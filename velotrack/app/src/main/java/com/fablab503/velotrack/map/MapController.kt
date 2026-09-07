package com.fablab503.velotrack.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import com.fablab503.velotrack.geo.Simplify
import com.fablab503.velotrack.geo.ZoomController
import com.fablab503.velotrack.model.CameraMode
import com.fablab503.velotrack.model.GpsFix
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.settings.Prefs
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Owns everything MapLibre-related for the main screen: style loading with the local band tile
 * archives, puck / track / route layers, and the three camera modes.
 *
 * All methods must be called on the main thread. MapLibre is only touched after [onMapReady] and,
 * for style objects, after the style has finished loading ([styleReady]); until then data is kept in
 * fields and re-applied when the style is (re)loaded.
 */
class MapController(
    private val context: Context,
    private val mapView: MapView,
    private val prefs: Prefs,
) {

    /** Invoked whenever a user gesture moves the camera (the controller has already switched to FREE). */
    var onUserGesture: (() -> Unit)? = null

    /** FOLLOW_3D | FOLLOW_2D | FREE. Setting a follow mode applies the camera immediately. */
    var cameraMode: CameraMode = prefs.followMode
        set(value) {
            val changed = field != value
            field = value
            if (value != CameraMode.FREE) {
                lastFollowMode = value
                appliedPaddingTop = null // padding differs between the follow modes; force a re-set
                if (changed) applyCameraNow()
            }
        }

    private var map: MapLibreMap? = null
    private var styleReady = false
    /** True once any style has finished loading; a later reload then passes through a blank style first. */
    private var styleLoadedOnce = false
    private var pendingOnDone: ((String?) -> Unit)? = null
    private var pendingLoad: Pair<List<File>, (String?) -> Unit>? = null
    private var styleWatchdog: Runnable? = null

    private var lastFollowMode: CameraMode = prefs.followMode
    private val zoomController = ZoomController()

    // Style objects (valid only while styleReady).
    private var style: Style? = null
    private var routeSource: GeoJsonSource? = null
    private var historySource: GeoJsonSource? = null
    private var liveSource: GeoJsonSource? = null
    private var puckSource: GeoJsonSource? = null
    private var routeLayer: LineLayer? = null
    private var historyLayer: LineLayer? = null
    private var liveLayer: LineLayer? = null
    private var puckLayer: SymbolLayer? = null

    // Theme colours (ARGB). Defaults match the pre-Material-3 look; MainActivity overrides them from the theme.
    private var trackColor: Int = DEFAULT_TRACK_COLOR
    private var routeColor: Int = DEFAULT_ROUTE_COLOR
    private var puckColor: Int = DEFAULT_PUCK_COLOR
    private var puckOnColor: Int = DEFAULT_PUCK_ON_COLOR

    // Data kept so that a style reload re-applies it.
    private var routePoints: List<LatLon>? = null
    private val historyPoints = ArrayList<LatLon>()
    private val livePoints = ArrayList<LatLon>()
    private var lastFix: GpsFix? = null
    private var lastHeading: Float? = null

    // Camera bookkeeping.
    private var cameraApplied = false
    private var appliedPaddingTop: Double? = null
    private var lastNowMs: Long = 0L
    private var lastNowElapsedMs: Long = 0L

    private val failListener = MapView.OnDidFailLoadingMapListener { errorMessage ->
        cancelStyleWatchdog()
        val cb = pendingOnDone
        pendingOnDone = null
        cb?.invoke(errorMessage ?: "Map failed to load")
    }

    // ---------------------------------------------------------------- lifecycle

    /** Stores the map, disables UI widgets, caps the frame rate, disables prefetch and wires gesture detection. */
    @Suppress("DEPRECATION")
    fun onMapReady(map: MapLibreMap) {
        this.map = map
        map.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = false
            isTiltGesturesEnabled = true
            isRotateGesturesEnabled = true
        }
        try {
            mapView.setMaximumFps(MAX_FPS)
        } catch (e: IllegalStateException) {
            // Renderer not created yet; the default refresh mode is already WHEN_DIRTY.
        }
        map.setPrefetchesTiles(false)

        // Before the first fix, show the last known area instead of the whole world.
        if (lastFix == null) {
            prefs.lastPosition?.let { p ->
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), INITIAL_ZOOM_IDLE))
            }
        }

        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                handleUserGesture()
            }
        }
        mapView.addOnDidFailLoadingMapListener(failListener)

        pendingLoad?.let { (files, onDone) ->
            pendingLoad = null
            loadStyle(files, onDone)
        }
    }

    /**
     * Loads `assets/style.json` with the band-file URLs substituted (`{MAP_URL_N}` for the N-th entry
     * of [bandFiles], i.e. band0.mbtiles .. band3.mbtiles in order), adds the route/track/puck
     * sources and layers, then calls [onDone] with `null` on success or an error message.
     *
     * A band file that does not exist yields a `null` URL, which [StyleTemplate.render] turns into an
     * empty `"tiles": []` source; when no band file exists at all (or the list is empty) a
     * self-contained black base style is loaded instead.
     * Safe to call before [onMapReady]: the load is deferred until the map exists.
     */
    fun loadStyle(bandFiles: List<File>, onDone: (String?) -> Unit) {
        val m = map
        if (m == null) {
            pendingLoad = Pair(bandFiles, onDone)
            return
        }
        val template = try {
            context.assets.open(STYLE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            onDone("Cannot read $STYLE_ASSET: ${e.message}")
            return
        }
        val mapUrls = ArrayList<String?>(bandFiles.size)
        for (file in bandFiles) {
            if (!file.isFile) {
                mapUrls.add(null)
                continue
            }
            val url = try {
                MapUrl.forFile(file.absolutePath)
            } catch (e: IllegalArgumentException) {
                onDone(e.message ?: "Unsupported map file")
                return
            }
            mapUrls.add(url)
        }
        // Without any map file the template's vector sources would all have to be emitted with an
        // empty "tiles" array, and MapLibre's TileLoader indexes tiles[0] unguarded (native abort on
        // the first render). Use a self-contained black style instead; setupStyle adds the overlays.
        val hasAnyFile = mapUrls.any { it != null }
        val json = if (hasAnyFile) StyleTemplate.render(template, mapUrls) else EMPTY_STYLE_JSON

        val hadStyle = styleLoadedOnce
        styleReady = false
        clearStyleRefs()
        pendingOnDone = onDone
        val applyFinal = {
            m.setStyle(Style.Builder().fromJson(json)) { style ->
                cancelStyleWatchdog()
                setupStyle(style)
                styleReady = true
                styleLoadedOnce = true
                val cb = pendingOnDone
                pendingOnDone = null
                cb?.invoke(null)
            }
            armStyleWatchdog(onDone)
        }
        if (hadStyle && json != EMPTY_STYLE_JSON) {
            // MapLibre keeps sources whose definition did not change, together with the tiles it has
            // already fetched (including the "no content" ones from before a download). Passing
            // through a blank style forces every source to be recreated and its tiles re-read.
            m.setStyle(Style.Builder().fromJson(EMPTY_STYLE_JSON)) { applyFinal() }
        } else {
            applyFinal()
        }
    }

    /**
     * A source that fails to open (unreadable/corrupt archive) is only reported through
     * onResourceError, which MapLibre ignores in continuous mode, and it keeps the style from ever
     * reaching the loaded state: neither [failListener] nor the setStyle callback fires. This timer
     * turns that silence into an error so the caller can deactivate the file and fall back.
     */
    private fun armStyleWatchdog(onDone: (String?) -> Unit) {
        cancelStyleWatchdog()
        val r = Runnable {
            styleWatchdog = null
            if (pendingOnDone !== onDone) return@Runnable // superseded or already completed
            pendingOnDone = null
            onDone(STYLE_TIMEOUT_MESSAGE)
        }
        styleWatchdog = r
        mapView.postDelayed(r, STYLE_TIMEOUT_MS)
    }

    private fun cancelStyleWatchdog() {
        styleWatchdog?.let { mapView.removeCallbacks(it) }
        styleWatchdog = null
    }

    /** Drops MapLibre references; the controller is unusable afterwards. */
    fun onDestroy() {
        cancelStyleWatchdog()
        mapView.removeOnDidFailLoadingMapListener(failListener)
        clearStyleRefs()
        styleReady = false
        pendingOnDone = null
        pendingLoad = null
        onUserGesture = null
        map = null
    }

    // ---------------------------------------------------------------- position and camera

    /** Moves the puck and, in a follow mode, eases the camera over one GPS interval. */
    fun updatePosition(fix: GpsFix, headingDeg: Float?, nowMs: Long) {
        lastFix = fix
        if (headingDeg != null) lastHeading = headingDeg
        lastNowMs = nowMs
        lastNowElapsedMs = SystemClock.elapsedRealtime()

        pushPuck()

        if (cameraMode == CameraMode.FREE) return
        val m = map ?: return
        val pos = buildCamera(m, fix, nowMs) ?: return
        m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), EASE_MS, false)
        cameraApplied = true
    }

    /** Returns to the last follow mode (default: the preference) and moves the camera right away. */
    fun recenter() {
        val target = lastFollowMode
        if (cameraMode == target) {
            applyCameraNow()
        } else {
            cameraMode = target // setter applies the camera
        }
    }

    private fun applyCameraNow() {
        val m = map ?: return
        val fix = lastFix ?: return
        if (cameraMode == CameraMode.FREE) return
        val pos = buildCamera(m, fix, nowInCallerClock()) ?: return
        m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), RECENTER_MS, true)
        cameraApplied = true
    }

    private fun buildCamera(m: MapLibreMap, fix: GpsFix, nowMs: Long): CameraPosition? {
        val builder = CameraPosition.Builder().target(LatLng(fix.lat, fix.lon))
        val desiredPaddingTop: Double
        when (cameraMode) {
            CameraMode.FOLLOW_3D -> {
                val currentZoom = if (cameraApplied) m.cameraPosition.zoom else INITIAL_ZOOM_3D
                val speedKmh = ((fix.speedMps ?: 0f) * 3.6f).toDouble()
                val zoom = zoomController.next(speedKmh, currentZoom, nowMs) ?: currentZoom
                val bearing = (lastHeading ?: 0f).toDouble()
                builder.zoom(zoom).tilt(prefs.pitchDeg).bearing(bearing)
                desiredPaddingTop = mapView.height * PUCK_TOP_PADDING_FRACTION
            }
            CameraMode.FOLLOW_2D -> {
                builder.zoom(ZOOM_2D).tilt(0.0).bearing(0.0)
                desiredPaddingTop = 0.0
            }
            CameraMode.FREE -> return null
        }
        if (appliedPaddingTop != desiredPaddingTop) {
            // Padding persists across later camera moves, so it is only sent when it changes
            // (mode switch, first fix, or a layout change of the MapView).
            builder.padding(0.0, desiredPaddingTop, 0.0, 0.0)
            appliedPaddingTop = desiredPaddingTop
        }
        return builder.build()
    }

    private fun handleUserGesture() {
        if (cameraMode != CameraMode.FREE) {
            lastFollowMode = cameraMode
            cameraMode = CameraMode.FREE
        }
        zoomController.onUserGesture(nowInCallerClock())
        onUserGesture?.invoke()
    }

    /**
     * The zoom controller compares timestamps supplied by the caller of [updatePosition]; gestures
     * are stamped in that same clock by extrapolating from the last fix with elapsedRealtime.
     */
    private fun nowInCallerClock(): Long =
        if (lastNowElapsedMs == 0L) System.currentTimeMillis()
        else lastNowMs + (SystemClock.elapsedRealtime() - lastNowElapsedMs)

    // ---------------------------------------------------------------- track and route data

    /** Replaces the history line with [points] simplified to 2 m; the live tail restarts from the last point. */
    fun setTrackHistory(points: List<LatLon>) {
        historyPoints.clear()
        historyPoints.addAll(Simplify.rdp(points, HISTORY_TOLERANCE_M))
        livePoints.clear()
        historyPoints.lastOrNull()?.let { livePoints.add(it) } // keeps the live line joined to history
        pushHistory()
        pushLive()
    }

    /** Appends to the live line; every [LIVE_CAP] points the tail is simplified into history. */
    fun appendTrackPoint(p: LatLon) {
        livePoints.add(p)
        if (livePoints.size >= LIVE_CAP) {
            val chunk = ArrayList(livePoints)
            historyPoints.addAll(Simplify.rdp(chunk, HISTORY_TOLERANCE_M))
            livePoints.clear()
            livePoints.add(chunk[chunk.size - 1]) // seed so the lines stay connected
            pushHistory()
        }
        pushLive()
    }

    fun clearTrack() {
        historyPoints.clear()
        livePoints.clear()
        pushHistory()
        pushLive()
    }

    fun setRoute(points: List<LatLon>?) {
        routePoints = points
        pushRoute()
    }

    // ---------------------------------------------------------------- theme colours

    /**
     * Sets the ARGB colours of the track lines, the route line and the puck. Safe to call at any time:
     * the colours are stored and re-applied on every style (re)load; if the style is already loaded the
     * line layers are updated in place and the puck image is replaced.
     */
    fun setThemeColors(trackColor: Int, routeColor: Int, puckColor: Int, puckOnColor: Int) {
        this.trackColor = trackColor
        this.routeColor = routeColor
        this.puckColor = puckColor
        this.puckOnColor = puckOnColor
        if (!styleReady) return
        routeLayer?.setProperties(lineColor(routeColor))
        historyLayer?.setProperties(lineColor(trackColor))
        liveLayer?.setProperties(lineColor(trackColor))
        // addImage with an existing name replaces the image; the symbol layer keeps referencing it.
        style?.addImage(IMAGE_PUCK, drawPuckBitmap())
    }

    // ---------------------------------------------------------------- style setup

    private fun setupStyle(style: Style) {
        val lineOptions = GeoJsonOptions().withBuffer(64).withTolerance(0.5f)

        val route = GeoJsonSource(SOURCE_ROUTE, lineCollection(routePoints ?: emptyList()), lineOptions)
        val history = GeoJsonSource(SOURCE_TRACK_HISTORY, lineCollection(historyPoints), lineOptions)
        val live = GeoJsonSource(SOURCE_TRACK_LIVE, lineCollection(livePoints), lineOptions)
        val puck = GeoJsonSource(SOURCE_PUCK, puckCollection())
        style.addSource(route)
        style.addSource(history)
        style.addSource(live)
        style.addSource(puck)

        // Added in order: route below the track lines, puck on top.
        val routeLine = LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
            lineColor(routeColor),
            lineWidth(6f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        val historyLine = LineLayer(LAYER_TRACK_HISTORY, SOURCE_TRACK_HISTORY).withProperties(
            lineColor(trackColor),
            lineWidth(5f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        val liveLine = LineLayer(LAYER_TRACK_LIVE, SOURCE_TRACK_LIVE).withProperties(
            lineColor(trackColor),
            lineWidth(5f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        style.addLayer(routeLine)
        style.addLayer(historyLine)
        style.addLayer(liveLine)

        style.addImage(IMAGE_PUCK, drawPuckBitmap())
        val puckSymbol = SymbolLayer(LAYER_PUCK, SOURCE_PUCK).withProperties(
            iconImage(IMAGE_PUCK),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconSize(1f),
            iconRotate(lastHeading ?: 0f),
        )
        style.addLayer(puckSymbol)

        this.style = style
        routeSource = route
        historySource = history
        liveSource = live
        puckSource = puck
        routeLayer = routeLine
        historyLayer = historyLine
        liveLayer = liveLine
        puckLayer = puckSymbol
    }

    private fun clearStyleRefs() {
        style = null
        routeSource = null
        historySource = null
        liveSource = null
        puckSource = null
        routeLayer = null
        historyLayer = null
        liveLayer = null
        puckLayer = null
    }

    private fun pushRoute() {
        if (!styleReady) return
        routeSource?.setGeoJson(lineCollection(routePoints ?: emptyList()))
    }

    private fun pushHistory() {
        if (!styleReady) return
        historySource?.setGeoJson(lineCollection(historyPoints))
    }

    private fun pushLive() {
        if (!styleReady) return
        liveSource?.setGeoJson(lineCollection(livePoints))
    }

    private fun pushPuck() {
        if (!styleReady) return
        puckSource?.setGeoJson(puckCollection())
        puckLayer?.setProperties(iconRotate(lastHeading ?: 0f))
    }

    private fun puckCollection(): FeatureCollection {
        val fix = lastFix ?: return FeatureCollection.fromFeatures(emptyList<Feature>())
        return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat))))
    }

    /** A GeoJSON line needs at least two positions; anything shorter becomes an empty collection. */
    private fun lineCollection(points: List<LatLon>): FeatureCollection {
        if (points.size < 2) return FeatureCollection.fromFeatures(emptyList<Feature>())
        val coords = ArrayList<Point>(points.size)
        for (p in points) coords.add(Point.fromLngLat(p.lon, p.lat))
        return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(coords))))
    }

    /**
     * Google-Maps-style puck on a 40dp canvas (density-aware): a soft [puckColor] halo, a [puckColor]
     * disc with a 2dp [puckOnColor] ring and a [puckOnColor] chevron pointing up (north before
     * rotation). Drawn in code so no drawable resource is needed.
     */
    private fun drawPuckBitmap(): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (PUCK_SIZE_DP * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val dp = density

        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (puckColor and 0x00FFFFFF) or (PUCK_HALO_ALPHA shl 24)
        }
        canvas.drawCircle(cx, cy, PUCK_HALO_RADIUS_DP * dp, halo)

        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = puckColor
        }
        canvas.drawCircle(cx, cy, PUCK_DISC_RADIUS_DP * dp, disc)

        val ringWidth = PUCK_RING_WIDTH_DP * dp
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringWidth
            color = puckOnColor
        }
        // Stroke is centred on the radius, so pull it in by half its width to keep the outer edge at 11dp.
        canvas.drawCircle(cx, cy, PUCK_DISC_RADIUS_DP * dp - ringWidth / 2f, ring)

        val half = PUCK_CHEVRON_HEIGHT_DP * dp / 2f
        val chevron = Path().apply {
            moveTo(cx, cy - half)                    // tip
            lineTo(cx + half, cy + half)             // right base
            lineTo(cx, cy + half * 0.45f)            // notch
            lineTo(cx - half, cy + half)             // left base
            close()
        }
        val chevronFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = puckOnColor
        }
        canvas.drawPath(chevron, chevronFill)
        return bitmap
    }

    companion object {
        const val STYLE_ASSET = "style.json"

        /** Base style used when no band file exists: no tile sources, black background. */
        private const val EMPTY_STYLE_JSON =
            """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#000000"}}]}"""

        private const val STYLE_TIMEOUT_MS = 15_000L
        private const val STYLE_TIMEOUT_MESSAGE =
            "Map style did not finish loading; the map file is probably unreadable"

        const val SOURCE_ROUTE = "route"
        const val SOURCE_TRACK_HISTORY = "track-history"
        const val SOURCE_TRACK_LIVE = "track-live"
        const val SOURCE_PUCK = "puck"
        const val LAYER_ROUTE = "route-line"
        const val LAYER_TRACK_HISTORY = "track-history-line"
        const val LAYER_TRACK_LIVE = "track-live-line"
        const val LAYER_PUCK = "puck-layer"
        const val IMAGE_PUCK = "puck"

        // ARGB defaults (Long literals narrowed to Int; not const because of the conversion call).
        private val DEFAULT_TRACK_COLOR: Int = 0xFF42A5F5.toInt()
        private val DEFAULT_ROUTE_COLOR: Int = 0xFFFF9800.toInt()
        private val DEFAULT_PUCK_COLOR: Int = 0xFF42A5F5.toInt()
        private val DEFAULT_PUCK_ON_COLOR: Int = Color.WHITE

        private const val MAX_FPS = 30
        private const val EASE_MS = 1000
        private const val RECENTER_MS = 500
        private const val INITIAL_ZOOM_3D = 17.0
        private const val INITIAL_ZOOM_IDLE = 15.0
        private const val ZOOM_2D = 15.5
        private const val PUCK_TOP_PADDING_FRACTION = 0.55
        private const val PUCK_SIZE_DP = 40f
        private const val PUCK_HALO_RADIUS_DP = 20f
        private const val PUCK_DISC_RADIUS_DP = 11f
        private const val PUCK_RING_WIDTH_DP = 2f
        private const val PUCK_CHEVRON_HEIGHT_DP = 10f
        private const val PUCK_HALO_ALPHA = 0x33
        private const val HISTORY_TOLERANCE_M = 2.0
        private const val LIVE_CAP = 500
    }
}
