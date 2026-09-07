# maplibre

## Recommendation
Use MapLibre Android 13.6.0 from Maven Central. For a sideloaded, offline-only cyclist APK, prefer org.maplibre.gl:android-sdk-opengl:13.6.0 (15.6 MB AAR, installs on any device) — or android-sdk-vulkan-opengl if you want Vulkan where available; avoid the default android-sdk artifact because its manifest hard-requires Vulkan 1.0. minSdk 23, compileSdk >= 34, Kotlin 2.2+. Strip INTERNET/ACCESS_WIFI_STATE with tools:node="remove" and call MapLibre.setConnected(false); check the merged manifest in CI. Keep the vector tiles OUTSIDE the APK (getExternalFilesDir or filesDir) because neither pmtiles:// nor mbtiles:// can read assets; reference them as "url": "pmtiles://file:///abs/path.pmtiles" or "url": "mbtiles:///abs/path.mbtiles" in a style JSON you patch at runtime and load with Style.Builder.fromJson. Glyphs and sprites can live in assets ("asset://fonts/{fontstack}/{range}.pbf", "asset://sprites/sprite"). MBTiles is the more mature local format (several open PMTiles bugs in Jul–Aug 2026, incl. gzip-compressed archives and a null-deref on Android); if you ship PMTiles, use uncompressed-internal archives and test on target hardware. For the follow camera, set a persistent top padding once via CameraPosition.Builder.padding(0, 0.55*height, 0, 0), then each ~1 s fix call easeCamera(newCameraPosition(target/zoom/tilt<=60/bearing), 1000, false). For the puck, a SymbolLayer with iconRotate + iconRotationAlignment(MAP) over a tiny GeoJsonSource is the simplest and lightest; LocationComponent with useDefaultLocationEngine(false) + RenderMode.GPS + forceLocationUpdate also works out of the box if you want its built-in animation. Battery: the map already renders WHEN_DIRTY; add setMaximumFps(30), setPrefetchesTiles(false), consider a lower pixelRatio, keep the finished part of a long track in a rarely-updated source and only push the live tail per fix.

## Facts
- [verified] Latest stable MapLibre Android release is 13.6.0 (published 28 Aug 2026). Maven Central metadata for org.maplibre.gl:android-sdk, android-sdk-opengl and android-sdk-vulkan all list 13.6.0 as the newest version (lastUpdated 20260828).
  src: https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk/maven-metadata.xml
- [verified] Repository: mavenCentral(). Coordinates: org.maplibre.gl:android-sdk:13.6.0 (Vulkan backend since 13.0.0), org.maplibre.gl:android-sdk-opengl:13.6.0 (OpenGL ES), org.maplibre.gl:android-sdk-vulkan:13.6.0 (Vulkan), org.maplibre.gl:android-sdk-vulkan-opengl:13.6.0 (both backends, runtime-selected; exists since 13.5.0). No other MapLibre artifacts need to be declared: the POM pulls org.maplibre.gl:android-sdk-geojson:6.0.1, org.maplibre.gl:maplibre-android-gestures:0.0.4, org.maplibre.gl:android-sdk-turf:6.0.1, kotlin-stdlib 2.2.10, okhttp 4.12.0, timber 5.0.1, androidx.fragment 1.8.9, kotlinx-coroutines-core 1.10.2 transitively.
  src: https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk/13.6.0/android-sdk-13.6.0.pom
- [verified] AAR sizes for 13.6.0: android-sdk (Vulkan) 18.4 MB, android-sdk-opengl 15.6 MB, android-sdk-vulkan-opengl 33.2 MB (docs say the dual artifact is ~10 MB larger than single-backend).
  src: https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk-vulkan-opengl/13.6.0/
