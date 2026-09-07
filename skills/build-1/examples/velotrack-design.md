# VeloTrack — offline, low-power bike GPS map and tracker

Date: 2026-09-07. Status: implemented autonomously from a voice brief; every decision below is a
proposal the user can overturn. Version facts were verified against primary sources on this date.

## 1. Purpose

A personal Android app for cycling that:

- shows the rider's position on an **offline vector map**, viewed from a tilted **3D perspective**
  that turns with the direction of travel ("course-up");
- **records rides** of any length (7 km to 1000+ km) to GPX with live distance, speed, time and climb;
- optionally follows a **pre-planned GPX route** and warns when the rider leaves it;
- uses **only the GPS receiver**: the app has no internet permission at all, so it can never use
  Wi-Fi or mobile data;
- is **as cheap on battery as possible** so a 7000 mAh phone lasts a multi-day ride;
- is built and published entirely on GitHub: every push builds an installable APK.

## 2. Assumptions made without the user

| Assumption | Why | If wrong |
|---|---|---|
| Phone is Android 8.0 (API 26) or newer | 7000 mAh phones are all Android; no iPhone has that capacity | iOS needs a different app and a developer account |
| No turn-by-turn routing in v1 | "direction app" read as heading + perspective view, not voice navigation. Offline routing engines are a separate large project | Add a routing engine (e.g. Valhalla) as a later sub-project |
| Map data is prepared at home, once per region | Offline maps must be downloaded before a ride; the user asked for no data use while riding | None; Organic Maps and OsmAnd work the same way |
| Labels in English with local fallback | Region unknown | Change one variable in the asset build script |
| Repo stays a private monorepo, app in `velotrack/` | Matches the repo layout agreed earlier | Split into its own repo later |

## 3. Requirements

Functional:

1. Map screen with position puck, offline base map, three camera modes: Follow 3D (default),
   Follow 2D north-up, Free (after the user pans; a Recenter button returns).
2. Camera in Follow 3D: pitch about 55°, bearing = direction of travel, zoom chosen from speed,
   puck placed in the lower third so the road ahead is visible.
3. Recording: Start / Pause / Stop. Survives screen-off and app-switching via a foreground service.
   Survives process death: an unfinished ride is resumed or finished on next launch.
4. Live HUD: speed, distance, moving time, average moving speed, elevation gain, clock, GPS quality
   (satellites used / seen, accuracy), battery percentage.
5. Tracks list: name, date, distance, duration; actions: view on map, export GPX (save-as or
   share), delete.
6. Route: import a GPX route/track file, show it in a contrasting colour, show distance remaining
   along the route and vibrate when off-route beyond a threshold.
7. Map files: import a `.mbtiles` (or `.pmtiles`) region file from device storage into the app;
   list and switch between imported regions; delete.
8. Settings: accuracy cut-off, auto-pause, screen behaviour (keep on / dim level), units (km or mi),
   3D pitch, off-route distance.

Non-functional:

- No `INTERNET` permission (the MapLibre library's own request for it is stripped in the manifest).
  No Google Play Services. Only open-source libraries.
- Pure black UI and near-black map theme for OLED screens.
- The map redraws only when the camera or data changes (MapLibre "when dirty" mode, 30 fps cap).
- All ride data is stored locally in SQLite; GPX is generated on demand, never the primary store.
- Unit tests for all pure logic (geometry, filtering, statistics, GPX read/write, route projection).
- Builds green on GitHub Actions with no local toolchain.

Out of scope for v1: turn-by-turn routing, elevation profile charts, sensors (heart rate, cadence,
barometer, compass), Wear OS, cloud sync, map downloads inside the app.

## 4. Approaches considered

1. **Fork Organic Maps or OsmAnd.** Most features exist already, but both are huge C++/Java
   codebases; adding a 3D course-up tracking mode and stripping networking would take longer than
   writing a focused app, and the result would be hard to maintain from GitHub Actions alone.
2. **Web app (PWA) with MapLibre GL JS.** Fast to build, but browsers cannot record GPS with the
   screen off, offline map storage of hundreds of megabytes is fragile, and battery use is worse.
3. **Small native Kotlin app on MapLibre Native (chosen).** MapLibre Native is the open-source
   successor of the Mapbox SDK: offline vector tiles from a local file, camera pitch and bearing,
   GPU rendering, no account. Kotlin with plain Android Views keeps the build simple enough to
   trust a CI-only pipeline. Protomaps supplies free OpenStreetMap vector tiles, so "download a
   region" is one command in a GitHub workflow.

## 5. Verified platform facts the design relies on

| Fact | Source |
|---|---|
| Android Gradle Plugin 9.4.0 is the latest stable; it needs Gradle ≥ 9.6 and JDK 17, and compiles Kotlin itself (bundled Kotlin 2.2.10). The `org.jetbrains.kotlin.android` plugin must **not** be applied. Unit tests exist only for the debug build type. | developer.android.com/build/releases |
| Gradle 9.7.1 is current and preinstalled on `ubuntu-latest`; `gradle/actions/setup-gradle@v6` with `gradle-version` needs no wrapper jar in the repo. | services.gradle.org, gradle/actions docs |
| `ubuntu-latest` (Ubuntu 24.04) has the Android SDK at `/usr/local/lib/android/sdk` with platforms 34–37 and build-tools 36.0.0, licences accepted; AGP auto-downloads anything missing. | actions/runner-images |
| MapLibre Native Android 13.6.0: default artifact is Vulkan-only (manifest requires Vulkan hardware). `org.maplibre.gl:android-sdk-opengl:13.6.0` runs on any device; minSdk 23; namespace `org.maplibre.android`. | Maven Central, MapLibre changelog |
| MapLibre reads local tiles from `mbtiles:///abs/path.mbtiles` and `pmtiles://file:///abs/path.pmtiles` via the source `url` field; neither can read from APK assets. Style, glyphs and sprites can be `asset://`. | maplibre-native docs and source |
| Open MapLibre bugs #4462 (PMTiles with gzip internal compression load no tiles), #4459 (Android crash in PMTiles source), #4421 (large PMTiles archives). Protomaps extracts use gzip internal compression. | github.com/maplibre/maplibre-native/issues |
| Protomaps daily planet builds at `https://build.protomaps.com/<YYYYMMDD>.pmtiles` (~138 GB, z0–15, ODbL, free to extract by bbox with the `pmtiles` CLI v1.31.2). The CLI cannot convert PMTiles to MBTiles; the official Python `pmtiles` package (BSD-3) provides that conversion. | docs.protomaps.com, PyPI |
| `@protomaps/basemaps` 5.7.2 generates the style: `layers("protomaps", namedFlavor("dark"), {lang})`; fonts Noto Sans Regular/Medium/Italic (OFL) and sprites `v4/dark` come from `protomaps/basemaps-assets`. Without `lang` there are no labels. | npm, github.com/protomaps |
| Foreground location service on API 34+ needs `FOREGROUND_SERVICE_LOCATION`, `foregroundServiceType="location"`, and fine location granted **before** `startForeground`; `ACCESS_BACKGROUND_LOCATION` is not needed. No time cap applies to the location type on API 35/36. | developer.android.com |
| GPS works in airplane mode (GNSS is not an airplane-mode radio). Without internet there is no assisted GPS, so a cold first fix can take 30 s to minutes. Battery Saver modes 1 and 2 stop GPS when the screen is off regardless of a foreground service. | AOSP Settings.java, Android power docs |

## 6. Architecture

Single Gradle module `velotrack/app`, package `com.fablab503.velotrack`. Packages:

| Package | Responsibility | Depends on |
|---|---|---|
| `ui` | `MainActivity` (map + HUD), `TracksActivity`, `SettingsActivity`, `MapFilesActivity`, permission flow, dialogs | all others via public APIs |
| `map` | `MapController`: loads the local style, manages camera modes, draws puck, track and route layers | MapLibre, `geo` |
| `location` | `GpsSource`: GPS-only updates via `LocationManagerCompat`, GNSS satellite status; `HeadingEstimator` | Android location APIs |
| `recording` | `RecordingService` (foreground), `PointFilter`, `RideStats`, `RideSession` state flow | `location`, `storage`, `geo` |
| `storage` | `TrackDatabase` (SQLite via `SQLiteOpenHelper`), `TrackRepository`, `MapFileStore`, `RouteStore` | Android storage |
| `gpx` | `GpxWriter` (streaming), `GpxParser` (`XmlPullParser`, streaming) | none |
| `route` | `RouteFollower`: nearest point on polyline, distance remaining, off-route detection | `geo` |
| `geo` | Pure functions: haversine, bearing, radial and Douglas-Peucker simplification, elevation hysteresis, speed-to-zoom | none |
| `settings` | `Prefs`: typed access to SharedPreferences | none |

Data flow while riding:

```
LocationManager (GPS only) --Location--> GpsSource --> RecordingService
   RecordingService: PointFilter -> RideStats -> TrackRepository (SQLite, batched)
                     -> RideSession.state (StateFlow<RideState>)
MainActivity collects RideSession.state -> HUD text, MapController.update(position, heading, trackDelta)
MapController -> MapLibre camera ease + GeoJSON source updates
```

`RideSession` is an application-scoped singleton holding a `StateFlow<RideState>`; the service
writes it, the UI reads it. No binder interface is needed because everything runs in one process.
When no recording is running, `MainActivity` still subscribes to `GpsSource` directly so the map
follows the rider without recording.

### 6.1 Map

- Engine: `org.maplibre.gl:android-sdk-opengl:13.6.0`. `MapLibre.getInstance(context)` needs no
  key and makes no network call; `MapLibre.setConnected(false)` is called right after so the
  library never consults the connectivity manager. The manifest removes the library's `INTERNET`
  and `ACCESS_WIFI_STATE` permissions with `tools:node="remove"`; the CI job greps the merged
  manifest and fails the build if `INTERNET` survives.
- Style: Protomaps `dark` flavor generated at build time by `tools/gen-style.mjs`, then patched
  for OLED: background and earth fills set to pure black, POI layers removed (fewer symbol layers,
  less label placement work). Glyphs `asset://fonts/{fontstack}/{range}.pbf`, sprites
  `asset://sprites/v4/dark`. The style's single vector source has `"url": "{MAP_URL}"`, replaced at
  runtime with `mbtiles:///abs/path` or `pmtiles://file:///abs/path` depending on the file
  extension, and loaded with `Style.Builder.fromJson`.
- Assets: all 256 glyph ranges for Noto Sans Regular, Medium and Italic (~11 MB) so any Latin,
  Greek or Cyrillic region renders; Devanagari is omitted. Sprites `dark` and `dark@2x`.
- Layers added on top of the base style: `route-line` (orange), `track-history` (blue, simplified,
  rebuilt every 500 points), `track-live` (blue, last 500 points, rebuilt on each fix), `puck`
  (symbol layer with a rotatable arrow bitmap, `icon-rotation-alignment: map`).
- Camera rules:
  - Follow 3D: tilt = setting (default 55°, MapLibre maximum 60°), bearing = heading, zoom by
    speed with linear interpolation between (0 km/h, 17.5), (10, 17.5), (20, 17.0), (30, 16.5),
    (45, 16.0), (70, 15.5); applied only when the target differs from the current zoom by more than
    0.25 and at most 0.1 zoom per second; a persistent top padding of 55 % of the view height puts
    the puck in the lower third. `easeCamera` linear over 1000 ms on each fix.
  - Follow 2D: tilt 0, bearing 0, zoom 15.5, no padding.
  - Free: any user gesture switches to Free and blocks auto-zoom; Recenter returns to the previous
    follow mode.
- Rendering: default when-dirty mode, `setMaximumFps(30)`, tile prefetch disabled, no compass,
  logo or attribution widgets (attribution is shown in the About dialog and on first launch).

### 6.2 GPS and heading

- `GpsSource` uses `LocationManagerCompat.requestLocationUpdates` with `GPS_PROVIDER`,
  `LocationRequestCompat` quality high-accuracy, interval 1000 ms, max update delay 0. Never the
  fused or network provider, so no Wi-Fi or cell scanning is ever triggered. The GPS request is
  never paused during auto-pause: re-acquisition without assisted GPS is slow.
- `GnssStatus.Callback` reports satellites used-in-fix and total for the HUD.
- Heading: GPS course when speed > 1.5 m/s, the fix has a bearing, and bearing accuracy (when
  reported) is < 45°; the last good heading is held otherwise so the view does not spin at a stop.
  No magnetometer in v1.
- Battery Saver: when recording starts, `PowerManager.locationPowerSaveMode` is checked; in the
  two modes that cut GPS with the screen off a warning dialog explains how to exempt the app.

### 6.3 Recording and statistics

- `RecordingService` extends `LifecycleService`, is started from the visible `MainActivity` only
  after fine-location permission is granted, and calls `ServiceCompat.startForeground` with
  `FOREGROUND_SERVICE_TYPE_LOCATION` (type passed on API 30+, 0 below) inside `onStartCommand`.
  It holds a partial wake lock while recording (undocumented but standard insurance against OEMs
  that stop GPS delivery with the screen off) and shows a low-importance notification with live
  distance and time plus Pause and Stop actions.
- `PointFilter` (pure): drop fixes without accuracy or with accuracy > cut-off (default 50 m);
  drop jumps where distance > 30 m/s × Δt + both accuracy radii; drop stationary drift (moved
  < 2 × accuracy while slower than 1 m/s); store a point when moved ≥ 5 m from the last stored
  point; start a new segment after a gap > 200 m or > 6 min.
- Auto-pause: speed < 1.0 m/s sustained 10 s pauses moving time and point storage; resumes at
  ≥ 1.5 m/s. Manual pause stops everything except the GPS request.
- `RideStats` (pure, incremental): distance (haversine between stored points), moving time,
  average moving speed, max speed, elevation gain and loss with exponential smoothing (α = 0.3) and
  5 m hysteresis, ignoring fixes whose vertical accuracy > 20 m. Altitude uses MSL on API 34+ when
  available, else the WGS84 value (documented in the README).
- Storage: SQLite tables `tracks(id, name, started_at, finished_at, distance_m, moving_ms,
  elevation_gain_m, elevation_loss_m, max_speed_mps, state)` and `points(track_id, seq, time_ms,
  lat, lon, ele, speed, accuracy, segment)`. Points are written in transactions of up to 20 rows
  or every 5 s, whichever comes first.
- Recovery: on launch, a track in state `recording` with no live service is offered in a dialog:
  Resume (restarts the service, continues the same track, new segment) or Finish.
- Export: `GpxWriter` streams GPX 1.1 (`trk/trkseg/trkpt` with `ele` before `time`, `Locale.ROOT`
  number formatting, ISO-8601 UTC) from the database cursor to a file in the app's external files
  directory, then offers Share (FileProvider) or Save-as (`ACTION_CREATE_DOCUMENT`). Each stored
  segment becomes a `trkseg`.

### 6.4 Routes

- Import via `ActivityResultContracts.OpenDocument`. `GpxParser` reads `rte/rtept` and
  `trk/trkseg/trkpt` streaming; the polyline is stored as a compact binary file (little-endian
  doubles) in app storage plus a row in `routes(id, name, distance_m, file, point_count)`.
- `RouteFollower` (pure): projects the current position onto the polyline (nearest segment within
  a search window around the last match so 10 000-point routes stay cheap; full rescan when the
  window match is worse than the threshold), returns distance to route, distance remaining along
  the route, and an off-route flag when distance > threshold (default 50 m) for > 10 s. Off-route
  triggers one vibration pattern and a HUD warning; back on route triggers a short buzz.

### 6.5 Map files

- Region files are produced by the `velotrack-map-extract` workflow (see 7.2) and downloaded to
  the phone at home. In the app, "Load map file" opens the document picker; the file is copied into
  `getExternalFilesDir("maps")` on an IO dispatcher with a progress bar (files are typically
  100 MB to 2 GB; SAF has no size limit).
- `MapFileStore` lists imported files, holds the active one in preferences, and deletes on request.
  File names are kept ASCII (sanitised on import) because the `mbtiles://` URL is percent-decoded.
- First launch with no map shows an explanatory screen with the exact steps and the workflow URL;
  the puck and HUD work on a black background regardless.

### 6.6 Battery policy (summary of every measure)

1. No `INTERNET` permission; radios are never used by the app.
2. GPS receiver only; no fused provider, no Wi-Fi/cell scanning, no magnetometer, no barometer.
3. Near-black map theme and black UI; no idle animations; 30 fps cap; when-dirty rendering.
4. Screen setting: keep on while the map is visible (default), dim to a user-chosen level via the
   window's `screenBrightness`, or leave to the system. Recording continues with the screen off.
5. Camera updates are eased at the GPS interval only; nothing runs on a timer faster than 1 Hz and
   database writes are coalesced.
6. Track rendering is split into a rarely rebuilt history source and a small live source.
7. Battery Saver location-mode warning at recording start.

### 6.7 Error handling

- Permission denied: the map still shows; recording controls are disabled with an explanation and
  a button to the system settings page.
- GPS off: banner "Location is turned off" with a shortcut to settings.
- No map file: informational overlay; the puck and HUD still work.
- Corrupt or wrong map file: style-load failure is caught; the file is marked bad and the user is
  told.
- Storage write failure while recording: the service keeps running, shows an error in the
  notification, and retries the batch; the ride is never silently dropped.
- Service killed by the OS: recovery dialog on next launch (6.3).

## 7. Build, CI and release

### 7.1 App build (`.github/workflows/velotrack-android.yml`)

Triggers: push to `main` touching `velotrack/**` or the workflow file, and manual dispatch. Steps:
`actions/checkout@v7` → `actions/setup-java@v6` (Temurin 17) → `gradle/actions/setup-gradle@v6`
(`gradle-version: 9.7.1`) → `actions/setup-node@v5` (Node 22) → `tools/build-assets.sh`
(installs `@protomaps/basemaps@5.7.2`, generates and patches the style, fetches fonts and sprites
from `basemaps-assets`) → `gradle testDebugUnitTest assembleRelease` → assert the merged manifest
has no `INTERNET` permission → upload APK artifact → publish to GitHub Releases: a rolling
pre-release `velotrack-latest` (always the newest APK at a stable URL) and an immutable
`velotrack-v<run_number>`.

Signing: a PKCS12 keystore generated with OpenSSL is committed at `velotrack/keystore/velotrack.p12`
(alias `velotrack`, password kept out of the repository as a GitHub secret, key password equal to store password as PKCS12
requires). This is acceptable for a private, personal repository and keeps updates installable over
each other. The build reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD` and `KEY_ALIAS` from the
environment with those defaults, so the key can move into GitHub Secrets later with no code change.
`versionCode` = GitHub run number (1 locally); `versionName` = `1.0.<run>`.

Gradle: AGP 9.4.0 via version catalog, no Kotlin plugin, compileSdk 36, targetSdk 36, minSdk 26,
JDK 17 source/target, ViewBinding on, R8 with `proguard-android-optimize.txt` plus keep rules for
MapLibre. Dependencies: core-ktx 1.19.0, appcompat 1.8.0, material 1.14.0, activity-ktx 1.13.0,
lifecycle-service and lifecycle-runtime-ktx 2.11.0, preference-ktx 1.2.1, constraintlayout 2.2.2,
kotlinx-coroutines-android 1.11.0, MapLibre OpenGL 13.6.0, JUnit 4.13.2 for tests.

### 7.2 Map extract (`.github/workflows/velotrack-map-extract.yml`)

Manual dispatch with inputs `name` (e.g. `portland`), `bbox` (`minLon,minLat,maxLon,maxLat`),
`maxzoom` (default 15) and optional `build` (planet build date; default: newest key from
`https://build-metadata.protomaps.dev/builds.json`). Installs `pmtiles` v1.31.2 from the release
tarball, runs a `--dry-run` to log the expected size, extracts the bbox, converts to MBTiles with
the Python `pmtiles` package, verifies both files, and uploads `<name>.mbtiles` (primary) and
`<name>.pmtiles` (secondary) to a release tagged `map-<name>` with `--clobber`. Release assets may
be up to 2 GB; the workflow fails with a clear message if the extract exceeds that and suggests a
smaller bbox or `maxzoom` 14.

### 7.3 Repository layout

```
velotrack/
  README.md                 install, get-a-map, ride, battery tips, privacy, licences
  THIRD_PARTY.md            licences of MapLibre, Protomaps, Noto fonts, OSM data
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
  app/                      Android module (src/main, src/test)
  keystore/velotrack.p12
  tools/build-assets.sh     style + fonts + sprites into app assets
  tools/gen-style.mjs       Node script using @protomaps/basemaps
  tools/package.json        pins @protomaps/basemaps 5.7.2
.github/workflows/velotrack-android.yml
.github/workflows/velotrack-map-extract.yml
docs/superpowers/specs/2026-09-07-velotrack-design.md   (this file)
docs/superpowers/plans/2026-09-07-velotrack-plan.md     (implementation plan)
```

## 8. Testing

- JVM unit tests (JUnit 4) for `geo`, `PointFilter`, `RideStats`, `RouteFollower`, `GpxWriter`,
  `GpxParser`, the speed-to-zoom rule and the map URL builder. They run in CI before the APK is
  assembled.
- No emulator tests. Manual acceptance on the phone: install APK; import a map; walk 200 m and
  confirm the puck, heading and distance; record with the screen off for 10 minutes and confirm the
  point count grows; export GPX and open it in another tool.

## 9. Licences and attribution

App code: MIT (repo licence). Map data: © OpenStreetMap contributors, ODbL; attribution shown in
the About dialog and on the first-launch screen with the URL openstreetmap.org/copyright as text.
Protomaps basemap style: BSD-3; sprites derived from MIT-licensed tangrams/icons; Noto Sans fonts:
SIL Open Font License 1.1; MapLibre Native: BSD-2. All listed in `velotrack/THIRD_PARTY.md`.
