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

/**
 * Owns everything MapLibre-related for the main screen: style loading with the local tile archive,
 * puck / track / route layers, and the three camera modes.
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
    private var pendingOnDone: ((String?) -> Unit)? = null
    private var pendingLoad: Pair<File?, (String?) -> Unit>? = null
    private var styleWatchdog: Runnable? = null

    private var lastFollowMode: CameraMode = prefs.followMode
    private val zoomController = ZoomController()

    // Style objects (valid only while styleReady).
    private var routeSource: GeoJsonSource? = null
    private var historySource: GeoJsonSource? = null
    private var liveSource: GeoJsonSource? = null
    private var puckSource: GeoJsonSource? = null
    private var puckLayer: SymbolLayer? = null

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

        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                handleUserGesture()
            }
        }
        mapView.addOnDidFailLoadingMapListener(failListener)

        pendingLoad?.let { (file, onDone) ->
            pendingLoad = null
            loadStyle(file, onDone)
        }
    }

    /**
     * Loads `assets/style.json` with the tile URL substituted, adds the route/track/puck sources and
     * layers, then calls [onDone] with `null` on success or an error message.
     * Safe to call before [onMapReady]: the load is deferred until the map exists.
     */
    fun loadStyle(mapFile: File?, onDone: (String?) -> Unit) {
        val m = map
        if (m == null) {
            pendingLoad = Pair(mapFile, onDone)
            return
        }
        val template = try {
            context.assets.open(STYLE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            onDone("Cannot read $STYLE_ASSET: ${e.message}")
            return
        }
        val mapUrl: String? = if (mapFile != null) {
            try {
                MapUrl.forFile(mapFile.absolutePath)
            } catch (e: IllegalArgumentException) {
                onDone(e.message ?: "Unsupported map file")
                return
            }
        } else {
            null
        }
        // Without a map file the template's vector source would have to be emitted with an empty
        // "tiles" array, and MapLibre's TileLoader indexes tiles[0] unguarded (native abort on the
        // first render). Use a self-contained black style instead; setupStyle adds the overlays.
        val json = if (mapUrl != null) StyleTemplate.render(template, mapUrl) else EMPTY_STYLE_JSON

        styleReady = false
        clearStyleRefs()
        pendingOnDone = onDone
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            cancelStyleWatchdog()
            setupStyle(style)
            styleReady = true
            val cb = pendingOnDone
            pendingOnDone = null
            cb?.invoke(null)
        }
        armStyleWatchdog(onDone)
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
        style.addLayer(
            LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
                lineColor(ROUTE_COLOR),
                lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        style.addLayer(
            LineLayer(LAYER_TRACK_HISTORY, SOURCE_TRACK_HISTORY).withProperties(
                lineColor(TRACK_COLOR),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        style.addLayer(
            LineLayer(LAYER_TRACK_LIVE, SOURCE_TRACK_LIVE).withProperties(
                lineColor(TRACK_COLOR),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            )
        )

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

        routeSource = route
        historySource = history
        liveSource = live
        puckSource = puck
        puckLayer = puckSymbol
    }

    private fun clearStyleRefs() {
        routeSource = null
        historySource = null
        liveSource = null
        puckSource = null
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
     * 96x96 px puck: a semi-transparent blue disc with a white, black-outlined arrow pointing up
     * (north before rotation). Drawn in code so no drawable resource is needed.
     */
    private fun drawPuckBitmap(): Bitmap {
        val size = PUCK_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(0x55, 0x42, 0xA5, 0xF5)
        }
        canvas.drawCircle(cx, cy, size * 0.42f, disc)

        val arrow = Path().apply {
            moveTo(cx, size * 0.10f)                 // tip
            lineTo(size * 0.78f, size * 0.74f)       // right base
            lineTo(cx, size * 0.58f)                 // notch
            lineTo(size * 0.22f, size * 0.74f)       // left base
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.035f
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        return bitmap
    }

    companion object {
        const val STYLE_ASSET = "style.json"

        /** Base style used when no map file is active: no tile sources, black background. */
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

        private const val ROUTE_COLOR = "#FF9800"
        private const val TRACK_COLOR = "#42A5F5"

        private const val MAX_FPS = 30
        private const val EASE_MS = 1000
        private const val RECENTER_MS = 500
        private const val INITIAL_ZOOM_3D = 17.0
        private const val ZOOM_2D = 15.5
        private const val PUCK_TOP_PADDING_FRACTION = 0.55
        private const val PUCK_SIZE_PX = 96
        private const val HISTORY_TOLERANCE_M = 2.0
        private const val LIVE_CAP = 500
    }
}