- [verified] Library minSdk = 23 (bumped from 21 in 12.0.0, changelog: 'Bump minimum Android SDK version from 21 to 23 (#3849)'); library compileSdk = 34; namespace org.maplibre.android.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/build.gradle.kts
- [verified] 13.0.0 changelog: '💥 Breaking: Use Vulkan as rendering backend for the org.maplibre.gl:android-sdk package. You can still use OpenGL ES with the org.maplibre.gl:android-sdk-opengl package.'
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/CHANGELOG.md
- [verified] The Vulkan flavor's AndroidManifest.xml (used by android-sdk and android-sdk-vulkan) declares <uses-feature android:name="android.hardware.vulkan.version" android:version="0x400003" android:required="true"/>; the multiBackend flavor (android-sdk-vulkan-opengl) declares the same feature with required="false" and falls back to OpenGL when Vulkan is unavailable. An APK built with android-sdk is therefore not installable on devices lacking Vulkan 1.0 hardware.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/vulkan/AndroidManifest.xml
- [verified] Backend auto-detection in android-sdk-vulkan-opengl: Vulkan when Build.VERSION.SDK_INT >= 24 (N) and PackageManager.hasSystemFeature(FEATURE_VULKAN_HARDWARE_VERSION, 0x400003); otherwise OpenGL. RenderingEngine.Type has values OPENGL, VULKAN; class is org.maplibre.android.RenderingEngine; RenderingEngine.getCurrentType() reports the active backend. Backend is fixed for the process lifetime after the first MapLibre.getInstance().
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/multiBackend/java/org/maplibre/android/RenderingEngine.java
- [verified] PMTiles support landed in PR #2882 (merged Jan 7 2025); Android CHANGELOG lists 'Add PMTiles support (#2882)' under 11.8.0, while the official docs page says 'Starting MapLibre Android 11.7.0, PMTiles archives are supported as tile sources.' Either way, every 12.x/13.x release has it. Later fixes: 11.8.8 'Force PMTiles metadata to always have XYZ tile scheme', 13.0.2 'better handle tile compression in PMTiles sources', 13.3.0 'Implement ambient cache for PMTiles sources (#4290)', 13.4.0 'Convert a PMTiles metadata decompression failure into an error response (#4399)'.
  src: https://maplibre.org/maplibre-native/android/examples/data/PMTiles/
- [verified] PMTiles URL syntax: the pmtiles:// prefix wraps another fully-specified URL that the normal resource loader can fetch. Local device file: pmtiles://file:///absolute/path/file.pmtiles (docs: 'pmtiles://file:// — read a file from device storage (use getExternalFilesDir or filesDir for the path)'; test app: RasterSource("watercolor", "pmtiles://file://$path", 256) where path is an absolute path starting with '/', giving three slashes). Remote: pmtiles://https://host/file.pmtiles. Relative URLs are NOT resolved ('MapLibre Native requires the URL inside pmtiles:// to be fully specified'). Core code: acceptsURL = url.starts_with("pmtiles://"); extract_url strips the prefix and requests the inner URL.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/default/src/mln/storage/pmtiles_file_source.cpp
- [verified] pmtiles://asset:// (PMTiles inside src/main/assets) is NOT supported: 'AssetManagerFileSource does not implement byte-range reads, which PMTiles requires to read its header and metadata. Use file:// from device storage instead.' Open issue #4360 (opened Jun 22 2026). Workaround: copy the archive from assets to filesDir/getExternalFilesDir on first run, or have the user pick/download it into app storage, then use pmtiles://file://.
  src: https://github.com/maplibre/maplibre-native/issues/4360
- [verified] PMTiles sources 'do not support offline pack downloads or caching' (irrelevant for a purely local file). The pmtiles:// prefix works with vector, raster and raster-dem sources, from style JSON or programmatically (VectorSource(id, "pmtiles://...") + layer.setSourceLayer("layername")). Programmatic raster-dem cannot set encoding; define it in style JSON.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/docs/data/PMTiles.md
- [verified] In style JSON, a PMTiles or MBTiles archive is referenced through the source's "url" field (the TileJSON slot), not "tiles". Example from MapLibre's own demo style: "terrain": {"type":"raster-dem","url":"pmtiles://https://demotiles.maplibre.org/pmtiles/raster/terrain.pmtiles","tileSize":512,"encoding":"terrarium","maxzoom":8}. For a local vector source: {"type":"vector","url":"pmtiles://file:///data/user/0/<pkg>/files/map.pmtiles"}. The native file source synthesizes TileJSON (with a tiles[] template) from the archive's header/metadata.
  src: https://demotiles.maplibre.org/pmtiles/raster/style-imagery.json
- [verified] MBTiles: 'Added the mbtiles file source for rendering vector tiles from file stored locally on the device' in 9.3.0 (Jan 2021); renamed MaptilerFileSource -> MBTilesFileSource in 9.6.0. Current core: acceptsURL = url.starts_with("mbtiles://"); path = percentDecode(url minus "mbtiles://"); request is rejected with 'MBTilesFileSource only supports absolute path urls' unless the part after "://" is an absolute path. So the syntax is mbtiles:///absolute/path/file.mbtiles (three slashes), used in the source's "url" field. It generates TileJSON: tilejson 2.0.0, scheme xyz, format from metadata (default png; 'mlt' -> encoding mlt), tiles template '<url>?file={x}/{y}/{z}.<format>', min/maxzoom from metadata or SELECT MIN/MAX(zoom_level). Spaces/special chars in the path must be percent-encoded.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/default/src/mln/storage/mbtiles_file_source.cpp
- [verified] mbtiles:// cannot read from APK assets (no mbtiles://asset:// or /android_asset support) — open feature request #3559. Community example of a working Android style entry: "url": "mbtiles:///data/user/0/pl.golem.mregion/files/map.mbtiles".
  src: https://github.com/maplibre/maplibre-native/issues/3559
