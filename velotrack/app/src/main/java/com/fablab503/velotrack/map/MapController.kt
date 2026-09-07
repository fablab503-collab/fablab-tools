package com.fablab503.velotrack.map

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import androidx.appcompat.content.res.AppCompatResources
import com.fablab503.velotrack.R
import com.fablab503.velotrack.geo.Simplify
import com.fablab503.velotrack.geo.ZoomController
import com.fablab503.velotrack.model.CameraMode
import com.fablab503.velotrack.model.FavoriteKind
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
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
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
    private var targetSource: GeoJsonSource? = null
    private var guidanceSource: GeoJsonSource? = null
    private var targetLayer: SymbolLayer? = null
    private var guidanceLayer: LineLayer? = null

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
    private var targetPoint: LatLon? = null
    private var targetKind: FavoriteKind? = null
    private var guidanceFrom: LatLon? = null
    private var guidanceTo: LatLon? = null

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

    // ---------------------------------------------------------------- guidance (favourites)

    /**
     * Shows a marker for the guidance target with the image of [kind] (`target-<kind>`, a null kind
     * uses the generic place image), or clears the marker when [target] is null. Kept across style
     * reloads.
     */
    fun setTarget(target: LatLon?, kind: FavoriteKind?) {
        targetPoint = target
        targetKind = kind
        pushTarget()
    }

    /**
     * Draws the dashed straight guidance line between [from] and [to] (route colour, 3 px, dash 2/2);
     * either null clears the line. Kept across style reloads.
     */
    fun setGuidanceLine(from: LatLon?, to: LatLon?) {
        guidanceFrom = from
        guidanceTo = to
        pushGuidance()
    }

    /**
     * Names of the map features under a screen point: nearest named road, else park/green, else water,
     * else locality. Null when nothing named is there or the style is not ready.
     *
     * Queries the rendered features in an 18 px box around the projected [latLon], one layer group at a
     * time in priority order, and returns the first non-blank `name` property.
     */
    fun placeNameAt(latLon: LatLon): String? {
        if (!styleReady) return null
        val m = map ?: return null
        val screen = m.projection.toScreenLocation(LatLng(latLon.lat, latLon.lon))
        val r = PLACE_QUERY_RADIUS_PX
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        for (group in PLACE_LAYER_GROUPS) {
            val features = m.queryRenderedFeatures(box, *group)
            for (feature in features) {
                val name = nameOf(feature)
                if (name != null) return name
            }
        }
        return null
    }

    /** The feature's `name` property when it is a non-blank string; tile data is untrusted, so never throw. */
    private fun nameOf(feature: Feature): String? {
        if (!feature.hasNonNullValueForProperty(PROPERTY_NAME)) return null
        val name = try {
            feature.getStringProperty(PROPERTY_NAME)
        } catch (e: RuntimeException) {
            null // not a JSON primitive (Gson throws on arrays/objects)
        }
        return if (name.isNullOrBlank()) null else name.trim()
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
        guidanceLayer?.setProperties(lineColor(routeColor))
        // addImage with an existing name replaces the image; the symbol layers keep referencing it.
        style?.let { s ->
            s.addImage(IMAGE_PUCK, drawPuckBitmap())
            addTargetImages(s)
        }
    }

    // ---------------------------------------------------------------- style setup

    private fun setupStyle(style: Style) {
        val lineOptions = GeoJsonOptions().withBuffer(64).withTolerance(0.5f)

        val route = GeoJsonSource(SOURCE_ROUTE, lineCollection(routePoints ?: emptyList()), lineOptions)
        val history = GeoJsonSource(SOURCE_TRACK_HISTORY, lineCollection(historyPoints), lineOptions)
        val live = GeoJsonSource(SOURCE_TRACK_LIVE, lineCollection(livePoints), lineOptions)
        val puck = GeoJsonSource(SOURCE_PUCK, puckCollection())
        val guidance = GeoJsonSource(SOURCE_GUIDANCE, guidanceCollection())
        val target = GeoJsonSource(SOURCE_TARGET, targetCollection())
        style.addSource(route)
        style.addSource(history)
        style.addSource(live)
        style.addSource(puck)
        style.addSource(guidance)
        style.addSource(target)

        // Added in order: route below the track lines, then the guidance line, puck, target marker on top.
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
        val guidanceLine = LineLayer(LAYER_GUIDANCE, SOURCE_GUIDANCE).withProperties(
            lineColor(routeColor),
            lineWidth(GUIDANCE_LINE_WIDTH),
            lineDasharray(arrayOf(2f, 2f)),
            lineCap(Property.LINE_CAP_BUTT),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        style.addLayer(routeLine)
        style.addLayer(historyLine)
        style.addLayer(liveLine)
        style.addLayer(guidanceLine)

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

        addTargetImages(style)
        val targetSymbol = SymbolLayer(LAYER_TARGET, SOURCE_TARGET).withProperties(
            iconImage(targetImageName(targetKind)),
            iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconSize(1f),
        )
        style.addLayer(targetSymbol)

        this.style = style
        routeSource = route
        historySource = history
        liveSource = live
        puckSource = puck
        guidanceSource = guidance
        targetSource = target
        routeLayer = routeLine
        historyLayer = historyLine
        liveLayer = liveLine
        puckLayer = puckSymbol
        guidanceLayer = guidanceLine
        targetLayer = targetSymbol
    }

    private fun clearStyleRefs() {
        style = null
        routeSource = null
        historySource = null
        liveSource = null
        puckSource = null
        guidanceSource = null
        targetSource = null
        routeLayer = null
        historyLayer = null
        liveLayer = null
        puckLayer = null
        guidanceLayer = null
        targetLayer = null
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

    private fun pushTarget() {
        if (!styleReady) return
        targetLayer?.setProperties(iconImage(targetImageName(targetKind)))
        targetSource?.setGeoJson(targetCollection())
    }

    private fun pushGuidance() {
        if (!styleReady) return
        guidanceSource?.setGeoJson(guidanceCollection())
    }

    private fun puckCollection(): FeatureCollection {
        val fix = lastFix ?: return FeatureCollection.fromFeatures(emptyList<Feature>())
        return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat))))
    }

    private fun targetCollection(): FeatureCollection {
        val p = targetPoint ?: return FeatureCollection.fromFeatures(emptyList<Feature>())
        return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))))
    }

    /** Empty unless both ends are set (a GeoJSON line needs two positions). */
    private fun guidanceCollection(): FeatureCollection {
        val from = guidanceFrom
        val to = guidanceTo
        if (from == null || to == null) return FeatureCollection.fromFeatures(emptyList<Feature>())
        return lineCollection(listOf(from, to))
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

    // ---------------------------------------------------------------- target marker images

    private fun targetImageName(kind: FavoriteKind?): String =
        IMAGE_TARGET_PREFIX + (kind ?: FavoriteKind.PLACE).key

    /** Adds (or replaces) the six `target-<kind>` marker images in [style]. */
    private fun addTargetImages(style: Style) {
        for (kind in FavoriteKind.entries) {
            style.addImage(targetImageName(kind), drawTargetBitmap(kind))
        }
    }

    /** The Material Symbols drawable used as the marker glyph of [kind] (drawables are owned by the ui module). */
    private fun glyphRes(kind: FavoriteKind): Int = when (kind) {
        FavoriteKind.HOME -> R.drawable.ic_home
        FavoriteKind.WORK -> R.drawable.ic_work
        FavoriteKind.PERSON -> R.drawable.ic_person
        FavoriteKind.RESTAURANT -> R.drawable.ic_restaurant
        FavoriteKind.THEATRE -> R.drawable.ic_theater_comedy
        FavoriteKind.PLACE -> R.drawable.ic_place
    }

    /**
     * Pin marker for [kind] on a 32 x 40 dp canvas (density-aware): a 32 dp disc in [routeColor] with a
     * 2 dp white ring, a short white-edged tail down to the anchor point at the bottom centre, and the
     * kind's glyph tinted white at 18 dp in the disc. Falls back to the plain disc when the glyph
     * drawable cannot be loaded.
     */
    private fun drawTargetBitmap(kind: FavoriteKind): Bitmap {
        val density = context.resources.displayMetrics.density
        val dp = density
        val width = (TARGET_DISC_DP * density).roundToInt().coerceAtLeast(1)
        val height = (TARGET_HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = TARGET_DISC_DP * dp / 2f
        val cx = width / 2f
        val cy = radius

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = routeColor
        }
        val ringWidth = TARGET_RING_WIDTH_DP * dp
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringWidth
            color = Color.WHITE
        }

        // Tail: a triangle from the lower part of the disc to the anchor point at the bottom centre.
        val tailHalf = TARGET_TAIL_HALF_WIDTH_DP * dp
        val tail = Path().apply {
            moveTo(cx - tailHalf, cy + radius * 0.55f)
            lineTo(cx, height.toFloat() - ringWidth / 2f)
            lineTo(cx + tailHalf, cy + radius * 0.55f)
            close()
        }
        canvas.drawPath(tail, fill)
        canvas.drawPath(tail, ring)

        canvas.drawCircle(cx, cy, radius, fill)
        // Stroke is centred on the radius, so pull it in by half its width to keep the outer edge at 16dp.
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, ring)

        val glyph = try {
            AppCompatResources.getDrawable(context, glyphRes(kind))
        } catch (e: Resources.NotFoundException) {
            null
        }
        if (glyph != null) {
            val d = glyph.mutate()
            d.setTint(Color.WHITE)
            val glyphPx = (TARGET_GLYPH_DP * dp).roundToInt().coerceAtLeast(1)
            val left = (cx - glyphPx / 2f).roundToInt()
            val top = (cy - glyphPx / 2f).roundToInt()
            d.setBounds(left, top, left + glyphPx, top + glyphPx)
            d.draw(canvas)
        }
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
        const val SOURCE_TARGET = "target"
        const val SOURCE_GUIDANCE = "guidance"
        const val LAYER_TARGET = "target-layer"
        const val LAYER_GUIDANCE = "guidance-line"
        /** Marker image ids are `target-<kind key>`, e.g. `target-home`. */
        const val IMAGE_TARGET_PREFIX = "target-"

        private const val PROPERTY_NAME = "name"
        private const val PLACE_QUERY_RADIUS_PX = 18f
        private const val GUIDANCE_LINE_WIDTH = 3f
        private const val TARGET_DISC_DP = 32f
        private const val TARGET_HEIGHT_DP = 40f
        private const val TARGET_RING_WIDTH_DP = 2f
        private const val TARGET_GLYPH_DP = 18f
        private const val TARGET_TAIL_HALF_WIDTH_DP = 5f

        /** Bands searched by [placeNameAt], most detailed first (band 0 is the low-zoom overview). */
        private val PLACE_BANDS = listOf(3, 2, 1)
        private val ROAD_LAYER_BASES = listOf(
            "roads_minor", "roads_major", "roads_highway", "roads_other", "roads_link", "roads_minor_service",
            "roads_bridges_minor", "roads_bridges_major", "roads_bridges_highway", "roads_bridges_other",
            "roads_tunnels_minor", "roads_tunnels_major", "roads_tunnels_highway", "roads_tunnels_other",
        )
        private val GREEN_LAYER_BASES = listOf("landuse_park", "landuse_urban_green")
        private val WATER_LAYER_BASES = listOf("water")
        private val LOCALITY_LAYER_BASES = listOf("places_locality")

        /**
         * Layer-id groups queried by [placeNameAt] in priority order: roads of band 3, band 2, band 1,
         * then green areas, water and localities (each across bands 3, 2, 1).
         */
        private val PLACE_LAYER_GROUPS: List<Array<String>> = buildList {
            for (band in PLACE_BANDS) add(withBand(ROAD_LAYER_BASES, listOf(band)))
            add(withBand(GREEN_LAYER_BASES, PLACE_BANDS))
            add(withBand(WATER_LAYER_BASES, PLACE_BANDS))
            add(withBand(LOCALITY_LAYER_BASES, PLACE_BANDS))
        }

        private fun withBand(bases: List<String>, bands: List<Int>): Array<String> {
            val ids = ArrayList<String>(bases.size * bands.size)
            for (band in bands) for (base in bases) ids.add("${base}_b$band")
            return ids.toTypedArray()
        }

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
