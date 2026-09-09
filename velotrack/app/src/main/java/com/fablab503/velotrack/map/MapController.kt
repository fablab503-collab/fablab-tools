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
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import com.fablab503.velotrack.R
import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.geo.Simplify
import com.fablab503.velotrack.geo.ZoomController
import com.fablab503.velotrack.model.CameraMode
import com.fablab503.velotrack.model.Favorite
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
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

    /**
     * True to use the light map style (`assets/style_light.json`, light grey empty base), false for the
     * dark one (`assets/style.json`, black). Read when [loadStyle] runs, so set it before loading or
     * reloading the style; changing it afterwards has no effect until the next [loadStyle].
     */
    var lightMap: Boolean = false

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
    private var favoritesSource: GeoJsonSource? = null
    private var favoritesLayer: SymbolLayer? = null

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
    private var favoritePoints: List<Favorite> = emptyList()

    /** Marker images already added to the current style, by [favoriteImageName]; cleared on every
     *  style (re)load since [Style.addImage] does not survive one. */
    private val favoriteImagesAdded = HashSet<String>()

    // Camera bookkeeping.
    private var cameraApplied = false
    private var appliedPaddingTop: Double? = null
    private var appliedPaddingLeft: Double? = null

    /**
     * Pixels of map hidden behind a panel on the left (the landscape statistics panel), so the
     * follow camera keeps the puck centred in the map the rider can actually see. 0 in portrait.
     */
    var cameraPaddingLeftPx: Double = 0.0
        set(value) {
            val changed = field != value
            field = value
            if (changed) applyCameraNow()
        }
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
     * Loads the style template (`assets/style_light.json` when [lightMap], else `assets/style.json`)
     * with the band-file URLs substituted (`{MAP_URL_N}` for the N-th entry of [bandFiles], i.e.
     * band0.mbtiles .. band3.mbtiles in order), adds the route/track/puck sources and layers, then
     * calls [onDone] with `null` on success or an error message.
     *
     * A band file that does not exist yields a `null` URL, which [StyleTemplate.render] turns into an
     * empty `"tiles": []` source; when no band file exists at all (or the list is empty) a
     * self-contained background-only base style ([emptyStyleJson]) is loaded instead.
     * Safe to call before [onMapReady]: the load is deferred until the map exists.
     */
    fun loadStyle(bandFiles: List<File>, onDone: (String?) -> Unit) {
        val m = map
        if (m == null) {
            pendingLoad = Pair(bandFiles, onDone)
            return
        }
        val styleAsset = if (lightMap) STYLE_ASSET_LIGHT else STYLE_ASSET
        val template = try {
            context.assets.open(styleAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            onDone("Cannot read $styleAsset: ${e.message}")
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
        // the first render). Use a self-contained background-only style instead; setupStyle adds the overlays.
        val hasAnyFile = mapUrls.any { it != null }
        val emptyJson = emptyStyleJson()
        val json = if (hasAnyFile) StyleTemplate.render(template, mapUrls) else emptyJson

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
        if (hadStyle && json != emptyJson) {
            // MapLibre keeps sources whose definition did not change, together with the tiles it has
            // already fetched (including the "no content" ones from before a download). Passing
            // through a blank style forces every source to be recreated and its tiles re-read.
            m.setStyle(Style.Builder().fromJson(emptyJson)) { applyFinal() }
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
        if (appliedPaddingTop != desiredPaddingTop || appliedPaddingLeft != cameraPaddingLeftPx) {
            // Padding persists across later camera moves, so it is only sent when it changes
            // (mode switch, first fix, a layout change of the MapView, or the landscape panel).
            builder.padding(cameraPaddingLeftPx, desiredPaddingTop, 0.0, 0.0)
            appliedPaddingTop = desiredPaddingTop
            appliedPaddingLeft = cameraPaddingLeftPx
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
     * Draws every saved place (Home, Work, every favourite) as a small marker on the map, always
     * on -- not only while actively guiding to one. Each marker uses the favourite's own [Favorite.emoji]
     * when it has one, else [FavoriteKind]'s own glyph, same as [setTarget]'s highlighted pin. Call
     * this after any add, edit or delete; it replaces the whole set, it does not merge into it.
     */
    fun setFavorites(favorites: List<Favorite>) {
        favoritePoints = favorites
        pushFavorites()
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
        // 1. Named roads, most detailed band first.
        for (band in PLACE_BANDS) {
            val roads = m.queryRenderedFeatures(box, *withBand(ROAD_LAYER_BASES, listOf(band)))
            if (DEBUG_PLACE) Log.d(TAG, "roads b$band: ${roads.size} in $box ${roads.firstOrNull()?.properties()}")
            for (road in roads) nameOf(road)?.let { return it }
        }
        // 2. Green areas. Protomaps landuse polygons carry no name: the name sits on a POI point inside
        //    the polygon, so look it up in the same band's source (else fall back to the kind of area).
        for (band in PLACE_BANDS) {
            val greens = m.queryRenderedFeatures(box, *withBand(GREEN_LAYER_BASES, listOf(band)))
            if (DEBUG_PLACE) Log.d(TAG, "green b$band: ${greens.size} ${greens.firstOrNull()?.properties()}")
            for (green in greens) {
                nameOf(green)?.let { return it }
                greenNameFromPois(band, green, latLon)?.let { return it }
            }
        }
        // 3. Named water, 4. locality.
        for (bases in listOf(WATER_LAYER_BASES, LOCALITY_LAYER_BASES)) {
            val features = m.queryRenderedFeatures(box, *withBand(bases, PLACE_BANDS))
            for (feature in features) nameOf(feature)?.let { return it }
        }
        return null
    }

    /**
     * Name of a park-like POI point lying inside [green] (a rendered landuse polygon of band [band]),
     * the nearest one to [at] when several qualify; else a generic label for the polygon's kind.
     * The polygon geometry MapLibre returns is clipped to its tile, so a POI in a neighbouring tile is
     * not found and the kind label is shown instead.
     */
    private fun greenNameFromPois(band: Int, green: Feature, at: LatLon): String? {
        val rings = outerRings(green.geometry())
        if (rings.isNotEmpty()) {
            val source = style?.getSourceAs<VectorSource>("band$band")
            val pois: List<Feature> = try {
                source?.querySourceFeatures(arrayOf(SOURCE_LAYER_POIS), GREEN_POI_FILTER) ?: emptyList()
            } catch (e: RuntimeException) {
                emptyList()
            }
            if (DEBUG_PLACE) Log.d(TAG, "pois b$band: ${pois.size} rings=${rings.size}")
            var best: String? = null
            var bestD = Double.MAX_VALUE
            for (poi in pois) {
                val name = nameOf(poi) ?: continue
                val point = poi.geometry() as? Point ?: continue
                val pt = LatLon(point.latitude(), point.longitude())
                if (rings.none { ring -> pointInRing(pt, ring) }) continue
                val d = Geo.distanceM(at, pt)
                if (d < bestD) {
                    bestD = d
                    best = name
                }
            }
            if (best != null) return best
        }
        return kindLabel(green)
    }

    private fun outerRings(geometry: Geometry?): List<List<Point>> = when (geometry) {
        is Polygon -> listOfNotNull(geometry.coordinates().firstOrNull())
        is MultiPolygon -> geometry.coordinates().mapNotNull { it.firstOrNull() }
        else -> emptyList()
    }

    /** Ray casting on lon/lat; good enough for park-sized polygons. */
    private fun pointInRing(p: LatLon, ring: List<Point>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i].longitude()
            val yi = ring[i].latitude()
            val xj = ring[j].longitude()
            val yj = ring[j].latitude()
            val crosses = (yi > p.lat) != (yj > p.lat) &&
                p.lon < (xj - xi) * (p.lat - yi) / (yj - yi) + xi
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    /** Generic label for an unnamed green polygon from its Protomaps `kind`, or null for kinds not worth naming. */
    private fun kindLabel(green: Feature): String? {
        val kind = try {
            if (green.hasNonNullValueForProperty(PROPERTY_KIND)) green.getStringProperty(PROPERTY_KIND) else null
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val res = when (kind) {
            "park", "dog_park", "village_green", "recreation_ground" -> R.string.place_kind_park
            "golf_course" -> R.string.place_kind_golf_course
            "forest", "wood" -> R.string.place_kind_forest
            "garden" -> R.string.place_kind_garden
            "cemetery" -> R.string.place_kind_cemetery
            "beach", "sand" -> R.string.place_kind_beach
            "pitch" -> R.string.place_kind_pitch
            "playground" -> R.string.place_kind_playground
            "nature_reserve", "protected_area", "national_park" -> R.string.place_kind_nature_reserve
            "wetland" -> R.string.place_kind_wetland
            "grass", "scrub", "grassland", "meadow", "farmland", "orchard", "allotments" -> R.string.place_kind_green
            else -> return null
        }
        return context.getString(res)
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

    /**
     * Base style used when no band file exists (and as the blank pass-through on a reload): no tile
     * sources, a single background layer in light grey (`#F2F2F2`) when [lightMap], else black.
     */
    private fun emptyStyleJson(): String {
        val color = if (lightMap) EMPTY_BACKGROUND_LIGHT else EMPTY_BACKGROUND_DARK
        return """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$color"}}]}"""
    }

    private fun setupStyle(style: Style) {
        val lineOptions = GeoJsonOptions().withBuffer(64).withTolerance(0.5f)

        val route = GeoJsonSource(SOURCE_ROUTE, lineCollection(routePoints ?: emptyList()), lineOptions)
        val history = GeoJsonSource(SOURCE_TRACK_HISTORY, lineCollection(historyPoints), lineOptions)
        val live = GeoJsonSource(SOURCE_TRACK_LIVE, lineCollection(livePoints), lineOptions)
        val puck = GeoJsonSource(SOURCE_PUCK, puckCollection())
        val guidance = GeoJsonSource(SOURCE_GUIDANCE, guidanceCollection())
        val target = GeoJsonSource(SOURCE_TARGET, targetCollection())
        val favoritesGeo = GeoJsonSource(SOURCE_FAVORITES, favoritesCollection())
        style.addSource(route)
        style.addSource(history)
        style.addSource(live)
        style.addSource(puck)
        style.addSource(guidance)
        style.addSource(target)
        style.addSource(favoritesGeo)

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

        // Favourite markers first (so the puck and the highlighted guidance pin, added next, sit
        // on top of them): the shared target-<kind> images cover every favourite with no emoji,
        // and each one that has an emoji gets its own tiny bitmap.
        addTargetImages(style)
        for (fav in favoritePoints) ensureFavoriteImage(style, fav)
        val favoritesSymbol = SymbolLayer(LAYER_FAVORITES, SOURCE_FAVORITES).withProperties(
            iconImage(Expression.get(PROPERTY_IMAGE)),
            iconAnchor(Property.ICON_ANCHOR_CENTER),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconSize(1f),
        )
        style.addLayer(favoritesSymbol)

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
        favoritesSource = favoritesGeo
        routeLayer = routeLine
        historyLayer = historyLine
        liveLayer = liveLine
        puckLayer = puckSymbol
        guidanceLayer = guidanceLine
        targetLayer = targetSymbol
        favoritesLayer = favoritesSymbol
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
        favoritesSource = null
        favoritesLayer = null
        favoriteImagesAdded.clear()
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

    private fun pushFavorites() {
        if (!styleReady) return
        val s = style ?: return
        for (fav in favoritePoints) ensureFavoriteImage(s, fav)
        favoritesSource?.setGeoJson(favoritesCollection())
    }

    /** One point Feature per favourite, carrying [PROPERTY_IMAGE] for the layer's data-driven icon. */
    private fun favoritesCollection(): FeatureCollection {
        if (favoritePoints.isEmpty()) return FeatureCollection.fromFeatures(emptyList<Feature>())
        val features = favoritePoints.map { fav ->
            Feature.fromGeometry(Point.fromLngLat(fav.lon, fav.lat)).apply {
                addStringProperty(PROPERTY_IMAGE, favoriteImageName(fav))
            }
        }
        return FeatureCollection.fromFeatures(features)
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

        drawMarkerGlyph(canvas, cx, cy, dp, TARGET_GLYPH_DP, kind, emoji = null, tint = Color.WHITE)
        return bitmap
    }

    // ---------------------------------------------------------------- favourite markers (all, always on)

    /**
     * Image id for [fav]'s marker: the emoji itself when it has one (each distinct emoji only
     * needs one bitmap, however many favourites share it), else the same `target-<kind key>`
     * image [drawTargetBitmap] already draws and [addTargetImages] already keeps current.
     */
    private fun favoriteImageName(fav: Favorite): String =
        fav.emoji?.let { IMAGE_FAVORITE_EMOJI_PREFIX + it } ?: targetImageName(fav.kind)

    /** Adds [fav]'s marker image the first time it is needed; a no-op for the shared kind images
     *  (already added by [addTargetImages]) and for an emoji already drawn for an earlier favourite. */
    private fun ensureFavoriteImage(style: Style, fav: Favorite) {
        val emoji = fav.emoji ?: return
        val name = favoriteImageName(fav)
        if (!favoriteImagesAdded.add(name)) return
        style.addImage(name, drawFavoriteBitmap(emoji))
    }

    /**
     * Small marker for a favourite carrying its own emoji: a 24 dp disc in [colorSurfaceContainer]
     * -- a neutral bookmark colour, deliberately not [routeColor], so a rider can tell "a saved
     * place" apart from "the place I am currently being guided to" ([drawTargetBitmap]'s bigger,
     * coloured, tailed pin) even when they are the same spot -- with a thin outline and the emoji
     * centred, anchored at its own centre rather than a tail-to-point bottom anchor.
     */
    private fun drawFavoriteBitmap(emoji: String): Bitmap {
        val dp = context.resources.displayMetrics.density
        val size = (FAVORITE_DISC_DP * dp).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = FAVORITE_DISC_DP * dp / 2f
        val cx = size / 2f
        val cy = size / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = FAVORITE_MARKER_FILL
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = TARGET_RING_WIDTH_DP * dp
            color = FAVORITE_MARKER_RING
        }
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius - ring.strokeWidth / 2f, ring)
        drawMarkerGlyph(canvas, cx, cy, dp, FAVORITE_GLYPH_DP, kind = null, emoji = emoji, tint = FAVORITE_MARKER_RING)
        return bitmap
    }

    /**
     * Centres either [emoji] (as text, colour emoji render as-is regardless of [tint]) or, when
     * null, [kind]'s Material glyph tinted [tint], in a [sizeDp] box at ([cx], [cy]). Used by both
     * the highlighted guidance pin and the small always-on favourite markers so the two stay
     * visually related.
     */
    private fun drawMarkerGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        dp: Float,
        sizeDp: Float,
        kind: FavoriteKind?,
        emoji: String?,
        tint: Int,
    ) {
        val sizePx = sizeDp * dp
        if (emoji != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sizePx
                textAlign = Paint.Align.CENTER
            }
            val baseline = cy - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(emoji, cx, baseline, paint)
            return
        }
        val glyph = try {
            AppCompatResources.getDrawable(context, glyphRes(kind ?: FavoriteKind.PLACE))
        } catch (e: Resources.NotFoundException) {
            null
        } ?: return
        val d = glyph.mutate()
        d.setTint(tint)
        val glyphPx = sizePx.roundToInt().coerceAtLeast(1)
        val left = (cx - glyphPx / 2f).roundToInt()
        val top = (cy - glyphPx / 2f).roundToInt()
        d.setBounds(left, top, left + glyphPx, top + glyphPx)
        d.draw(canvas)
    }

    companion object {
        /** Dark style template (Protomaps dark flavour, black earth), used when [lightMap] is false. */
        const val STYLE_ASSET = "style.json"
        /** Light style template (Protomaps light flavour), used when [lightMap] is true. */
        const val STYLE_ASSET_LIGHT = "style_light.json"

        /** Background colours of [emptyStyleJson]. */
        private const val EMPTY_BACKGROUND_DARK = "#000000"
        private const val EMPTY_BACKGROUND_LIGHT = "#F2F2F2"

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
        const val SOURCE_FAVORITES = "favorites"
        const val LAYER_FAVORITES = "favorites-layer"
        /** One emoji marker image is shared by every favourite using that emoji. */
        const val IMAGE_FAVORITE_EMOJI_PREFIX = "favorite-emoji-"

        private const val TAG = "VeloMap"
        private const val DEBUG_PLACE = false
        private const val PROPERTY_NAME = "name"
        private const val PROPERTY_KIND = "kind"
        private const val PROPERTY_IMAGE = "image"
        private const val SOURCE_LAYER_POIS = "pois"
        private const val PLACE_QUERY_RADIUS_PX = 18f
        private const val GUIDANCE_LINE_WIDTH = 3f
        private const val TARGET_DISC_DP = 32f
        private const val TARGET_HEIGHT_DP = 40f
        private const val TARGET_RING_WIDTH_DP = 2f
        private const val TARGET_GLYPH_DP = 18f
        private const val TARGET_TAIL_HALF_WIDTH_DP = 5f
        /** Smaller and plainer than the guidance target pin: a bookmark, not "go here now". */
        private const val FAVORITE_DISC_DP = 22f
        private const val FAVORITE_GLYPH_DP = 13f
        private const val FAVORITE_MARKER_FILL = Color.WHITE
        private const val FAVORITE_MARKER_RING = Color.DKGRAY

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
        private val GREEN_POI_KINDS = listOf(
            "park", "dog_park", "village_green", "recreation_ground", "golf_course", "forest", "wood", "garden",
            "cemetery", "beach", "pitch", "playground", "nature_reserve", "protected_area", "national_park",
            "wetland", "grass", "scrub", "grassland", "meadow", "allotments", "zoo", "theme_park", "marina",
        )
        /** POI points that can name a green area; evaluated by MapLibre inside the loaded tiles. */
        private val GREEN_POI_FILTER: Expression = Expression.any(
            *GREEN_POI_KINDS.map { Expression.eq(Expression.get(PROPERTY_KIND), Expression.literal(it)) }.toTypedArray()
        )

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