- [verified] asset:// URLs: Style.Builder.fromUri javadoc: 'asset://... loads the style from the APK assets/ directory'; 'file://... loads the style from a file path'. AssetManagerFileSource.canRequest matches any resource URL starting with "asset://" and opens percentDecode(url.substr(8)) relative to the assets/ root via AAssetManager_open — so it serves style JSON, glyph PBFs, sprite JSON/PNG and GeoJSON alike (the docs' GeoJSON guide uses GeoJsonSource(id, URI("asset://points-sf.geojson"))). Style JSON template for a fully offline app: "glyphs": "asset://fonts/{fontstack}/{range}.pbf" (files at assets/fonts/<Font Name>/0-255.pbf, 256-511.pbf, ...), "sprite": "asset://sprites/sprite" (files assets/sprites/sprite.json, sprite.png, sprite@2x.json, sprite@2x.png). Per style spec, {fontstack} is replaced with the comma-separated font list from text-font and {range} with a 256-codepoint range like 0-255.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/cpp/asset_manager_file_source.cpp
- [verified] MapLibre.getInstance(Context) is equivalent to getInstance(context, null, WellKnownTileServer.MapLibre): no API key required (apiKey is @Nullable), and no network call is made — it only initializes thread utils, Timber, RenderingEngine type, FileSource directory paths, TileServerOptions and registers ConnectivityReceiver. Must be called on the UI thread before creating a MapView (typically in Application.onCreate or Activity.onCreate before setContentView). Other overloads: getInstance(Context, String apiKey, WellKnownTileServer) and getInstance(Context, String apiKey, WellKnownTileServer, RenderingEngine.Type type) — the last throws UnsupportedOperationException on single-backend artifacts if the type isn't the compiled-in backend.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/MapLibre.java
- [verified] Telemetry was removed from MapLibre Android in 9.4.0 ('Removed Telemetry #7', March 2021). There is no telemetry/analytics code to disable in 13.x.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/CHANGELOG.md
- [verified] The SDK's own AndroidManifest.xml declares uses-permission INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION; these merge into any consuming app unless removed with tools:node="remove". ConnectivityReceiver.isNetworkActive() calls ConnectivityManager.getActiveNetworkInfo() (requires ACCESS_NETWORK_STATE) when isConnected() is queried and no manual state was set; MapLibre.setConnected(Boolean) overrides it ('useful for apps which control their own connectivity state and want to bypass any checks to the ConnectivityManager').
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/AndroidManifest.xml
- [verified] MapView lifecycle methods to forward: onCreate(Bundle) (call after MapLibre.getInstance and after inflating), onStart(), onResume(), onPause(), onStop(), onSaveInstanceState(Bundle), onLowMemory(), onDestroy(). getMapAsync(OnMapReadyCallback) delivers the MapLibreMap. MapView constructors: MapView(Context), MapView(Context, AttributeSet), MapView(Context, AttributeSet, int), MapView(Context, MapLibreMapOptions).
  src: https://maplibre.org/maplibre-native/android/examples/getting-started/
- [verified] CameraPosition.Builder methods: target(LatLng?), zoom(Double) [range 0.0–25.5; MapLibreConstants.MINIMUM_ZOOM=0, MAXIMUM_ZOOM=25.5], tilt(Double) [degrees, 0.0–60.0; MapLibreConstants.MAXIMUM_TILT=60 / MAXIMUM_PITCH=60f], bearing(Double) [degrees clockwise from north], padding(DoubleArray?) and padding(left, top, right, bottom: Double) ['Padding in pixels that shifts the viewport by the specified amount. Applied padding is going to persist and impact following camera transformations. Specified in left, top, right, bottom order.'], centerAltitude(Double), fov(Double), roll(Double), build(). CameraPosition.kt is now Kotlin.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/camera/CameraPosition.kt
- [verified] MapLibreMap camera API: moveCamera(CameraUpdate), moveCamera(CameraUpdate, CancelableCallback); easeCamera(CameraUpdate), easeCamera(CameraUpdate, CancelableCallback), easeCamera(CameraUpdate, int durationMs), easeCamera(CameraUpdate, int durationMs, boolean easingInterpolator), easeCamera(CameraUpdate, int durationMs, boolean easingInterpolator, CancelableCallback); animateCamera(CameraUpdate), animateCamera(CameraUpdate, int durationMs), animateCamera(CameraUpdate, CancelableCallback), animateCamera(CameraUpdate, int durationMs, CancelableCallback). Also cameraPosition getter/setter, setMaxPitchPreference/setMinPitchPreference/setMaxZoomPreference/setMinZoomPreference, setPrefetchesTiles(boolean), setPrefetchZoomDelta(int), setFocalBearing(double bearing, float focalX, float focalY, long duration), triggerRepaint(), getLocationComponent(), getUiSettings(), setStyle(String), setStyle(String, Style.OnStyleLoaded), setStyle(Style.Builder), setStyle(Style.Builder, OnStyleLoaded). setPadding(int,int,int,int) is @Deprecated in favor of CameraPosition.Builder.padding / CameraUpdateFactory.paddingTo. Default MapLibreConstants.ANIMATION_DURATION = 300 ms.
  src: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/index.html
