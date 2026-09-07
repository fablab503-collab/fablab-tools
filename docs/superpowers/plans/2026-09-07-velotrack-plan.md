# VeloTrack implementation plan and module contracts

Companion to `docs/superpowers/specs/2026-09-07-velotrack-design.md`. This file is the binding
contract between the modules: every public signature listed here is implemented exactly as written
so that modules written in parallel fit together without a compiler round-trip. Files marked
**(written)** already exist and must not be changed by module authors; everything else is to be
created.

Root of the Android project: `velotrack/`. Package root: `com.fablab503.velotrack`.
Source root: `velotrack/app/src/main/java/com/fablab503/velotrack/`.
Test root: `velotrack/app/src/test/java/com/fablab503/velotrack/`.

## Rules for every module

- Kotlin only, JVM 17, Android minSdk 26, compileSdk 36. AGP 9.4.0 with built-in Kotlin 2.2.10:
  no `kotlin-android` plugin, no kapt, no KSP, no Compose, no Room, no Hilt. Views + ViewBinding.
- Only these dependencies exist (see `velotrack/gradle/libs.versions.toml`): AndroidX core-ktx,
  appcompat, material, activity-ktx, lifecycle-service, lifecycle-runtime-ktx, preference-ktx,
  constraintlayout, kotlinx-coroutines-android, MapLibre `android-sdk-opengl` 13.6.0, JUnit 4.
- Pure modules (`geo`, `gpx`, `route`, `recording.PointFilter`, `recording.RideStats`, `map.MapUrl`,
  `map.StyleTemplate`) must not import anything from `android.*` so their JUnit tests run on the
  JVM. `gpx.GpxParser` uses `javax.xml.parsers.SAXParserFactory` (available on Android and JVM),
  never `XmlPullParser`.
- No `INTERNET` permission, no network code anywhere.
- Every nullable Android API result is handled; no `!!` on system services.
- Strings shown to the user live in `res/values/strings.xml` (owned by the `ui` module). Modules
  outside `ui` never reference `R.string`; they pass codes or plain text upward.
- Numbers formatted for files use `Locale.ROOT`.

## Shared model — `model/Models.kt` (written)

See the file for the exact definitions of `LatLon`, `GpsFix`, `TrackPoint`, `GpsStatus`,
`RecordingStatus`, `RideStatsSnapshot`, `RideState`, `TrackSummary`, `RouteSummary`,
`RouteProgress`, `CameraMode`, `ScreenMode`, `Units`.

## Settings — `settings/Prefs.kt` (written)

`class Prefs(context: Context)` wrapping the default SharedPreferences with typed properties. Keys
are public constants so `res/xml/preferences.xml` can reference them.

## Ride session — `recording/RideSession.kt` (written)

`object RideSession { val state: StateFlow<RideState>; fun update(transform: (RideState) -> RideState); var serviceRunning: Boolean }`.

---

## Module A — `geo` (pure)

`geo/Geo.kt`:

```kotlin
object Geo {
    const val EARTH_RADIUS_M = 6371008.8
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
    fun distanceM(a: LatLon, b: LatLon): Double
    /** Initial bearing from a to b, degrees clockwise from north in [0, 360). */
    fun bearingDeg(a: LatLon, b: LatLon): Double
    fun normalizeDeg(deg: Double): Double            // [0, 360)
    /** Signed shortest difference target - current in (-180, 180]. */
    fun angleDiffDeg(current: Double, target: Double): Double
    /** Perpendicular distance from p to segment ab in metres (local equirectangular projection). */
    fun pointToSegmentM(p: LatLon, a: LatLon, b: LatLon): Double
    /** Fraction t in [0,1] of the projection of p onto ab, and the projected point. */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, LatLon>
    fun metersPerPixel(zoom: Double, latDeg: Double): Double   // 156543.03392 * cos(lat) / 2^zoom
}
```

`geo/Simplify.kt`:

```kotlin
object Simplify {
    /** Douglas–Peucker, iterative (no recursion), keeps first and last. */
    fun rdp(points: List<LatLon>, toleranceM: Double): List<LatLon>
    /** Keeps a point only if at least minDistM from the last kept point; keeps first and last. */
    fun radial(points: List<LatLon>, minDistM: Double): List<LatLon>
}
```

`geo/ElevationAccumulator.kt`:

