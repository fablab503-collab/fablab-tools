export const meta = {
  name: 'velotrack-research',
  description: 'Verify current MapLibre Android, Android toolchain, Protomaps offline-map, and GPS battery facts before designing the bike app',
  phases: [{ title: 'Research', detail: 'four independent fact-finding agents with web access' }],
}

const SCHEMA = {
  type: 'object',
  properties: {
    facts: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          claim: { type: 'string' },
          source_url: { type: 'string' },
          confidence: { type: 'string', enum: ['verified', 'likely', 'unverified'] },
        },
        required: ['claim', 'source_url', 'confidence'],
      },
    },
    gotchas: { type: 'array', items: { type: 'string' } },
    code_snippets: {
      type: 'array',
      items: {
        type: 'object',
        properties: { purpose: { type: 'string' }, code: { type: 'string' } },
        required: ['purpose', 'code'],
      },
    },
    recommendation: { type: 'string' },
  },
  required: ['facts', 'gotchas', 'code_snippets', 'recommendation'],
}

const COMMON = `You are a research agent. Today is 2026-09-07. Use ToolSearch to load WebSearch and WebFetch, then verify every claim against primary sources (official docs, GitHub repos, Maven Central, release notes). Mark each fact verified/likely/unverified honestly. Do NOT rely on memory for version numbers: fetch them. Return raw structured data only; be precise and include exact version strings, Maven coordinates, URLs, and code where relevant.

Context: we are building an open-source Android app in Kotlin (no Google Play Services, NO internet permission at all) for a cyclist: offline vector map with a tilted 3D-perspective camera that follows the rider's GPS heading, low battery use, track recording to GPX for rides up to 1000 km, built entirely on GitHub Actions (no local Android SDK) into a sideloadable APK.`