- [verified] CameraUpdateFactory statics: newCameraPosition(CameraPosition), newLatLng(LatLng), newLatLngZoom(LatLng, Double), newLatLngPadding(LatLng, left, top, right, bottom: Double), newLatLngBounds(LatLngBounds, Int padding) + 3 overloads (per-side padding; bearing+tilt variants), paddingTo(DoubleArray?), paddingTo(left, top, right, bottom: Double), zoomTo(Double), zoomBy(Double), zoomBy(Double, Point focus), zoomIn(), zoomOut(), tiltTo(Double), bearingTo(Double). To keep the puck in the lower third, set a persistent top padding (e.g. top = 0.5–0.6 × view height, bottom = 0) via CameraPosition.Builder.padding(...) or paddingTo(...): the camera target is then placed at the centre of the padded viewport, i.e. lower on screen.
  src: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.camera/-camera-update-factory/index.html
- [verified] GeoJsonSource constructors include GeoJsonSource(String id), GeoJsonSource(String id, GeoJsonOptions), GeoJsonSource(String id, String geoJson[, GeoJsonOptions]), GeoJsonSource(String id, URI uri[, options]), GeoJsonSource(String id, URL url[, options]), GeoJsonSource(String id, FeatureCollection[, options]), GeoJsonSource(String id, Feature[, options]), GeoJsonSource(String id, Geometry[, options]). Update methods: setGeoJson(String json), setGeoJson(FeatureCollection), setGeoJson(Feature), setGeoJson(Geometry). GeoJsonOptions (Kotlin) builder-style: withMinZoom(Int), withMaxZoom(Int), withBuffer(Int), withLineMetrics(Boolean), withTolerance(Float), withCluster(Boolean), withClusterMaxZoom/Radius/MinPoints, withClusterProperty(...), withSynchronousUpdate(Boolean). 13.0.0 notes: laggy-map fix means large GeoJSON updates are asynchronous by default; use GeoJsonOptions().withSynchronousUpdate(true) only if you need the old synchronous behaviour.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/style/sources/GeoJsonOptions.kt
- [verified] Layer constructors: LineLayer(String layerId, String sourceId), SymbolLayer(String layerId, String sourceId); both have withProperties(vararg PropertyValue). PropertyFactory statics (org.maplibre.android.style.layers.PropertyFactory): lineColor(@ColorInt int) / lineColor(String) / lineColor(Expression); lineWidth(Float) / lineWidth(Expression); lineCap(String: Property.LINE_CAP_ROUND etc.), lineJoin(String); iconImage(String|Expression), iconRotate(Float|Expression) [degrees clockwise], iconRotationAlignment(String: Property.ICON_ROTATION_ALIGNMENT_MAP so the arrow rotates with the map), iconAllowOverlap(Boolean), iconIgnorePlacement(Boolean), iconSize(Float), iconAnchor(String). Style.addImage overloads: addImage(String name, Bitmap), addImage(String, Bitmap, boolean sdf), addImage(String, Drawable), plus stretch/content variants; Style.Builder.withImage(id, Bitmap|Drawable[, sdf]) also exists. Style: addSource(Source), addLayer(Layer), addLayerBelow(Layer, String below), addLayerAbove(Layer, String above), getLayer(String), getSource(String), removeLayer(String|Layer), removeSource(String|Source). Style.Builder: fromUri(String) (fromUrl deprecated), fromJson(String), withSource(Source), withLayer(Layer), withImage(...).
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/style/layers/PropertyFactory.java
- [verified] LocationComponent works with manually fed android.location.Location: build LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(false).locationComponentOptions(opts).build(); call activateLocationComponent(options); isLocationComponentEnabled = true; then forceLocationUpdate(Location) or forceLocationUpdate(List<Location>, boolean lookAheadUpdate) (intermediate points animate the puck). Javadoc: 'set useDefaultLocationEngine ... to false. No engine is going to be initialized and you can push location updates with forceLocationUpdate(Location).' RenderMode.GPS = 'Tracking the user location with bearing considered from android.location.Location' (LocationComponent.setRenderMode(GPS) calls setGpsBearing(lastLocation.getBearing()); LocationAnimatorCoordinator animates bearing from Location.getBearing() with shortest-rotation interpolation). RenderMode.COMPASS uses the CompassEngine (magnetometer); RenderMode.NORMAL ignores bearing. CameraMode constants: NONE, NONE_COMPASS, NONE_GPS, TRACKING, TRACKING_COMPASS, TRACKING_GPS ('Camera tracks the user location, with normalized bearing'), TRACKING_GPS_NORTH; setCameraMode(int), setCameraMode(int, OnLocationCameraTransitionListener), setCameraMode(int, long transitionDuration, Double zoom, Double bearing, Double tilt, listener). LocationComponentOptions.Builder has gpsDrawable(int), bearingDrawable(int), foregroundDrawable(int), backgroundDrawable(int), foregroundTintColor(Integer), bearingTintColor(Integer), accuracyAlpha(float), elevation(float), enableStaleState(boolean), pulseEnabled(boolean), compassAnimationEnabled(Boolean), accuracyAnimationEnabled(boolean), trackingAnimationDurationMultiplier(float), layerAbove(String)/layerBelow(String). LocationComponentActivationOptions.Builder also has useSpecializedLocationLayer(boolean) (dedicated native layer instead of symbol+circle layers).
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/location/LocationComponent.java
- [verified] Rendering/battery: MapView renders WHEN_DIRTY by default. MapRenderer.RenderingRefreshMode enum: WHEN_DIRTY ('The map is rendered only in response to an event that affects the rendering of the map. This mode is preferred to improve battery life and overall system performance') and CONTINUOUS ('repeatedly re-rendered at the refresh rate of the display... preferred when benchmarking'). MapView.setRenderingRefreshMode(MapRenderer.RenderingRefreshMode) / getRenderingRefreshMode() (exposed since 11.5.0). MapView.setMaximumFps(int) caps the frame rate ('can't excess the ability of device hardware'; values <=0 ignored; implemented as expectedRenderTime = 1E9/maximumFps in MapRenderer). Both throw IllegalStateException if called before the renderer exists (call after MapView.onCreate / in getMapAsync). MapLibreMapOptions: textureMode(boolean) (TextureView instead of SurfaceView — needed only for view animations/overlap; SurfaceView is the cheaper default), translucentTextureSurface(boolean), foregroundLoadColor(int), pixelRatio(float) (lower ratio = fewer pixels rendered), setPrefetchesTiles(boolean), setPrefetchZoomDelta(int), crossSourceCollisions(boolean), localIdeographFontFamily(String...) / localIdeographFontFamilyEnabled(boolean), fastPFOREnabled(boolean), minZoomPreference/maxZoomPreference/minPitchPreference/maxPitchPreference(double), gesture toggles, compassEnabled/logoEnabled/attributionEnabled(boolean), apiBaseUri(String), asyncRendererCleanup(boolean), actionJournal*(...). Equivalent XML attrs: maplibre_renderTextureMode, maplibre_pixelRatio, maplibre_enableTilePrefetch, maplibre_prefetchZoomDelta, maplibre_cameraTilt, maplibre_cameraPitchMax, maplibre_uiCompass, maplibre_uiLogo, maplibre_uiAttribution, maplibre_localIdeographEnabled, maplibre_fastPFOREnabled etc.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapView.java
- [verified] Package rename: 11.0.0 '💥 Breaking: Change package of all classes from com.mapbox.mapboxsdk to org.maplibre.android'. Current (13.6.0) imports: org.maplibre.android.MapLibre; org.maplibre.android.WellKnownTileServer; org.maplibre.android.RenderingEngine; org.maplibre.android.maps.MapView; org.maplibre.android.maps.MapLibreMap; org.maplibre.android.maps.MapLibreMapOptions; org.maplibre.android.maps.Style; org.maplibre.android.maps.OnMapReadyCallback; org.maplibre.android.maps.renderer.MapRenderer; org.maplibre.android.camera.CameraPosition; org.maplibre.android.camera.CameraUpdateFactory; org.maplibre.android.geometry.LatLng; org.maplibre.android.style.sources.GeoJsonSource; org.maplibre.android.style.sources.GeoJsonOptions; org.maplibre.android.style.sources.VectorSource; org.maplibre.android.style.layers.LineLayer; org.maplibre.android.style.layers.SymbolLayer; org.maplibre.android.style.layers.PropertyFactory; org.maplibre.android.style.layers.Property; org.maplibre.android.style.expressions.Expression; org.maplibre.android.location.LocationComponent; org.maplibre.android.location.LocationComponentActivationOptions; org.maplibre.android.location.LocationComponentOptions; org.maplibre.android.location.modes.RenderMode; org.maplibre.android.location.modes.CameraMode. GeoJSON model classes come from android-sdk-geojson: org.maplibre.geojson.Feature, FeatureCollection, LineString, Point. API docs at maplibre.org currently document 13.4.1 (openglRelease) but the package list is the same for 13.6.0.
  src: https://maplibre.org/maplibre-native/android/api/index.html
- [likely] MapLibre's own build uses Kotlin 2.3.20 and publishes against kotlin-stdlib 2.2.10, okhttp 4.12.0, coroutines 1.10.2 — the consuming app should use Kotlin Gradle plugin >= 2.2 and compileSdk >= 34 to avoid metadata/AGP dependency warnings.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/gradle/libs.versions.toml
- [verified] Style spec: vector source 'url' is documented as 'A URL to a TileJSON resource. Supported protocols are http: and https:' — the pmtiles:// and mbtiles:// schemes in 'url' are MapLibre Native extensions handled by native file sources, not part of the style spec, so styles using them are not portable to MapLibre GL JS unchanged.
  src: https://maplibre.org/maplibre-style-spec/sources/
- [verified] Known open PMTiles bugs as of Aug 2026: #4462 'archives with internal_compression = gzip fail to load every tile (incorrect header check)' (Aug 5 2026), #4459 'Android: SIGSEGV (null deref) in PMTilesFileSource thread during normal map interaction' (Aug 4 2026), #4421 'PMTilesFileSource: invalid map<K,T> key on large archives - directory LRU evicts entries still in use' (Jul 21 2026). Prefer archives with internal compression 'none' or test your specific archive; MBTiles is the more battle-tested local format.
  src: https://github.com/maplibre/maplibre-native/issues?q=pmtiles

## Gotchas
- Manifest merging: the MapLibre AAR contributes INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION permissions. For a 'no INTERNET permission' app, add xmlns:tools and <uses-permission android:name="android.permission.INTERNET" tools:node="remove"/> (and ACCESS_WIFI_STATE). Be cautious removing ACCESS_NETWORK_STATE: ConnectivityReceiver.isNetworkActive() calls ConnectivityManager.getActiveNetworkInfo() (a normal permission; missing it can throw SecurityException). Safest: keep ACCESS_NETWORK_STATE or call MapLibre.setConnected(false) right after getInstance so the check is bypassed. Verify the merged manifest in CI (build/intermediates/merged_manifests) as part of the GitHub Actions job.
- Default artifact org.maplibre.gl:android-sdk is Vulkan since 13.0.0 and its manifest declares android.hardware.vulkan.version 0x400003 required=true — the APK will refuse to install on GPUs without Vulkan 1.0. For a sideloaded cyclist app on unknown hardware pick either android-sdk-opengl (smallest, runs anywhere) or android-sdk-vulkan-opengl (~33 MB AAR, picks Vulkan on API 24+ with Vulkan hardware, otherwise OpenGL).
- pmtiles://asset:// does NOT work (AssetManagerFileSource lacks byte-range reads, issue #4360) and mbtiles:// cannot read assets either (#3559). Ship tiles outside the APK: user copies the .pmtiles/.mbtiles into getExternalFilesDir(null) or use the system file picker (ACTION_OPEN_DOCUMENT) and copy into filesDir; then reference pmtiles://file:///abs/path or mbtiles:///abs/path. Style JSON must be generated/patched at runtime with the absolute path (or use Style.Builder.fromJson with the string), because assets are read-only and paths differ per device.
- Relative URLs inside pmtiles:// are not resolved against the style URL on Native; always use a fully specified inner URL (file:// or https://).
- Fonts/sprites CAN live in assets: "glyphs": "asset://fonts/{fontstack}/{range}.pbf" and "sprite": "asset://sprites/sprite" — asset paths are relative to src/main/assets/, percent-decoded, and the fontstack directory name must exactly match the text-font name(s) used in the style (e.g. assets/fonts/Noto Sans Regular/0-255.pbf). Multi-font stacks request 'FontA,FontB' as one directory name, so keep text-font to a single font per layer to avoid needing combined glyph dirs.
- MBTiles paths with spaces or non-ASCII need percent-encoding (the source percent-decodes the URL). Keep the file in an ASCII path to avoid trouble.
- setMaximumFps() and setRenderingRefreshMode() throw IllegalStateException if called before the MapRenderer exists — call them after mapView.onCreate(savedInstanceState), e.g. inside getMapAsync. Default is already WHEN_DIRTY; a camera easeCamera loop or continuous LocationComponent animations mark the map dirty every frame anyway, so the real battery levers are: (1) feed location at ~1 Hz and ease over ~1000 ms rather than animating faster, (2) setMaximumFps(30) or lower, (3) reduce pixelRatio in MapLibreMapOptions, (4) disable tile prefetch (setPrefetchesTiles(false)) or lower prefetchZoomDelta, (5) avoid textureMode (TextureView costs an extra composition pass).
- Tilt/pitch is capped at 60 degrees (MapLibreConstants.MAXIMUM_TILT = 60); requesting more is clamped. Use MapLibreMapOptions.maxPitchPreference/minPitchPreference or map.setMaxPitchPreference if you want to restrict user gestures.
- MapLibreMap.setPadding(int...) is deprecated; use CameraPosition.Builder.padding(l,t,r,b) or CameraUpdateFactory.paddingTo(...). Padding persists across subsequent camera moves, so set it once (e.g. top = 55% of MapView height) and then only update target/bearing each GPS fix. Padding is in pixels (not dp).
- GeoJsonSource.setGeoJson on a 1000 km track: the whole FeatureCollection is re-tiled on each call. For long rides, keep the finished part of the track in one source that is updated rarely (e.g. every N points or on each km) and the 'live tail' in a second small source updated per fix; enable GeoJsonOptions().withTolerance(...) / withBuffer(...) sensibly, and do NOT enable withLineMetrics unless you need line-gradient. Simplify old points (Douglas-Peucker) before pushing.
- LocationComponent adds its own layers/images to the Style; if you call map.setStyle(...) again you must re-activate it in the new Style's OnStyleLoaded. It also references R.style.maplibre_LocationComponent resources (prefix renamed from mapbox_ in 10.0.0). LocationComponent's RenderMode.GPS uses Location.getBearing() as provided — filter/smooth bearing yourself (or drop fixes with hasBearing()==false) before forceLocationUpdate, otherwise the puck jitters at low speed.
- Style.Builder.fromUrl is deprecated (use fromUri). Loading a style via asset:// works, but because tile paths must be absolute device paths, in practice you read the asset template, substitute the path, and use Style.Builder.fromJson(json).
- CHANGELOG and the docs disagree about the first PMTiles version (11.8.0 vs 11.7.0). Irrelevant for 13.6.0 but do not pin anything below 11.8.8 (XYZ scheme fix) or 13.0.2 (tile compression fix).
- Kotlin: the 13.6.0 AAR depends on kotlin-stdlib 2.2.10; use Kotlin Gradle Plugin 2.2.x or newer and compileSdk 34+ in the app to avoid 'incompatible Kotlin metadata' and AGP dependency-compileSdk errors on GitHub Actions.
- OkHttp 4.12.0 and timber are pulled in as runtime deps even for an offline app; they are harmless without INTERNET permission (any accidental http(s) source will just fail), but R8 shrinking will drop most of it.

## Snippets
### Gradle dependency (choose ONE artifact) — app/build.gradle.kts
```
repositories { mavenCentral() }

dependencies {
    // OpenGL ES only — smallest, installs on any device (recommended for a sideloaded app):
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.0")
    // or: Vulkan with OpenGL fallback chosen at runtime (bigger AAR):
    // implementation("org.maplibre.gl:android-sdk-vulkan-opengl:13.6.0")
    // or the default (Vulkan ONLY, manifest requires Vulkan 1.0 hardware):
    // implementation("org.maplibre.gl:android-sdk:13.6.0")
}

android {
    compileSdk = 35
    defaultConfig { minSdk = 23 }
}
```
### AndroidManifest.xml — strip network permissions merged in from the MapLibre AAR
```
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" tools:node="remove" />
    <!-- keep ACCESS_NETWORK_STATE (normal permission, no network access) so
         ConnectivityReceiver.getActiveNetworkInfo() cannot throw, or call MapLibre.setConnected(false) -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    ...
</manifest>
```
### Offline style JSON template stored at src/main/assets/style.json (tiles path patched at runtime)
```
{
  "version": 8,
  "name": "offline-bike",
  "glyphs": "asset://fonts/{fontstack}/{range}.pbf",
  "sprite": "asset://sprites/sprite",
  "sources": {
    "omt": {
      "type": "vector",
      "url": "pmtiles://file://__TILES_PATH__"
    }
  },
  "layers": [
    { "id": "bg", "type": "background", "paint": { "background-color": "#f8f4f0" } },
    { "id": "roads", "type": "line", "source": "omt", "source-layer": "transportation",
      "paint": { "line-color": "#888", "line-width": 1.5 } },
    { "id": "labels", "type": "symbol", "source": "omt", "source-layer": "place",
      "layout": { "text-field": ["get", "name"], "text-font": ["Noto Sans Regular"], "text-size": 12 } }
  ]
}
// For MBTiles instead: "url": "mbtiles://__TILES_PATH__"  (with __TILES_PATH__ = /data/user/0/<pkg>/files/map.mbtiles -> mbtiles:///data/...)
// Layout of assets: assets/fonts/Noto Sans Regular/0-255.pbf ... ; assets/sprites/sprite.json, sprite.png, sprite@2x.json, sprite@2x.png
```
### Activity: initialise MapLibre + MapView, load local style, forward lifecycle
```
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.renderer.MapRenderer
import java.io.File

class MapActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)          // no API key, no network; must precede MapView creation
        MapLibre.setConnected(false)        // never consult ConnectivityManager

        val options = MapLibreMapOptions.createFromAttributes(this)
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
            .setPrefetchesTiles(false)      // battery: fewer tile requests
            .maxPitchPreference(60.0)
            .camera(CameraPosition.Builder().target(LatLng(48.0, 17.0)).zoom(15.0).tilt(55.0).build())

        mapView = MapView(this, options)
        setContentView(mapView)
        mapView.onCreate(savedInstanceState)
        mapView.setMaximumFps(30)           // renderer exists after onCreate
        mapView.setRenderingRefreshMode(MapRenderer.RenderingRefreshMode.WHEN_DIRTY) // already the default

        val tiles = File(getExternalFilesDir(null), "map.pmtiles")   // copied there by user/first run
        val styleJson = assets.open("style.json").bufferedReader().use { it.readText() }
            .replace("__TILES_PATH__", tiles.absolutePath)          // -> pmtiles://file:///storage/.../map.pmtiles

        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = false
            m.setStyle(Style.Builder().fromJson(styleJson)) { style -> onStyleReady(m, style) }
        }
    }

    private fun onStyleReady(m: MapLibreMap, style: Style) { /* add track/puck layers, see below */ }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
}
```
### Track line (GeoJsonSource + LineLayer) and custom puck (SymbolLayer with rotated icon)
```
import android.graphics.BitmapFactory
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private val trackPoints = ArrayList<Point>()
private lateinit var trackSource: GeoJsonSource
private lateinit var puckSource: GeoJsonSource

fun addTrackAndPuck(style: Style) {
    trackSource = GeoJsonSource("track", FeatureCollection.fromFeatures(emptyList()),
        GeoJsonOptions().withTolerance(0.5f).withBuffer(64))
    style.addSource(trackSource)
    style.addLayer(LineLayer("track-line", "track").withProperties(
        lineColor("#1E88E5"),
        lineWidth(5f),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND)))

    style.addImage("puck", BitmapFactory.decodeResource(resources, R.drawable.puck_arrow))
    puckSource = GeoJsonSource("puck")
    style.addSource(puckSource)
    style.addLayer(SymbolLayer("puck-layer", "puck").withProperties(
        iconImage("puck"),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), // rotate with map so bearing is geographic
        iconAllowOverlap(true),
        iconIgnorePlacement(true),
        iconSize(1.0f)))
}

fun onFix(lon: Double, lat: Double, bearingDeg: Float) {
    trackPoints.add(Point.fromLngLat(lon, lat))
    trackSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(trackPoints)))   // or FeatureCollection
    val puck = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    puckSource.setGeoJson(puck)
    // rotate puck: either per-feature via a property + expression, or simply set the layer property:
    (map?.style?.getLayer("puck-layer") as? SymbolLayer)?.setProperties(iconRotate(bearingDeg))
}
```
### Ease camera to tilted, heading-up position with the target in the lower third (persistent top padding)
```
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

private var paddingSet = false

fun followRider(lat: Double, lon: Double, bearingDeg: Double, intervalMs: Int = 1000) {
    val m = map ?: return
    val builder = CameraPosition.Builder()
        .target(LatLng(lat, lon))
        .zoom(16.5)
        .tilt(55.0)                 // max 60.0
        .bearing(bearingDeg)        // degrees clockwise from north; map rotates so heading is 'up'
    if (!paddingSet) {
        // padding is in pixels, order left, top, right, bottom; it persists for later camera moves
        builder.padding(0.0, mapView.height * 0.55, 0.0, 0.0)
        paddingSet = true
    }
    // linear ease across the GPS interval so motion is continuous; easingInterpolator=false = linear
    m.easeCamera(CameraUpdateFactory.newCameraPosition(builder.build()), intervalMs, false)
}

// One-shot alternatives:
// m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))               // instant
// m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 1500)       // fly-style animation
// m.easeCamera(CameraUpdateFactory.paddingTo(0.0, topPx, 0.0, 0.0))       // change padding only
```
### Alternative puck: built-in LocationComponent fed manually with android.location.Location (GPS bearing)
```
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode

fun setupLocationComponent(m: MapLibreMap, style: Style) {
    val lcOptions = LocationComponentOptions.builder(this)
        .gpsDrawable(R.drawable.puck_arrow)      // icon used in RenderMode.GPS
        .accuracyAlpha(0f)
        .pulseEnabled(false)
        .enableStaleState(false)
        .build()
    val activation = LocationComponentActivationOptions.builder(this, style)
        .useDefaultLocationEngine(false)         // we push fixes ourselves
        .locationComponentOptions(lcOptions)
        .build()
    m.locationComponent.apply {
        activateLocationComponent(activation)
        isLocationComponentEnabled = true
        renderMode = RenderMode.GPS              // bearing from Location.getBearing()
        cameraMode = CameraMode.NONE             // we drive the camera with easeCamera (or use TRACKING_GPS)
    }
}

fun onLocation(loc: android.location.Location) {
    if (loc.hasBearing()) map?.locationComponent?.forceLocationUpdate(loc)
}
```
