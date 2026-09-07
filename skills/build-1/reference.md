# Build 1 reference: verified facts and snippets (as of 2026-09-07)

Sources were primary (Maven Central metadata, Google Maven, MapLibre source/changelog, Android docs,
actions/runner-images, Protomaps docs/npm/PyPI). Re-verify anything older than two months. Full
research notes with source URLs: `~/Documents/GitHub/fablab-tools/skills/build-1/reference/*.md`.

## GitHub from the sandbox

```bash
H="Authorization: Bearer $(gh auth token)"          # gh itself fails TLS (OSStatus -26276); the token works
curl -sS -H "$H" https://api.github.com/repos/OWNER/REPO/actions/runs?head_sha=SHA
curl -sS -H "$H" https://api.github.com/repos/OWNER/REPO/releases
# create repo: POST /user/repos  {"name","private":true,"auto_init":true,"gitignore_template":"Node","license_template":"mit"}
git config credential.helper '!gh auth git-credential'   # push works; "failed to store: 100001" is harmless
```
Sandbox: Bash can only write under the project dir and $TMPDIR; `~/.claude/*` and `.claude/skills` are
Bash-read-only but the Write/Edit tools can write there. npm needs `npm_config_cache=$TMPDIR/npm-cache`.

## Android toolchain (GitHub Actions ubuntu-latest = Ubuntu 24.04)

- Preinstalled: Temurin 17 default, Gradle 9.7.1, Android SDK at `/usr/local/lib/android/sdk` with
  platforms 34–37 and build-tools 34–37, licences accepted; AGP auto-downloads missing packages.
- Actions: `actions/checkout@v7`, `actions/setup-java@v6` (temurin 17), `gradle/actions/setup-gradle@v6`
  (`gradle-version: "9.7.1"`, then run `gradle`, not `./gradlew`), `actions/setup-node@v7`,
  `actions/upload-artifact@v7`, `actions/setup-python@v7`. `gh` is preinstalled; pass
  `GH_TOKEN: ${{ github.token }}` and set `permissions: contents: write`.
- AGP 9.4.0: Kotlin built in (bundled KGP 2.2.10); do not apply any Kotlin plugin; kapt incompatible;
  targetSdk must be set explicitly; unit tests only for debug; use `proguard-android-optimize.txt`.
- Versions that build together at compileSdk 36: core-ktx 1.18.0 (1.19.0 needs 37), appcompat 1.8.0,
  material 1.14.0, activity-ktx 1.13.0, lifecycle 2.11.0, preference-ktx 1.2.1, constraintlayout 2.2.2,
  kotlinx-coroutines-android 1.11.0, junit 4.13.2, MapLibre 13.6.0 (pulls kotlin-stdlib 2.2.10).

```kotlin
// app/build.gradle.kts essentials
plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.example.app"; compileSdk = 36
    defaultConfig { minSdk = 26; targetSdk = 36
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1 }
    signingConfigs { create("release") { storeFile = rootProject.file("keystore/app.p12")
        storePassword = "pw"; keyAlias = "alias"; keyPassword = "pw"; storeType = "pkcs12" } }
    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { viewBinding = true }
}
```

PKCS12 key without Java:
```bash
openssl req -x509 -newkey rsa:2048 -sha256 -days 10950 -nodes -keyout key.pem -out cert.pem -subj "/CN=App"
openssl pkcs12 -export -inkey key.pem -in cert.pem -name alias -out app.p12 -passout pass:pw
```

## MapLibre Native Android 13.6.0

- Artifacts: `android-sdk` (Vulkan-only, manifest requires Vulkan hardware), `android-sdk-opengl`
  (use this), `android-sdk-vulkan-opengl` (both, +18 MB). minSdk 23. Package `org.maplibre.android.*`;
  GeoJSON classes `org.maplibre.geojson.*`. Telemetry removed long ago; no API key.
- `MapLibre.getInstance(context)` before creating a MapView (no network); `MapLibre.setConnected(false)`.
- The AAR merges INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, location permissions into your
  manifest: remove with `tools:node="remove"`; keep ACCESS_NETWORK_STATE.
- Local tiles in style JSON `sources.<id>.url`: `mbtiles:///abs/path.mbtiles` or
  `pmtiles://file:///abs/path.pmtiles` (three slashes; percent-encode spaces). Not from assets.
  Style, glyphs, sprites may be `asset://…` (`asset://fonts/{fontstack}/{range}.pbf`).