phase('Research')
const topics = [
  {
    key: 'maplibre',
    prompt: `${COMMON}

TOPIC: MapLibre Native for Android (open-source map SDK), as of September 2026.
Find and verify:
1. Latest stable release version and exact Maven coordinates (org.maplibre.gl:android-sdk vs android-sdk-vulkan, any other required artifacts, repository needed). Minimum SDK required.
2. Loading LOCAL tile files without network: does MapLibre Native Android support "pmtiles://" URLs for local PMTiles files? Since which version? Exact URL syntax for a local absolute file path on Android (e.g. pmtiles:///storage/... or pmtiles://file:///...). Same for "mbtiles://" local files. Check MapLibre Native changelog/release notes and docs at maplibre.org/maplibre-native and the GitHub repo maplibre/maplibre-native (search issues/PRs for "PMTiles" and "mbtiles"). How does a style JSON reference such a source (a "url" pointing at pmtiles:// for a vector source)?
3. Using "asset://" URLs on Android for style JSON, glyphs (fonts PBF) and sprites so the app works fully offline. Exact format of the glyphs template ("asset://fonts/{fontstack}/{range}.pbf" etc).
4. Initialization: MapLibre.getInstance(context) — does it require an API key or network? MapView lifecycle methods that must be forwarded. How to disable any telemetry or network fetch.
5. Camera control: CameraPosition builder with target, zoom, tilt (pitch max value), bearing, padding; MapLibreMap.easeCamera/animateCamera/moveCamera signatures; how to offset the focal point so the puck sits in the lower third (padding or CameraUpdateFactory with padding).
6. Drawing the ride track and a puck: GeoJsonSource(String id, ...) + setGeoJson(FeatureCollection) for updates, LineLayer with PropertyFactory.lineColor/lineWidth, SymbolLayer with an image for the puck and icon-rotate; adding images to Style (style.addImage). Also the built-in LocationComponent: does it work with a plain android.location.Location fed manually (forceLocationUpdate) and does it support a bearing-based "puck" in GPS/COMPASS render mode? Which is simpler for a custom puck?
7. Performance/battery knobs: does MapView render continuously or only on changes; setRenderMode or similar; any setting to reduce framerate; texture mode; MapLibreMapOptions relevant flags.
8. Package names (org.maplibre.android.* vs com.mapbox.*) for the current version — list the exact imports for MapView, MapLibre, MapLibreMap, Style, CameraPosition, CameraUpdateFactory, GeoJsonSource, LineLayer, SymbolLayer, PropertyFactory, LatLng, OnMapReadyCallback.
Provide minimal correct Kotlin snippets for: initializing MapLibre and MapView in an Activity with a local style asset, adding a GeoJsonSource+LineLayer, and easing camera to a tilted position.`,
  },
  {
    key: 'android-toolchain',
    prompt: `${COMMON}

TOPIC: Android build toolchain and platform APIs, as of September 2026. This build runs ONLY on GitHub Actions (ubuntu-latest) and must succeed first try, so exact compatible versions matter.
Find and verify:
1. Latest STABLE Android Gradle Plugin (AGP) version, the Gradle version it requires, and the JDK it requires. Latest stable Kotlin version compatible with that AGP (and whether the kotlin-android plugin id is "org.jetbrains.kotlin.android"). Latest stable compileSdk/targetSdk API level (Android 16 = API 36? confirm) and whether AGP supports it.
2. GitHub Actions: what Android SDK components are preinstalled on ubuntu-latest runners (check actions/runner-images README for Ubuntu 24.04), whether AGP auto-downloads missing platform/build-tools (android.builder.sdkDownload) and license acceptance; recommended action versions: actions/checkout, actions/setup-java (temurin, which JDK), gradle/actions/setup-gradle (does it install a Gradle version if the repo has no wrapper jar? the "gradle-version" input), and how to create/update a GitHub Release with an APK asset using the preinstalled gh CLI with GITHUB_TOKEN (permissions: contents: write). Also android-actions/setup-android if useful.
3. Gradle project setup with Kotlin DSL and version catalogs (settings.gradle.kts pluginManagement + dependencyResolutionManagement, gradle/libs.versions.toml) — a minimal correct template for a single-module Android app. Whether ViewBinding requires any plugin (buildFeatures.viewBinding). Whether the Gradle wrapper jar is needed if we call gradle from setup-gradle.
4. Signing: can a PKCS12 (.p12) keystore generated with the openssl CLI be used in signingConfigs (storeType = "pkcs12")? Exact signingConfigs Kotlin DSL syntax reading store path/password from env vars with a fallback.
5. Current stable AndroidX/Material versions: androidx.appcompat, androidx.core:core-ktx, com.google.android.material, androidx.activity:activity-ktx, androidx.lifecycle (lifecycle-service, lifecycle-runtime-ktx), androidx.preference:preference-ktx, androidx.constraintlayout, kotlinx-coroutines-android.
6. Foreground service for GPS recording on API 34+/35/36: required manifest permissions (FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION), android:foregroundServiceType="location", ServiceCompat.startForeground with FOREGROUND_SERVICE_TYPE_LOCATION, the rule that ACCESS_FINE_LOCATION must be granted BEFORE starting a location FGS (SecurityException otherwise), POST_NOTIFICATIONS runtime permission on API 33+, notification channel requirements, whether ACCESS_BACKGROUND_LOCATION is needed for a foreground service (it is not; confirm), and any new API 35/36 restrictions (e.g., FGS start-from-background limits, 6-hour dataSync limits—does it apply to location type?).
7. GPS-only location on Android without Play Services: android.location.LocationManager.requestLocationUpdates with LocationManager.GPS_PROVIDER and the API 31+ LocationRequest.Builder (setQuality QUALITY_HIGH_ACCURACY, setMinUpdateIntervalMillis, setMinUpdateDistanceMeters), the older overload for API 26-30, LocationListenerCompat, GnssStatus.Callback registration (registerGnssStatusCallback) for satellite counts, whether GPS works in airplane mode, Location.hasBearing/hasSpeed/getAccuracy/getVerticalAccuracyMeters semantics. Also: is a PARTIAL_WAKE_LOCK needed for a foreground location service to keep receiving GPS updates when the screen is off (Doze behavior for FGS)? What does Android battery-saver do to GPS?
8. Storage/file picking: Intent ACTION_OPEN_DOCUMENT via ActivityResultContracts.OpenDocument for arbitrary files (mime */*), copying a content Uri to getExternalFilesDir(...) with a ContentResolver InputStream, and using ACTION_CREATE_DOCUMENT / sharing a GPX via FileProvider with ACTION_SEND. Whether large (1+ GB) file copies via SAF are fine.
Return exact version strings with the source URL for each.`,
  },
  {
    key: 'protomaps',
    prompt: `${COMMON}

TOPIC: Free offline vector map data and styling with Protomaps (open source, OpenStreetMap-based), as of September 2026.
Find and verify:
1. Protomaps daily planet builds at https://build.protomaps.com (or maps.protomaps.com/builds): file naming (YYYYMMDD.pmtiles), size, max zoom (15?), license terms for extracting regions for free.
2. The pmtiles CLI (github.com/protomaps/go-pmtiles): latest release version, how to install on an ubuntu GitHub Actions runner (download release tarball URL pattern for linux x86_64, or "go install"), exact syntax of "pmtiles extract <planet-url> out.pmtiles --bbox=minLon,minLat,maxLon,maxLat" (also --region geojson, --maxzoom), rough output sizes (e.g. a 100x100 km area, a 300x300 km area, a whole small country at maxzoom 15), and "pmtiles convert" to MBTiles in case the client needs MBTiles.
3. The Protomaps basemap style: npm package @protomaps/basemaps — how to generate a MapLibre style JSON in a Node script for a given theme (which themes exist: light, dark, white, grayscale, black) and a given source URL, plus the "lang" option; the current major version and API (function name layers(sourceName, theme, {lang}) and namedFlavor / flavors). Also the tile schema version and whether the planet builds match the current basemaps layers version (version compatibility rules between planet build and @protomaps/basemaps major).
4. Offline glyphs and sprites: the protomaps/basemaps-assets GitHub repo (fonts/ as PBF ranges, sprites/v4/...) — exact directory layout, which fontstack names the style uses (e.g. "Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic"), and their license. How large the fonts directory is, and whether a subset of ranges (e.g. Latin only) suffices for a Latin-language region.
5. Attribution requirements: OpenStreetMap ODbL attribution text for the app, Protomaps attribution requirements, and license of the style code (BSD-3?).
6. Any alternative free offline vector sources worth noting (OpenFreeMap downloads, Geofabrik + tilemaker) with one-line trade-offs; and whether OSM raster tile bulk downloading is allowed (it is not; confirm policy URL).
Provide: a working bash snippet for GitHub Actions to install pmtiles and extract a bbox; a Node snippet to generate a dark style JSON pointing its source at "pmtiles://" URL placeholder and glyphs/sprites at asset:// URLs.`,
  },
  {
    key: 'gps-battery',
    prompt: `${COMMON}

TOPIC: Low-power GPS tracking design for a cycling app on Android; learn from existing open-source apps rather than theory.
Find and verify:
1. OpenTracks (github.com/OpenTracksApp/OpenTracks): what location provider it uses (LocationManager GPS vs fused), default recording interval and min distance settings, how it handles idle/auto-pause, how it records to GPX/KML, how its foreground service keeps GPS alive, whether it uses a wake lock, and its license (Apache-2.0?). Extract concrete defaults (e.g., "min recording interval 1s, recording distance interval 10 m, max accuracy 50 m").
2. Organic Maps and OsmAnd: what they do for battery in navigation mode (e.g., dim screen, reduce frame rate, disable 3D buildings, "keep screen on" options, dark map style). Any documented measured battery consumption numbers for GPS tracking on Android (e.g., mAh per hour with screen on/off) from reputable sources or issue threads.
3. Android platform facts: relative power cost of GPS receiver vs screen at typical brightness vs cellular/WiFi radios (cite sources, e.g., Android docs or academic measurements); whether airplane mode + GPS works; how to keep the screen on only while the app is visible (FLAG_KEEP_SCREEN_ON) and how to lower brightness for one window (WindowManager.LayoutParams.screenBrightness); benefits of pure-black UI on OLED.
4. Track data handling for long rides (1000 km, ~40+ hours, 1 Hz = ~150k points): sensible storage (SQLite), display simplification (Douglas-Peucker / radial distance thresholds), computing distance (haversine) with accuracy filtering (ignore points with accuracy > X m, ignore jumps > speed threshold), elevation gain smoothing (thresholded hysteresis, e.g., count gain only after 3-5 m sustained), and the GPX 1.1 format essentials (trk/trkseg/trkpt with lat/lon/ele/time, xsi schema header) including how to append to a GPX file incrementally so a crash never loses the file.
5. Heading source: GPS course (Location.getBearing, valid when moving > ~1 m/s) vs magnetometer compass (rotation vector sensor) — recommended fusion approach for a "course-up 3D view" that does not spin when stopped; sensor sampling rates and their battery cost.
Give concrete recommended defaults for our app (interval, distance filter, accuracy cutoff, auto-pause speed, camera pitch/zoom by speed) with justification.`,
  },
]

const results = await parallel(topics.map(t => () =>
  agent(t.prompt, { label: `research:${t.key}`, phase: 'Research', schema: SCHEMA, effort: 'high' })
    .then(r => ({ key: t.key, ...r }))))

const ok = results.filter(Boolean)
log(`${ok.length}/${topics.length} research agents returned`)
return ok