```kotlin
class ElevationAccumulator(private val thresholdM: Double = 5.0, private val alpha: Double = 0.3,
                           private val maxVerticalAccuracyM: Float = 20f) {
    var gainM: Double; var lossM: Double  // private set
    fun add(ele: Double, verticalAccuracyM: Float?)
    fun reset()
}
```

`geo/ZoomRule.kt`:

```kotlin
object ZoomRule {
    /** Linear interpolation over (0,17.5) (10,17.5) (20,17.0) (30,16.5) (45,16.0) (70,15.5); clamps beyond. */
    fun targetZoom(speedKmh: Double): Double
}
class ZoomController(private val hysteresis: Double = 0.25, private val maxRatePerSecond: Double = 0.1,
                     private val gestureBlockMs: Long = 10_000) {
    fun onUserGesture(nowMs: Long)
    /** Returns the zoom to apply now, or null if the camera zoom should stay. */
    fun next(speedKmh: Double, currentZoom: Double, nowMs: Long): Double?
}
```

Tests: `geo/GeoTest.kt`, `geo/SimplifyTest.kt`, `geo/ElevationAccumulatorTest.kt`,
`geo/ZoomRuleTest.kt` (known distances such as Paris–London ≈ 343.5 km; bearing east = 90; RDP of a
straight line collapses to 2 points; elevation noise of ±3 m accumulates nothing, a 20 m climb
accumulates ≈ 20 m; zoom hysteresis and rate limit).

## Module B — `gpx` (pure)

`gpx/GpxWriter.kt`:

```kotlin
object GpxWriter {
    const val CREATOR = "VeloTrack"
    /** Streams GPX 1.1; a new <trkseg> starts whenever point.segment changes. Does not close `out`. */
    fun write(out: java.io.OutputStream, trackName: String, points: Sequence<TrackPoint>)
    fun escapeXml(s: String): String
    fun isoTime(timeMs: Long): String   // 2026-09-07T10:15:30Z
}
```

Format: `<?xml version="1.0" encoding="UTF-8"?>`, `<gpx version="1.1" creator="VeloTrack" xmlns=... xmlns:xsi=... xsi:schemaLocation=...>`,
`<metadata><time>…</time></metadata>`, `<trk><name>…</name><trkseg><trkpt lat="…" lon="…"><ele>…</ele><time>…</time></trkpt>…`.
lat/lon with 6 decimals, ele with 1 decimal, `Locale.ROOT`, `<ele>` omitted when null, `<ele>` before `<time>`.

`gpx/GpxParser.kt`:

```kotlin
data class GpxData(val name: String?, val points: List<LatLon>, val elevations: List<Double?>)
object GpxParser {
    /** Reads rte/rtept and trk/trkseg/trkpt (in document order) with SAX. Throws IllegalArgumentException on malformed input or zero points. */
    fun parse(input: java.io.InputStream): GpxData
}
```

Tests: `gpx/GpxWriterTest.kt` (output contains expected elements in order, two segments produce two
`trkseg`, escaping), `gpx/GpxParserTest.kt` (parses a route file, a track file, and rejects garbage),
plus a round-trip test.

## Module C — `route` (pure)

`route/RouteFollower.kt`:

```kotlin
class RouteFollower(points: List<LatLon>, private val offRouteThresholdM: Double = 50.0,
                    private val offRouteDelayMs: Long = 10_000, private val windowSegments: Int = 50) {
    val totalDistanceM: Double
    val pointCount: Int
    /** Nearest point on the polyline searched around the last match; full rescan if the local best exceeds the threshold. */
    fun update(pos: LatLon, nowMs: Long): RouteProgress
    fun reset()
}
```

`RouteProgress.offRoute` becomes true only after the distance has exceeded the threshold
continuously for `offRouteDelayMs`; it returns to false immediately when back within the threshold.
`distanceRemainingM` = total − cumulative distance to the projected point.
Tests: `route/RouteFollowerTest.kt` (on-route progress decreases, off-route delay, rejoining later
segment triggers rescan).

## Module D — `storage` (Android, no tests required)

`storage/TrackDatabase.kt`: `class TrackDatabase(context: Context) : SQLiteOpenHelper(context, "velotrack.db", null, 1)`
with tables:

```sql
CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, started_at INTEGER NOT NULL,
  finished_at INTEGER, distance_m REAL NOT NULL DEFAULT 0, moving_ms INTEGER NOT NULL DEFAULT 0,
  elevation_gain_m REAL NOT NULL DEFAULT 0, elevation_loss_m REAL NOT NULL DEFAULT 0,
  max_speed_mps REAL NOT NULL DEFAULT 0, state TEXT NOT NULL);           -- state: recording | finished
CREATE TABLE points (track_id INTEGER NOT NULL, seq INTEGER NOT NULL, time_ms INTEGER NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, speed REAL, accuracy REAL NOT NULL, segment INTEGER NOT NULL,
  PRIMARY KEY (track_id, seq));
CREATE TABLE routes (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, distance_m REAL NOT NULL,
  point_count INTEGER NOT NULL, file TEXT NOT NULL, created_at INTEGER NOT NULL);
```

WAL mode enabled in `onConfigure`.

`storage/TrackRepository.kt`:

```kotlin
class TrackRepository(private val db: TrackDatabase) {
    fun createTrack(name: String, startedAtMs: Long): Long
    fun appendPoints(trackId: Long, points: List<TrackPoint>)          // one transaction; seq continues from max(seq)+1
    fun updateStats(trackId: Long, stats: RideStatsSnapshot)
    fun finishTrack(trackId: Long, stats: RideStatsSnapshot, finishedAtMs: Long)
    fun renameTrack(trackId: Long, name: String)
    fun deleteTrack(trackId: Long)
    fun listTracks(): List<TrackSummary>                                // newest first
    fun getTrack(trackId: Long): TrackSummary?
    fun findUnfinished(): TrackSummary?                                 // state == recording
    fun pointCount(trackId: Long): Int
    fun lastSegment(trackId: Long): Int                                 // 0 when no points
    fun loadPoints(trackId: Long): List<TrackPoint>
    fun forEachPoint(trackId: Long, block: (TrackPoint) -> Unit)        // cursor streaming, for GPX export
    fun pointsAsSequence(trackId: Long): Sequence<TrackPoint>           // loads in chunks of 5000
}
```

`storage/MapFileStore.kt`:

```kotlin
class MapFileStore(private val context: Context, private val prefs: Prefs) {
    val mapsDir: File                                                   // context.getExternalFilesDir("maps") ?: filesDir/maps
    fun listMaps(): List<File>                                          // *.mbtiles and *.pmtiles, sorted by name
    fun activeMap(): File?                                              // from prefs.activeMapFile, null if missing on disk
    fun setActive(file: File?)
    fun delete(file: File)
    /** Copies the document to mapsDir with a sanitised ASCII name; reports (copiedBytes, totalBytesOrNull). */
    suspend fun import(uri: Uri, onProgress: (Long, Long?) -> Unit): File
    companion object { fun sanitizeName(displayName: String?): String }   // keeps [A-Za-z0-9._-], forces extension
}
```

`storage/RouteStore.kt`:

```kotlin
class RouteStore(private val context: Context, private val db: TrackDatabase, private val prefs: Prefs) {
    suspend fun importGpx(uri: Uri): RouteSummary                       // parses with GpxParser, stores lat/lon doubles LE in routesDir/<id>.bin
    fun listRoutes(): List<RouteSummary>
    fun loadPoints(routeId: Long): List<LatLon>
    fun delete(routeId: Long)
    fun activeRoute(): RouteSummary?
    fun setActive(routeId: Long?)
}
```

## Module E — `location` + `recording` (Android; PointFilter and RideStats are pure and tested)

`location/LocationExt.kt`: `fun android.location.Location.toGpsFix(): GpsFix` (MSL altitude on API 34+
when `hasMslAltitude()`, else `altitude` when `hasAltitude()`; accuracies only when `has…()`).

`location/GpsSource.kt`:

```kotlin
class GpsSource(context: Context) {
    interface Listener { fun onFix(fix: GpsFix); fun onStatus(status: GpsStatus) }
    val isGpsEnabled: Boolean
    fun hasPermission(): Boolean
    /** GPS_PROVIDER only via LocationManagerCompat + LocationRequestCompat(1000 ms, high accuracy, maxUpdateDelay 0). Registers GnssStatus callback. Safe to call twice. */
    fun start(listener: Listener)
    fun stop()
}
```

`location/HeadingEstimator.kt`:

```kotlin
class HeadingEstimator(private val minSpeedMps: Float = 1.5f, private val maxBearingAccuracyDeg: Float = 45f) {
    val headingDeg: Float?      // last good heading, null until the first one
    fun update(fix: GpsFix): Float?
    fun reset()
}
```

`recording/PointFilter.kt` (pure):

```kotlin
data class FilterConfig(val maxAccuracyM: Float = 50f, val maxSpeedMps: Float = 30f, val minStoreDistanceM: Float = 5f,
    val segmentGapM: Float = 200f, val segmentGapMs: Long = 6 * 60_000, val pauseSpeedMps: Float = 1.0f,
    val resumeSpeedMps: Float = 1.5f, val pauseAfterMs: Long = 10_000, val autoPauseEnabled: Boolean = true)
data class FilterDecision(val accepted: Boolean, val store: Boolean, val newSegment: Boolean,
    val autoPaused: Boolean, val speedMps: Float, val point: TrackPoint?)
class PointFilter(private val config: FilterConfig) {
    var segment: Int   // current segment index, starts at given value
    constructor(config: FilterConfig, startSegment: Int)
    fun offer(fix: GpsFix): FilterDecision
    fun forcePause(paused: Boolean)          // manual pause: nothing stored while true
    fun reset()
}
```

Rules: reject if `accuracyM == null || accuracyM > maxAccuracyM`; reject jumps where distance to the
previous accepted fix > `maxSpeedMps × Δt + acc_prev + acc_new`; speed = `fix.speedMps` or derived;
auto-pause when speed < pauseSpeed for ≥ pauseAfterMs, resume at ≥ resumeSpeed; not stored while
paused; stationary drift (moved < 2×accuracy and speed < pauseSpeed) not stored; store when moved
≥ minStoreDistance from the last stored point (first accepted fix is always stored); newSegment
when gap from last stored point > segmentGapM or > segmentGapMs (segment++).

`recording/RideStats.kt` (pure):

```kotlin
class RideStats(private val elevation: ElevationAccumulator = ElevationAccumulator()) {
    fun start(startedAtMs: Long)
    fun addStored(point: TrackPoint, verticalAccuracyM: Float?)     // distance to previous stored point (skipped across segment change), max speed, elevation
    fun addMovingTime(deltaMs: Long)
    fun snapshot(nowMs: Long): RideStatsSnapshot
    companion object { fun rebuild(points: Sequence<TrackPoint>, startedAtMs: Long, movingMs: Long): RideStats }
}
```

`recording/RecordingService.kt`: `class RecordingService : LifecycleService(), GpsSource.Listener`.
Actions (String constants in companion): `ACTION_START` (extra `EXTRA_RESUME_TRACK_ID` Long, −1 for
new), `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_STOP`. Companion helpers
`start(context, resumeTrackId: Long?)`, `pause(context)`, `resume(context)`, `stop(context)` using
`ContextCompat.startForegroundService` (start) and `context.startService` (others). In
`onStartCommand`: check fine-location permission (else `stopSelf`), create channel
`velotrack.recording` (IMPORTANCE_LOW), `ServiceCompat.startForeground(this, 1, notification,
if (SDK_INT >= 30) FOREGROUND_SERVICE_TYPE_LOCATION else 0)` wrapped in try/catch, acquire a
PARTIAL_WAKE_LOCK (`VeloTrack::Recording`, timeout 12 h), start GPS, create or resume the track
(resume: `lastSegment + 1`, `RideStats.rebuild`). Buffer stored points; flush to
`TrackRepository.appendPoints` every 20 points or 5 s; update `RideSession.state` on every fix;
update the notification at most every 5 s (distance, moving time; actions Pause/Resume and Stop as
PendingIntents to the service). On STOP: flush, `finishTrack`, release wake lock, stop GPS,
`RideSession.serviceRunning = false`, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`.
Storage exceptions are caught, reported in `RideState.error`, and the batch retried on the next flush.
`START_STICKY`. Notification small icon `R.drawable.ic_stat_rec` (provided by `ui`).

`recording/RideController.kt` (helper used by UI): `object RideController { fun startNew(context); fun resumeUnfinished(context, trackId); fun pause(context); fun resume(context); fun stop(context) }` delegating to the service.

Tests: `recording/PointFilterTest.kt`, `recording/RideStatsTest.kt`.

## Module F — `map` (Android; MapUrl and StyleTemplate pure and tested)

`map/MapUrl.kt` (pure): `object MapUrl { fun forFile(absolutePath: String): String }` →
`mbtiles://<path>` when the path ends with `.mbtiles` (path starts with `/`, so the result has three
slashes), `pmtiles://file://<path>` for `.pmtiles`, else `IllegalArgumentException`. Path segments
are percent-encoded for spaces and non-ASCII.