- Open PMTiles bugs: gzip internal compression loads no tiles (#4462), Android SIGSEGV (#4459),
  large-archive LRU (#4421) → prefer MBTiles.
- MapView lifecycle to forward: onCreate(Bundle), onStart, onResume, onPause, onStop,
  onSaveInstanceState, onLowMemory, onDestroy. `setMaximumFps(30)` and rendering mode only after
  `mapView.onCreate`. Render mode default WHEN_DIRTY. `map.setPrefetchesTiles(false)`.
- Camera: `CameraPosition.Builder().target(LatLng).zoom(z).tilt(<=60).bearing(deg).padding(l,t,r,b)`
  (padding persists), `map.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 1000, false)`.
- Data: `GeoJsonSource(id, FeatureCollection, GeoJsonOptions().withBuffer(64).withTolerance(0.5f))`,
  `setGeoJson(...)`, `LineLayer(id, src).withProperties(lineColor(...), lineWidth(5f))`,
  `SymbolLayer(id, src)` with `iconImage`, `iconRotate`, `iconRotationAlignment(ICON_ROTATION_ALIGNMENT_MAP)`,
  `iconAllowOverlap(true)`, `iconIgnorePlacement(true)`; `style.addImage(name, bitmap)`.
- Failures: `mapView.addOnDidFailLoadingMapListener { msg -> }` covers style JSON only; add a watchdog
  timer for unreadable tile files (OnStyleLoaded never fires). Never pass `"tiles": []` sources with
  layers; use a background-only style when no map file is active.

## Protomaps offline pipeline (CI)

```bash
PM=1.31.2; curl -fsSL -o p.tgz https://github.com/protomaps/go-pmtiles/releases/download/v$PM/go-pmtiles_${PM}_Linux_x86_64.tar.gz
tar -xzf p.tgz pmtiles && sudo install -m0755 pmtiles /usr/local/bin/
BUILD=$(curl -fsSL https://build-metadata.protomaps.dev/builds.json | jq -r 'map(.key)|sort|last')
pmtiles extract https://build.protomaps.com/$BUILD out.pmtiles --bbox=minLon,minLat,maxLon,maxLat --maxzoom=15 --download-threads=8
pip install pmtiles==3.7.0 && pmtiles-convert out.pmtiles out.mbtiles      # go CLI cannot do this direction
```
Style: `import { layers, namedFlavor } from "@protomaps/basemaps"` (5.7.2);
`{ version: 8, glyphs: "asset://fonts/{fontstack}/{range}.pbf", sprite: "asset://sprites/v4/dark",
sources: { protomaps: { type: "vector", url: "{MAP_URL}" } }, layers: layers("protomaps", namedFlavor("dark"), { lang: "en" }) }`.
Fonts: `basemaps-assets/fonts/<Noto Sans Regular|Medium|Italic>/<start>-<start+255>.pbf` (256 ranges
each, ~11 MB total); sprites `sprites/v4/dark{,@2x}.{json,png}`. Attribution: "© OpenStreetMap contributors".
Sizes: Berlin bbox at z15 ≈ 84 MB; 300 km square ≈ 1–2 GB; GitHub release asset limit 2 GB.

## Foreground GPS recording

- Manifest: ACCESS_FINE/COARSE_LOCATION, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION,
  POST_NOTIFICATIONS, WAKE_LOCK; `<service android:foregroundServiceType="location" android:exported="false"/>`.
- Order: request permissions in a visible Activity → `ContextCompat.startForegroundService` →
  in `onStartCommand` (call `super` first in LifecycleService) create channel (IMPORTANCE_LOW) →
  `ServiceCompat.startForeground(this, id != 0, notification, if (SDK_INT >= 30) FOREGROUND_SERVICE_TYPE_LOCATION else 0)`
  in try/catch → start GPS. Never request ACCESS_BACKGROUND_LOCATION. Location type has no 6 h cap.
- GPS only: `LocationManagerCompat.requestLocationUpdates(lm, LocationManager.GPS_PROVIDER,
  LocationRequestCompat.Builder(1000).setQuality(QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(1000)
  .setMinUpdateDistanceMeters(0f).setMaxUpdateDelayMillis(0).build(), ContextCompat.getMainExecutor(ctx), listener)`
  with `LocationListenerCompat`; `GnssStatus.Callback` via `registerGnssStatusCallback(executor, cb)` (30+)
  else `(cb, Handler)`. Airplane mode keeps GPS. Partial wake lock as OEM insurance.
- Location fields: `hasBearing/bearing` (moving only), `hasSpeed/speed`, `hasAccuracy/accuracy`,
  API 26+ vertical/bearing/speed accuracy, `hasMslAltitude/mslAltitudeMeters` on 34+ else WGS84 `altitude`.
- Filtering defaults that match OpenTracks/OsmAnd: accuracy ≤ 50 m; jump if d > 30 m/s·dt + accuracies;
  store when moved ≥ 5 m; new segment after 200 m or 6 min gap; auto-pause < 1.0 m/s for 10 s, resume ≥ 1.5.
- Battery Saver: `PowerManager.locationPowerSaveMode` 1 or 2 disables GPS with screen off → warn user.

## Files, sharing

`ActivityResultContracts.OpenDocument(arrayOf("*/*"))` → stream-copy to `getExternalFilesDir("maps")`
on Dispatchers.IO (no size limit). `CreateDocument("application/gpx+xml")` for save-as. FileProvider
`${applicationId}.fileprovider` with `<external-files-path name="tracks" path="tracks/"/>` + ACTION_SEND.
GPX parsing on both JVM and Android: `javax.xml.parsers.SAXParserFactory` (XmlPullParser stubs fail in JVM tests).