`map/StyleTemplate.kt` (pure): `object StyleTemplate { const val PLACEHOLDER = "{MAP_URL}"; fun render(template: String, mapUrl: String?): String }` —
replaces the placeholder; when `mapUrl` is null, removes the `"url"` line's value by replacing with
an empty `tiles` array so the style still loads on a black background (implement by parsing the
JSON with `org.json` — available on Android but not on the JVM; therefore implement string-based:
replace `"url": "{MAP_URL}"` with `"tiles": []`).

`map/MapController.kt`:

```kotlin
class MapController(private val context: Context, private val mapView: MapView, private val prefs: Prefs) {
    var onUserGesture: (() -> Unit)? = null
    var cameraMode: CameraMode          // FOLLOW_3D | FOLLOW_2D | FREE; setting it applies immediately
    fun onMapReady(map: MapLibreMap)    // stores map, disables ui widgets, sets maxFps 30, prefetch off, adds gesture listeners that set FREE + onUserGesture
    /** Loads assets/style.json with the map URL substituted; adds route/track/puck sources and layers; calls back with success or the error message. */
    fun loadStyle(mapFile: File?, onDone: (String?) -> Unit)
    fun updatePosition(fix: GpsFix, headingDeg: Float?, nowMs: Long)   // moves puck, eases camera per mode, zoom via ZoomController
    fun recenter()                                                      // returns to the last follow mode
    fun setTrackHistory(points: List<LatLon>)                           // full replace of history source (simplified with Simplify.rdp 2 m)
    fun appendTrackPoint(p: LatLon)                                     // live source; every 500 points moves them into history
    fun clearTrack()
    fun setRoute(points: List<LatLon>?)
    fun onDestroy()
}
```

Style loading: read `assets/style.json`, `StyleTemplate.render(template, mapFile?.let { MapUrl.forFile(it.absolutePath) })`,
`map.setStyle(Style.Builder().fromJson(json)) { style -> … }`. Errors via
`mapView.addOnDidFailLoadingMapListener`. Puck image: draw a 48 dp arrow into a Bitmap with
`Canvas`/`Path` (no drawable dependency), `style.addImage("puck", bitmap)`, `SymbolLayer("puck-layer","puck")`
with `iconImage("puck")`, `iconRotationAlignment(ICON_ROTATION_ALIGNMENT_MAP)`, `iconAllowOverlap(true)`,
`iconIgnorePlacement(true)`; rotation via `iconRotate(headingDeg)` on the layer. Camera:
`CameraPosition.Builder().target(...).zoom(...).tilt(prefs.pitchDeg).bearing(heading)`, padding
(0, 0.55·height, 0, 0) set once for FOLLOW_3D and cleared (0,0,0,0) for FOLLOW_2D;
`map.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 1000, false)`.

Tests: `map/MapUrlTest.kt`, `map/StyleTemplateTest.kt`.

## Module G — `ui` (Android)

Owns `AndroidManifest.xml`, all `res/` (layouts, strings, themes, drawables, `xml/file_paths.xml`,
`xml/preferences.xml`), `VeloTrackApp : Application` (calls `MapLibre.getInstance(this)` then
`MapLibre.setConnected(false)`), and the activities:

- `ui/MainActivity.kt`: full-screen `MapView` (created programmatically with `MapLibreMapOptions`
  `attributionEnabled(false).logoEnabled(false).compassEnabled(false).setPrefetchesTiles(false)`) inside
  a `FrameLayout` with the HUD overlay (`layout/activity_main.xml` with a `FrameLayout` id `mapContainer`).
  HUD: speed (large), unit label, distance, moving time, avg speed, elevation gain, clock, GPS
  (`sats used/total`, accuracy), battery %. Buttons: record/pause/stop FAB group, recenter, 2D/3D
  toggle, overflow menu (Tracks, Routes → import GPX / clear, Map files, Settings, About). Permission
  flow with `RequestMultiplePermissions` (fine + coarse, POST_NOTIFICATIONS on 33+). Collects
  `RideSession.state` with `repeatOnLifecycle(STARTED)`. When not recording, runs its own
  `GpsSource` for the puck. Applies `ScreenMode` (FLAG_KEEP_SCREEN_ON / `screenBrightness`).
  Recovery dialog for `TrackRepository.findUnfinished()` when the service is not running. Battery
  Saver warning at record start. Banner when GPS is off; overlay when no map file is active.
  Route following: when `prefs.activeRouteId` is set, loads points, uses `RouteFollower`, shows
  distance remaining and vibrates on off-route transitions (`VibratorManager` on 31+).
- `ui/TracksActivity.kt`: RecyclerView-free simple `ListView` of `TrackSummary`; tap → view on map
  (`MainActivity` extra `EXTRA_VIEW_TRACK_ID`); long-press → rename / export (Share via FileProvider,
  Save-as via `CreateDocument("application/gpx+xml")`) / delete.
- `ui/MapFilesActivity.kt`: list of map files, pick active, import with progress dialog, delete.
- `ui/SettingsActivity.kt` + `ui/SettingsFragment : PreferenceFragmentCompat` from `preferences.xml`.
- `ui/AboutDialog.kt`: version, licences summary, OSM attribution text with URL.
- `ui/Format.kt`: `object Format { fun speed(mps: Double, units: Units): String; fun distance(m: Double, units: Units): String; fun duration(ms: Long): String; fun elevation(m: Double, units: Units): String }`.

Manifest: permissions `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `VIBRATE`; removals with
`tools:node="remove"` for `INTERNET` and `ACCESS_WIFI_STATE`; `<uses-feature android.hardware.location.gps required=true>`;
application `android:name=".VeloTrackApp"`, theme `Theme.VeloTrack` (Material3 dark, black window
background, no action bar on main); service `.recording.RecordingService` with
`foregroundServiceType="location"`, `exported=false`; FileProvider `${applicationId}.fileprovider`
with `external-files-path name="tracks" path="tracks/"`. `MainActivity` `launchMode="singleTop"`,
`screenOrientation` unrestricted, `configChanges="orientation|screenSize|keyboardHidden"`.

Drawables: `ic_stat_rec` (vector, white dot), `ic_launcher` adaptive icon with a simple vector
foreground (bicycle-ish arrow) and black background, plus `ic_launcher_round`.

## Module H — tools, CI, docs

- `velotrack/tools/package.json` (`"type": "module"`, dependency `@protomaps/basemaps` `5.7.2`).
- `velotrack/tools/gen-style.mjs`: builds the style with `layers("protomaps", namedFlavor("dark"), { lang })`,
  `glyphs: "asset://fonts/{fontstack}/{range}.pbf"`, `sprite: "asset://sprites/v4/dark"`, source
  `{ type: "vector", url: "{MAP_URL}", attribution: "© OpenStreetMap contributors" }`; then patches:
  `background` layer paint `background-color` → `#000000`, any layer with id `earth` → fill
  `#000000`, removes layers whose id starts with `pois`; writes `app/src/main/assets/style.json`.
  `LANG` env var, default `en`.
- `velotrack/tools/build-assets.sh`: `set -euo pipefail`; `npm ci`/`npm install` in `tools/`;
  runs `gen-style.mjs`; downloads sprites `v4/dark{,@2x}.{json,png}` and all 256 glyph ranges for
  `Noto Sans Regular`, `Noto Sans Medium`, `Noto Sans Italic` plus `fonts/OFL.txt` from
  `https://raw.githubusercontent.com/protomaps/basemaps-assets/main/` using `curl` with
  parallelism (`xargs -P 16`), skipping files that already exist; verifies counts.
- `.github/workflows/velotrack-android.yml` and `.github/workflows/velotrack-map-extract.yml` as
  specified in the design (sections 7.1, 7.2). Map extract uses `pip install pmtiles==3.7.0` and
  `pmtiles-convert <name>.pmtiles <name>.mbtiles`.
- `velotrack/README.md`, `velotrack/THIRD_PARTY.md`.

## Order of work

1. (done) Scaffold, shared model, prefs, session.
2. Modules A–H in parallel, each with its own files only.
3. Review pass for compile errors and contract mismatches; fixes.
4. Push; CI build; fix until green; verify the release APK exists.
