# android-toolchain

## Recommendation
Use AGP 9.4.0 (com.android.application only — Kotlin is built in; do not apply org.jetbrains.kotlin.android), Gradle 9.7.1, Temurin JDK 17, compileSdk/targetSdk 36 (37 is supported by AGP 9.4.0 and preinstalled as platforms;android-37.0 if you want Android 17), minSdk 26. On ubuntu-latest (Ubuntu 24.04) use actions/checkout@v7, actions/setup-java@v6 (temurin 17), gradle/actions/setup-gradle@v6 with gradle-version: '9.7.1' and run `gradle assembleRelease` (no wrapper jar needed); skip android-actions/setup-android because the SDK (build-tools 36.0.0, android-36, android-37.0, licenses accepted) is already at /usr/local/lib/android/sdk and AGP auto-downloads anything missing. Sign with a PKCS12 keystore (storeType = "pkcs12", keyPassword == storePassword) decoded from a base64 secret, keep the signingConfig conditional so secret-less builds still succeed, and publish with the preinstalled gh under `permissions: contents: write` using `gh release view || gh release create ... --generate-notes` plus `gh release upload --clobber` for reruns. Dependencies: core-ktx 1.19.0, appcompat 1.8.0, material 1.14.0, activity-ktx 1.13.0, lifecycle-service/runtime-ktx 2.11.0, preference-ktx 1.2.1, constraintlayout 2.2.2, kotlinx-coroutines-android 1.11.0. For recording: declare FOREGROUND_SERVICE + FOREGROUND_SERVICE_LOCATION + foregroundServiceType="location" (+ POST_NOTIFICATIONS, WAKE_LOCK), request ACCESS_FINE_LOCATION (and POST_NOTIFICATIONS on 33+) from the visible Activity before startForegroundService, call ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION) immediately in onStartCommand, and never request ACCESS_BACKGROUND_LOCATION. Use LocationManager.GPS_PROVIDER with LocationRequest.Builder(1000).setQuality(QUALITY_HIGH_ACCURACY) on API 31+ (legacy overload below), LocationListenerCompat, and a GnssStatus.Callback for satellite counts; hold a PARTIAL_WAKE_LOCK inside the FGS as OEM insurance and warn when Battery Saver's location mode would disable GPS with the screen off. GPS works in airplane mode but cold fixes are slow without network assistance. Import maps with ActivityResultContracts.OpenDocument(arrayOf("*/*")) streaming into getExternalFilesDir on Dispatchers.IO (no SAF size limit), stream GPX to disk continuously, and export via CreateDocument("application/gpx+xml") or FileProvider + ACTION_SEND.

## Facts
- [verified] Latest STABLE Android Gradle Plugin is 9.4.0 (September 2026). Google Maven metadata for com.android.tools.build:gradle lists 9.4.0 as the newest non-pre-release; <latest>/<release> point to 9.5.0-alpha04 (pre-release, do not use).
  src: https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
- [verified] AGP 9.4.0 requires Gradle minimum 9.6.0 (default 9.6.0), JDK minimum 17, SDK Build Tools 36.0.0 (default), NDK default 28.2.13676358, and supports compileSdk up to API level 37.
  src: https://developer.android.com/build/releases/gradle-plugin
- [verified] Current stable Gradle is 9.7.1 (released 2026-08-19), distribution https://services.gradle.org/distributions/gradle-9.7.1-bin.zip, SHA-256 acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a. Gradle 9.7.1 satisfies AGP 9.4.0's >=9.6.0 requirement and is also the version preinstalled on ubuntu-latest.
  src: https://services.gradle.org/versions/current
- [verified] Since AGP 9.0, Kotlin support is BUILT IN (gradle property android.builtInKotlin defaults to true). You must NOT apply org.jetbrains.kotlin.android / kotlin-android in an AGP 9.x project (it is incompatible with the new DSL); com.android.application compiles Kotlin sources itself and adds kotlin-stdlib automatically. android.kotlinOptions{} is replaced by kotlin { compilerOptions { } } inside the android {} block; jvmTarget defaults to android.compileOptions.targetCompatibility so it need not be set.
  src: https://developer.android.com/build/migrate-to-built-in-kotlin
- [verified] AGP 9.0 release notes: built-in Kotlin has a runtime dependency on KGP 2.2.10; lower KGP versions are auto-upgraded; to use a higher KGP put org.jetbrains.kotlin:kotlin-gradle-plugin:<version> on the buildscript classpath. org.jetbrains.kotlin.kapt is incompatible (use com.android.legacy-kapt or KSP). Opt-out property android.builtInKotlin=false exists but the new-DSL opt-out (android.newDsl=false) is removed in AGP 10.
  src: https://developer.android.com/build/releases/agp-9-0-0-release-notes
- [verified] The AGP 9.4.0 POM declares runtime dependencies org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10 and kotlin-stdlib:2.2.10, i.e. AGP 9.4.0 still bundles Kotlin 2.2.10 for built-in Kotlin compilation unless you put a newer KGP on the classpath.
  src: https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.0/gradle-9.4.0.pom
- [verified] Latest Kotlin Gradle Plugin on Maven Central is 2.4.20 (<latest>/<release> = 2.4.20); JetBrains/kotlin GitHub 'latest release' is v2.4.10 (2026-07-14), so 2.4.20 is very new. The Android plugin id is 'org.jetbrains.kotlin.android' (still exists, but see built-in Kotlin note: do not apply it with AGP 9).
  src: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml
- [verified] Kotlin docs compatibility table: KGP 2.4.0–2.4.10 fully supports Gradle 7.6.3–9.5.0 and AGP 8.5.2–9.1.0; newer Gradle/AGP 'can be used' but may show deprecation warnings. (No row yet for 2.4.20.) This only matters if you add a newer KGP to the classpath; with the bundled KGP 2.2.10 AGP handles compatibility itself.
  src: https://kotlinlang.org/docs/gradle-configure-project.html
- [verified] Android 16 = API 36 (BAKLAVA, stable, 2025); Android 16 QPR2 = minor API 36.1; Android 17 = API 37 (CINNAMON_BUN, stable, 2026); Android 17 QPR1 = 37.1 (beta per apilevels.com). Google's SDK repository publishes non-beta platform packages platforms;android-37.0 (extension level 22), android-37.1 (ext 23), android-37.2 (ext 24) plus android-37.2-beta1..3; the base API-36 package is platforms;android-36 and the minor is android-36.1.
  src: https://apilevels.com/
- [verified] Android 17 SDK setup docs say: android { compileSdk = 37 } and defaultConfig { targetSdk = 37 }. AGP 9.4.0 lists max API 37, so compileSdk 37 / targetSdk 37 is supported. Android 16 (36) remains a fully valid, lower-risk choice.
  src: https://developer.android.com/about/versions/17/setup-sdk
- [likely] Minor-SDK compile DSL (AGP 8.9+/9.x): android { compileSdk { version = release(36) { minorApiLevel = 1 } } }; Build.VERSION.SDK_INT_FULL checks minor levels at runtime. Plain 'compileSdk = 36' Int property still works.
  src: https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/CompileSdkVersion
- [verified] ubuntu-latest currently maps to Ubuntu 24.04 (Ubuntu 26.04 is 'preview' with label ubuntu-26.04 only).
  src: https://github.com/actions/runner-images/blob/main/README.md
- [verified] Ubuntu 24.04 runner image 20260831.293.1 preinstalls: Java (Temurin) 8.0.504+1, 11.0.32+9, 17.0.20+1 (DEFAULT, JAVA_HOME_17_X64), 21.0.12+1, 25.0.4+1; Gradle 9.7.1; Kotlin 2.4.10-release-377; GitHub CLI 2.98.0; ANDROID_HOME=ANDROID_SDK_ROOT=/usr/local/lib/android/sdk; Android Command Line Tools 12.0; Build-tools 37.0.0, 36.0.0, 36.1.0, 35.0.0, 35.0.1, 34.0.0; Platform-Tools 37.0.1; Platforms android-37.2-beta3/beta2/beta1, android-37.2, android-37.1, android-37.0, android-36.1, android-36-ext19, android-36-ext18, android-36 (rev 2), android-35-ext15/ext14, android-35, android-34-ext8/12/11/10, android-34; NDK 27.3.13750724 (default), 28.2.13676358, 29.0.14206865; cmake 3.31.5 and 4.1.2.
  src: https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md
- [verified] The runner image build script installs all SDK components with `echo "y" | sdkmanager ...` (licenses accepted at image build time) and `chmod -R a+rwx ${ANDROID_SDK_ROOT}`, so the SDK dir is writable and licensed; toolset-2404.json sets platform_min_version 34, build_tools_min_version 34.0.0, java default 17.
  src: https://raw.githubusercontent.com/actions/runner-images/main/images/ubuntu/scripts/build/install-android-sdk.sh
- [verified] AGP auto-downloads missing SDK platforms/build-tools during a command-line build as long as the SDK's licenses/ directory contains accepted licenses; the feature is ON by default and disabled with gradle.properties android.builder.sdkDownload=false. It is only auto-disabled for builds started from Android Studio.
  src: https://developer.android.com/studio/intro/update#download-with-gradle
- [verified] actions/checkout latest release v7.0.1 (2026-07-20), major tag v7, runtime node24.
  src: https://api.github.com/repos/actions/checkout/releases/latest
- [verified] actions/setup-java latest release v6.0.0 (2026-08-24), major tag v6, runtime node24 (needs runner >= v2.327.1, fine on GitHub-hosted). Inputs: distribution: temurin, java-version: '17' (or '21'/'25'/'latest'); README notes GitHub-hosted runners pre-cache Temurin so temurin 17/21/25 hit the tool cache. Legacy 'adopt' distributions removed—use temurin.
  src: https://github.com/actions/setup-java/blob/main/README.md
- [verified] gradle/actions latest release v6.3.0 (2026-08-02); use gradle/actions/setup-gradle@v6 (node24). The 'gradle-version' input 'can download and install a specified Gradle version, adding this installed version to the PATH' (then run `gradle build`, not ./gradlew); aliases wrapper (default), current, release-candidate, nightly, release-nightly. Distributions are cached. Wrapper validation (validate-wrappers) is on by default and only checks wrapper jars that exist. Caching: cache-provider enhanced (default; free for public repos) or basic; cache-read-only, cache-disabled, etc.
  src: https://github.com/gradle/actions/blob/main/docs/setup-gradle.md
- [verified] A Gradle wrapper jar is NOT required if you use setup-gradle with gradle-version: '9.7.1' (or 'current') and invoke `gradle`. If you prefer ./gradlew, gradle-wrapper.jar + gradle-wrapper.properties must be committed (setup-gradle validates the jar checksum).
  src: https://github.com/gradle/actions/blob/main/docs/setup-gradle.md
- [verified] android-actions/setup-android latest release v4.0.1 (2026-04-04), runtime node24; inputs cmdline-tools-version, accept-android-sdk-licenses (default yes), log-accepted-android-sdk-licenses (default true), packages (default 'tools platform-tools'; empty string to skip). Optional on ubuntu-latest because the SDK is already present and licensed; useful only to pin cmdline-tools or force license acceptance.
  src: https://github.com/android-actions/setup-android
- [verified] gh release create synopsis: `gh release create [<tag>] [<filename>... | <pattern>...]`; flags --title/-t, --notes/-n, --notes-file/-F, --notes-from-tag, --generate-notes, --notes-start-tag, --draft/-d, --prerelease/-p, --latest (--latest=false to not mark latest), --target <branch|sha>, --verify-tag, --fail-on-no-commits, --discussion-category; asset label via 'file#Label'. `gh release upload <tag> <files>... --clobber` deletes existing same-name assets before uploading. `gh release view <tag> --json <fields>` can test existence. gh has no --clobber on create.
  src: https://cli.github.com/manual/gh_release_create
- [verified] GITHUB_TOKEN reaches gh via env GH_TOKEN: ${{ secrets.GITHUB_TOKEN }} (GitHub's own docs example). Creating a release (POST /repos/{owner}/{repo}/releases) and editing releases/assets require the 'Contents' repository permission = write, so the workflow/job needs `permissions: contents: write`. Specifying any permission scope sets all unspecified scopes to none.
  src: https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens
- [verified] Gradle version catalogs: gradle/libs.versions.toml is auto-imported as `libs`; sections [versions], [libraries] ({ module = "g:a", version.ref = "x" }), [plugins] ({ id = "...", version.ref = "x" }), [bundles]; use alias(libs.plugins.xxx) in plugins{} and `alias(libs.plugins.xxx) apply false` in the root build script.
  src: https://docs.gradle.org/current/userguide/version_catalogs.html
- [verified] Android docs sample settings.gradle.kts: pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } } dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } } rootProject.name = ...; include(":app"). Module sample applies only id("com.android.application") (no Kotlin plugin) with namespace, compileSdk, defaultConfig, buildTypes, compileOptions.
  src: https://developer.android.com/build
- [verified] View binding needs no plugin or dependency: android { buildFeatures { viewBinding = true } }; generated FooBinding.inflate(layoutInflater) / bind(view).
  src: https://developer.android.com/topic/libraries/view-binding
- [verified] AGP DSL ApkSigningConfig exposes storeFile: File?, storePassword: String?, keyAlias: String?, keyPassword: String?, storeType: String? (documented values e.g. "jks", "pkcs12"), enableV1Signing..enableV4Signing: Boolean?. Kotlin DSL: signingConfigs { create("release") { ... } } and buildTypes { release { signingConfig = signingConfigs.getByName("release") } }.
  src: https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/ApkSigningConfig
- [likely] A .p12 created with `openssl pkcs12 -export -in cert.pem -inkey key.pem -name <alias> -out keystore.p12` works as an Android signing keystore with storeType = "pkcs12" (Java's PKCS12 KeyStore reads OpenSSL 3 PBES2/AES-256 output on JDK 17). Gradle/AGP still requires a non-empty storePassword, and for PKCS12 the keyPassword must equal the storePassword (Java PKCS12 uses one password for the store and the entries).
  src: https://dev.to/surhidamatya/signing-apk-with-p12-1m8i
- [verified] Current stable AndroidX/Material versions (Google Maven metadata, September 2026): androidx.appcompat:appcompat:1.8.0; androidx.core:core-ktx:1.19.0 (core 1.19.0); androidx.activity:activity-ktx:1.13.0 (1.14.0-alpha01 is pre-release); androidx.lifecycle:lifecycle-service:2.11.0 and lifecycle-runtime-ktx:2.11.0 (2.12.0-alpha02 pre-release); androidx.preference:preference-ktx:1.2.1; androidx.constraintlayout:constraintlayout:2.2.2; com.google.android.material:material:1.14.0 (GitHub release 2026-05-13; requires minSdk >= 23); org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0 (GitHub release 2026-05-08, built against Kotlin 2.2.20).
  src: https://developer.android.com/jetpack/androidx/versions
- [verified] Foreground service manifest requirements: <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/> (API 28+, normal permission) plus, when targeting API 34+, the type permission <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/> and <service android:foregroundServiceType="location" android:exported="false"/>. Missing type in manifest => MissingForegroundServiceTypeException; missing type permission or unmet runtime prerequisites => SecurityException at startForeground().
  src: https://developer.android.com/about/versions/14/changes/fgs-types-required
- [verified] Location FGS runtime prerequisites: location services enabled AND ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION already granted BEFORE calling startForeground with FOREGROUND_SERVICE_TYPE_LOCATION, otherwise SecurityException. Location permissions are while-in-use: a location FGS cannot be started while the app is in the background unless ACCESS_BACKGROUND_LOCATION is held or an exemption applies (e.g. started from a notification/widget action). Start the service from a visible Activity after the permission grant.
  src: https://developer.android.com/develop/background-work/services/fgs/service-types
- [verified] ACCESS_BACKGROUND_LOCATION is NOT required for continued GPS access from a running foreground service: an app is considered 'in use' when an activity is visible OR it runs a foreground service with a persistent notification, and 'retains location access even when placed in the background (Home pressed or display turned off)'. Background permission is only needed for geofencing/starting location access while already in background.
  src: https://developer.android.com/develop/sensors-and-location/location/permissions
- [verified] ServiceCompat.startForeground(@NonNull Service service, int id, @NonNull Notification notification, int foregroundServiceType) (androidx.core) routes to the typed Service.startForeground on API 29+/34+ and the untyped one below 29; id must not be 0. ServiceCompat.stopForeground(service, flags) with STOP_FOREGROUND_REMOVE = 1<<0 and STOP_FOREGROUND_DETACH = 1<<1. Google's sample passes the type only when SDK_INT >= R (30) else 0.
  src: https://raw.githubusercontent.com/androidx/androidx/androidx-main/core/core/src/main/java/androidx/core/app/ServiceCompat.java
- [verified] POST_NOTIFICATIONS (API 33+) is a runtime permission but is NOT required to launch a foreground service; if denied, the FGS still runs and its notification appears only in the Task Manager, not the notification drawer. Every notification on API 26+ must target a NotificationChannel created via NotificationManager.createNotificationChannel (re-creating an existing channel is a no-op); IMPORTANCE_LOW = no sound (good for an ongoing recording notification).
  src: https://developer.android.com/develop/ui/views/notifications/notification-permission
- [verified] Android 15 (API 35) FGS changes: 6-hour/24h timeout with Service.onTimeout(int,int) applies ONLY to dataSync and mediaProcessing types, not location; BOOT_COMPLETED can't start dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone FGS (location not listed); SYSTEM_ALERT_WINDOW exemption now requires a visible overlay. Location type is unaffected.
  src: https://developer.android.com/about/versions/15/behavior-changes-15
- [verified] Android 16 (API 36): no new location/FGS-location/wake-lock restrictions; changes are health FGS type (FOREGROUND_SERVICE_TYPE_HEALTH for heart-rate sensors), JobScheduler jobs running concurrently with an FGS now count against runtime quota, scheduleAtFixedRate catch-up change. Android 17 (API 37) behavior-change pages list no FGS/location/wake-lock changes except 'background audio hardening' (audio in background requires an FGS). Google Play policy for Android 17+ session-based location asks for the 'location button' instead of persistent permission—irrelevant for a sideloaded app.
  src: https://developer.android.com/about/versions/16/behavior-changes-all
- [likely] Context.startForegroundService() (API 26+) requires the service to call startForeground() promptly (5 seconds; on API 31+ failure raises ForegroundServiceDidNotStartInTimeException); ForegroundServiceStartNotAllowedException (API 31+) is thrown when starting from an invalid background state—Google's sample wraps ServiceCompat.startForeground in try/catch for it.
  src: https://developer.android.com/develop/background-work/services/fgs/launch
- [verified] android.location.LocationManager: GPS_PROVIDER = "gps"; API 31+: requestLocationUpdates(String provider, LocationRequest request, Executor executor, LocationListener listener); legacy: requestLocationUpdates(String provider, long minTimeMs, float minDistanceM, LocationListener listener) (main-thread callbacks; Looper variant also exists); removeUpdates(LocationListener). GnssStatus callbacks: registerGnssStatusCallback(Executor, GnssStatus.Callback) API 30+, registerGnssStatusCallback(Handler, GnssStatus.Callback) API 24+ (deprecated in 30); unregisterGnssStatusCallback(callback). Requires ACCESS_FINE_LOCATION for GPS.
  src: https://developer.android.com/reference/android/location/LocationManager
- [verified] android.location.LocationRequest.Builder (API 31+): Builder(long intervalMillis); setQuality(int) with QUALITY_HIGH_ACCURACY = 100 (also QUALITY_BALANCED_POWER_ACCURACY 102, QUALITY_LOW_POWER 104); setMinUpdateIntervalMillis(long); setMinUpdateDistanceMeters(float); setIntervalMillis(long); setMaxUpdateDelayMillis(long); build().
  src: https://developer.android.com/reference/android/location/LocationRequest.Builder
- [verified] androidx.core.location.LocationListenerCompat extends android.location.LocationListener and provides default no-op implementations of onStatusChanged(String,int,Bundle), onProviderEnabled(String), onProviderDisabled(String), onLocationChanged(List<Location>) (delegates to single-location callback) and onFlushComplete(int), so on API < 30 you don't crash on the abstract legacy methods and batched updates are handled.
  src: https://raw.githubusercontent.com/androidx/androidx/androidx-main/core/core/src/main/java/androidx/core/location/LocationListenerCompat.java
- [verified] GnssStatus.Callback (API 24+): onStarted(), onStopped(), onFirstFix(int ttffMillis), onSatelliteStatusChanged(GnssStatus). GnssStatus.getSatelliteCount(), usedInFix(int i), getCn0DbHz(int i) give total/used-in-fix satellite counts for a GPS status indicator.
  src: https://developer.android.com/reference/android/location/GnssStatus.Callback
- [verified] Location getters: hasBearing()/getBearing() degrees clockwise from true north 0..360 (only while moving); hasSpeed()/getSpeed() m/s; hasAccuracy()/getAccuracy() horizontal radius in meters at 68% confidence; API 26+: hasVerticalAccuracy()/getVerticalAccuracyMeters() (68% confidence), hasBearingAccuracy()/getBearingAccuracyDegrees(), hasSpeedAccuracy()/getSpeedAccuracyMetersPerSecond(); hasAltitude()/getAltitude() meters above WGS84 ellipsoid (NOT sea level; API 34+ hasMslAltitude()/getMslAltitudeMeters()); getElapsedRealtimeNanos() monotonic timestamp; getTime() UTC ms (GPS time, fine for GPX <time>).
  src: https://developer.android.com/reference/kotlin/android/location/Location
- [likely] Airplane mode toggles only the radios listed in Settings.Global.AIRPLANE_MODE_RADIOS: RADIO_BLUETOOTH "bluetooth", RADIO_WIFI "wifi", RADIO_WIMAX "wimax", RADIO_CELL "cell", RADIO_NFC "nfc", RADIO_UWB "uwb" — there is no GPS/GNSS radio constant, so GPS_PROVIDER keeps working in airplane mode (the receiver is passive). Note the first fix will be slower without network-assisted A-GPS/SUPL data.
  src: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java
- [verified] Doze: the system suspends network access, IGNORES wake locks, defers AlarmManager alarms, doesn't perform Wi-Fi scans, blocks sync adapters and JobScheduler; GPS is not on the restriction list. Docs say a foreground service does not exempt the app from Doze ('Don't start a foreground service just to prevent the system from determining that your app is idle'), but an app with a running FGS is in the Active standby bucket with no job/alarm restrictions.
  src: https://developer.android.com/training/monitoring-device-state/doze-standby
- [unverified] Whether a PARTIAL_WAKE_LOCK is strictly required for a location FGS to keep receiving GPS fixes with the screen off is NOT documented by Google; in practice the platform's GNSS HAL/LocationManagerService wakes the device for each fix while a GPS request is active, but there are long-standing reports of inconsistent/stopped updates on some OEM builds (e.g. Google issue 63937937, Huawei/Samsung). Widely used trackers hold a PARTIAL_WAKE_LOCK inside the FGS as insurance. Google's best-practice page explicitly pairs wake locks with a foreground service notification, requires <uses-permission android:name="android.permission.WAKE_LOCK"/>, acquire(timeoutMs) and release in finally.
  src: https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices
- [verified] Battery Saver (Android 9+ AOSP defaults): 'Location services may be disabled when the screen is off'; PowerManager.getLocationPowerSaveMode() (API 28+) returns LOCATION_MODE_NO_CHANGE 0, LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF 1, LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF 2, LOCATION_MODE_FOREGROUND_ONLY 3, LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF 4. Google's location-permissions doc: on Android 12+ in LOCATION_MODE_FOREGROUND_ONLY an app 'will continue receiving location updates while in foreground or running a foreground service when Battery Saver is on, even if the screen is off'. In modes 1/2 GPS stops when the screen is off regardless of FGS; warn the user to exclude the app / disable Battery Saver when recording.
  src: https://developer.android.com/about/versions/pie/power
- [verified] ActivityResultContracts.OpenDocument: input Array<String> of MIME types, output Uri?; builds ACTION_OPEN_DOCUMENT with type */* and EXTRA_MIME_TYPES (pass arrayOf("*/*") for any file). ActivityResultContracts.CreateDocument(mimeType): input = suggested file name (EXTRA_TITLE), output Uri?. ACTION_CREATE_DOCUMENT never overwrites—same name yields 'name(1).gpx'. Read via contentResolver.openInputStream(uri) (or openFileDescriptor) on a background thread; takePersistableUriPermission only if you need long-term access. No documented file-size limit for SAF streams; OpenableColumns.SIZE may be null for remote providers.
  src: https://developer.android.com/training/data-storage/shared/documents-files
- [verified] FileProvider (androidx.core): <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths"/></provider>; <external-files-path name="..." path="."/> maps getExternalFilesDir(null). FileProvider.getUriForFile(context, authority, file) then ACTION_SEND with EXTRA_STREAM, setType("application/gpx+xml"), addFlags(FLAG_GRANT_READ_URI_PERMISSION), Intent.createChooser.
  src: https://developer.android.com/reference/androidx/core/content/FileProvider

## Gotchas
- AGP 9.x: do NOT put org.jetbrains.kotlin.android in the app module (or `alias(libs.plugins.kotlin.android)`), it fails with the new DSL. Kotlin is compiled by com.android.application itself (bundled KGP 2.2.10 in AGP 9.4.0). If you want Kotlin 2.4.x language features you must add KGP to the build classpath (root `plugins { id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false }` or buildscript classpath) — this is officially documented only for the classpath form; test before relying on it. Kotlin 2.2.10 is sufficient for a normal app.
- Pick Gradle 9.7.1 (>= AGP 9.4.0's required 9.6.0). If you use setup-gradle `gradle-version: '9.7.1'` you must run `gradle ...`, not `./gradlew` (there is no wrapper). If you commit a wrapper instead, you must commit gradle/wrapper/gradle-wrapper.jar too; setup-gradle validates its checksum and fails on a tampered jar.
- compileSdk 37: the SDK package is `platforms;android-37.0` (new minor-version naming; also installed on the runner) and AGP 9.4.0 declares API 37 as max. If anything about 37 misbehaves on first run, compileSdk = 36 / targetSdk = 36 (`platforms;android-36`, build-tools 36.0.0 — both preinstalled and AGP 9.4.0 defaults) is the zero-risk choice; Android 17 adds no FGS/location behavior changes for this app.
- AGP auto-downloads missing SDK packages only when the SDK's licenses/ dir has accepted licenses. The hosted image builds with `echo y | sdkmanager` so it is licensed and `chmod a+rwx`; android-actions/setup-android is optional. Do not set ANDROID_HOME yourself; it is /usr/local/lib/android/sdk already.
- Node 24 actions (checkout@v7, setup-java@v6, setup-gradle@v6, setup-android@v4) require runner >= 2.327.1 — fine on GitHub-hosted, matters only for self-hosted.
- Default GITHUB_TOKEN may be read-only depending on repo settings; add `permissions: contents: write` at workflow or job level. Setting any scope sets all others to none. Pass the token as `GH_TOKEN: ${{ github.token }}` for gh.
- `gh release create` fails if the release already exists; for repeatable builds check with `gh release view <tag>` and fall back to `gh release upload <tag> app.apk --clobber`. Tag the commit (or let gh create the tag with --target).
- PKCS12 via openssl: Java requires storePassword == keyPassword for PKCS12; an empty password is rejected by Gradle. Prefer `openssl pkcs12 -export ... -name <alias>` and reference the same alias in keyAlias. Alternatively generate with `keytool -genkeypair -storetype PKCS12` (JDK 17 is on the runner). Store the .p12 base64 in a secret and decode at build time; keep the signing block conditional so PRs from forks (no secrets) still build the debug APK.
- AGP 9 changed defaults: targetSdk now defaults to compileSdk if unset (set it explicitly), R8 optimized resource shrinking is on, proguard-android.txt is disallowed (use getDefaultProguardFile("proguard-android-optimize.txt")), and unit tests are only created for the debug build type.
- Material 1.14.0 and AndroidX require minSdk >= 23; API 26 (Android 8.0) is a practical minimum for this app because notification channels, startForegroundService, and Location vertical/speed/bearing accuracy all appear at 26.
- Location FGS start order: request ACCESS_FINE_LOCATION (and POST_NOTIFICATIONS on 33+) in the Activity, wait for the grant, THEN startForegroundService from the visible Activity; calling startForeground(type LOCATION) without the runtime permission throws SecurityException, without the manifest type throws MissingForegroundServiceTypeException, and starting from the background throws ForegroundServiceStartNotAllowedException / gets no location access. Restarting the FGS from a notification action is allowed (exempt).
- Do NOT request ACCESS_BACKGROUND_LOCATION; not needed for an FGS and it triggers a confusing Settings flow. Do NOT declare FOREGROUND_SERVICE_TYPE dataSync (6-hour cap on API 35+); location type has no time cap.
- POST_NOTIFICATIONS denial does not stop the FGS, but the user loses the drawer notification and its stop button; request it and explain why. Use a dedicated low-importance channel so the ongoing notification is silent.
- Doze ignores app wake locks and Battery Saver in GPS_DISABLED/ALL_DISABLED-when-screen-off modes cuts GPS regardless of your FGS. Check PowerManager.isPowerSaveMode()/getLocationPowerSaveMode() when recording starts and warn; consider asking the user to exempt the app from battery optimization (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is a Play-policy issue only, fine for sideload). Holding a PARTIAL_WAKE_LOCK inside the FGS (with a long timeout, e.g. re-acquired per session, released in onDestroy) is common OEM-quirk insurance but is undocumented as a requirement.
- Without INTERNET there is no A-GPS/SUPL assistance: cold time-to-first-fix can be 30 s to several minutes; show GnssStatus satellite counts so the rider knows the receiver is searching. GPS works in airplane mode.
- Location.getAltitude() is WGS84 ellipsoid height, not sea level (differs by tens of metres in Europe); GPX <ele> conventionally is MSL — use getMslAltitudeMeters() on API 34+ when hasMslAltitude(), else consider a geoid offset or document it.
- For a 1000 km ride keep the GPX writer streaming/appending (BufferedWriter flush every N points) inside getExternalFilesDir(); don't buffer the track in memory. Copying a 1+ GB map file through SAF is fine (stream openInputStream -> FileOutputStream in 64 KB+ buffers) but must run off the main thread (coroutine on Dispatchers.IO) and should show progress; OpenableColumns.SIZE can be null.

## Snippets
### GitHub Actions workflow: build release APK on ubuntu-latest with preinstalled SDK, JDK 17 (Temurin), Gradle 9.7.1 via setup-gradle (no wrapper needed), optional PKCS12 signing from secrets, and publish/update a GitHub Release with the APK using preinstalled gh
```
name: android

on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:
  workflow_dispatch:

permissions:
  contents: write   # needed for gh release create/upload with GITHUB_TOKEN

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v6
        with:
          gradle-version: '9.7.1'   # installs Gradle and puts it on PATH; no gradle-wrapper.jar required
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      # ubuntu-latest already has ANDROID_HOME=/usr/local/lib/android/sdk with licenses accepted,
      # platforms android-36/android-37.0 and build-tools 36.0.0; AGP auto-downloads anything else missing.

      - name: Decode signing keystore (skipped when secrets are absent, e.g. fork PRs)
        if: ${{ secrets.KEYSTORE_P12_BASE64 != '' }}
        env:
          KEYSTORE_P12_BASE64: ${{ secrets.KEYSTORE_P12_BASE64 }}
        run: echo "$KEYSTORE_P12_BASE64" | base64 -d > "$RUNNER_TEMP/release.p12"

      - name: Build
        env:
          KEYSTORE_FILE: ${{ secrets.KEYSTORE_P12_BASE64 != '' && format('{0}/release.p12', runner.temp) || '' }}
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
        run: gradle --no-daemon assembleRelease

      - uses: actions/upload-artifact@v4
        with:
          name: apk
          path: app/build/outputs/apk/release/*.apk

      - name: Publish GitHub Release
        if: startsWith(github.ref, 'refs/tags/v')
        env:
          GH_TOKEN: ${{ github.token }}
          TAG: ${{ github.ref_name }}
        run: |
          APK=$(ls app/build/outputs/apk/release/*.apk | head -n1)
          cp "$APK" "bikenav-$TAG.apk"
          if gh release view "$TAG" >/dev/null 2>&1; then
            gh release upload "$TAG" "bikenav-$TAG.apk" --clobber
          else
            gh release create "$TAG" "bikenav-$TAG.apk" --title "$TAG" --generate-notes --verify-tag
          fi

```
### settings.gradle.kts (Kotlin DSL) for a single-module app with version catalog auto-discovery
```
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BikeNav"
include(":app")

```
### gradle/libs.versions.toml with verified current stable versions (AGP 9.4.0 has built-in Kotlin; no kotlin-android plugin entry)
```
[versions]
agp = "9.4.0"
coreKtx = "1.19.0"
appcompat = "1.8.0"
material = "1.14.0"
activity = "1.13.0"
lifecycle = "2.11.0"
preference = "1.2.1"
constraintlayout = "2.2.2"
coroutines = "1.11.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
material = { module = "com.google.android.material:material", version.ref = "material" }
androidx-activity-ktx = { module = "androidx.activity:activity-ktx", version.ref = "activity" }
androidx-lifecycle-service = { module = "androidx.lifecycle:lifecycle-service", version.ref = "lifecycle" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-preference-ktx = { module = "androidx.preference:preference-ktx", version.ref = "preference" }
androidx-constraintlayout = { module = "androidx.constraintlayout:constraintlayout", version.ref = "constraintlayout" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }

```
### Root build.gradle.kts
```
plugins {
    alias(libs.plugins.android.application) apply false
    // Optional, only if you want Kotlin newer than the KGP 2.2.10 bundled with AGP 9.4.0 (puts KGP on the classpath without applying it):
    // id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
}

```
### app/build.gradle.kts: AGP 9 built-in Kotlin, ViewBinding, and PKCS12 signingConfig read from env vars with fallback to unsigned/debug-signed release when secrets are missing
```
plugins {
    alias(libs.plugins.android.application)   // Kotlin is compiled by AGP's built-in Kotlin; no org.jetbrains.kotlin.android
}

val ksFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.exists() }
val ksPassword = System.getenv("KEYSTORE_PASSWORD")
val ksAlias = System.getenv("KEY_ALIAS") ?: "release"
val hasReleaseKey = ksFile != null && !ksPassword.isNullOrBlank()

android {
    namespace = "org.example.bikenav"
    compileSdk = 36            // or 37 (Android 17); both preinstalled on ubuntu-latest

    defaultConfig {
        applicationId = "org.example.bikenav"
        minSdk = 26
        targetSdk = 36         // AGP 9 defaults targetSdk to compileSdk if unset; keep explicit
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksPassword   // PKCS12: key password must equal store password
                storeType = "pkcs12"
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17   // built-in Kotlin's jvmTarget follows this
    }

    kotlin {
        compilerOptions {
            // jvmTarget defaults to compileOptions.targetCompatibility; add flags here if needed
        }
    }

    buildFeatures {
        viewBinding = true     // no extra plugin/dependency
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
}

```
### Create a PKCS12 keystore with openssl only (no keytool) and encode it for a GitHub secret
```
openssl req -x509 -newkey rsa:4096 -sha256 -days 10950 -nodes \
  -subj "/CN=BikeNav Release" -keyout key.pem -out cert.pem
openssl pkcs12 -export -in cert.pem -inkey key.pem -name release \
  -passout pass:"$KEYSTORE_PASSWORD" -out release.p12
base64 -w0 release.p12 > release.p12.b64   # -> secret KEYSTORE_P12_BASE64; also set KEYSTORE_PASSWORD and KEY_ALIAS=release
# Optional local sanity check with the JDK: keytool -list -storetype PKCS12 -keystore release.p12

```
### AndroidManifest.xml: permissions and foreground location service (no INTERNET)
```
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-feature android:name="android.hardware.location.gps" android:required="true" />

    <application ...>
        <service
            android:name=".recording.RecordingService"
            android:foregroundServiceType="location"
            android:exported="false" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>

<!-- res/xml/file_paths.xml -->
<paths>
    <external-files-path name="tracks" path="tracks/" />
</paths>

```
### Starting the location FGS correctly: permissions first (Activity), then startForegroundService, then ServiceCompat.startForeground with FOREGROUND_SERVICE_TYPE_LOCATION
```
// In the Activity
private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecordingService()
}

fun requestAndStart() {
    val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
    permLauncher.launch(perms.toTypedArray())
}

private fun startRecordingService() {
    // must be called while this Activity is visible (while-in-use rule)
    ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START))
}

// In the Service (extends androidx.lifecycle.LifecycleService for lifecycleScope)
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        stopSelf(); return START_NOT_STICKY
    }
    createChannel()
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_rec)
        .setContentTitle(getString(R.string.recording))
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
    try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID /* != 0 */, notification,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
    } catch (e: Exception) {
        if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) { stopSelf(); return START_NOT_STICKY }
        throw e
    }
    startGps()
    return START_STICKY
}

private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch) // no-op if it exists
    }
}

```
### GPS-only updates via LocationManager (API 31+ LocationRequest.Builder, legacy overload for 26-30), LocationListenerCompat, GnssStatus callback, optional partial wake lock
```
private val lm by lazy { getSystemService(LocationManager::class.java) }
private val listener = object : LocationListenerCompat {
    override fun onLocationChanged(location: Location) { onFix(location) }
    // onProviderEnabled/Disabled, onStatusChanged, onLocationChanged(List) have default impls
}
private val gnssCallback = if (Build.VERSION.SDK_INT >= 24) object : GnssStatus.Callback() {
    override fun onSatelliteStatusChanged(status: GnssStatus) {
        var used = 0
        for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
        satellites.value = used to status.satelliteCount
    }
    override fun onFirstFix(ttffMillis: Int) { /* update UI */ }
} else null

@SuppressLint("MissingPermission") // checked before startForeground
private fun startGps() {
    val executor = ContextCompat.getMainExecutor(this)
    if (Build.VERSION.SDK_INT >= 31) {
        val req = LocationRequest.Builder(1000L)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)   // 100
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(0L)
            .build()
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, req, executor, listener)
    } else {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener) // main-thread callbacks
    }
    gnssCallback?.let { cb ->
        if (Build.VERSION.SDK_INT >= 30) lm.registerGnssStatusCallback(executor, cb)
        else lm.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
    }
    // Insurance against OEMs that stop GPS delivery with the screen off (undocumented requirement):
    wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BikeNav::Recording").apply { acquire(12 * 60 * 60 * 1000L) }
}

private fun stopGps() {
    lm.removeUpdates(listener)
    gnssCallback?.let { lm.unregisterGnssStatusCallback(it) }
    wakeLock?.takeIf { it.isHeld }?.release()
}

private fun onFix(l: Location) {
    val bearing = if (l.hasBearing()) l.bearing else null           // deg from true north, only while moving
    val speed = if (l.hasSpeed()) l.speed else null                 // m/s
    val hAcc = if (l.hasAccuracy()) l.accuracy else null            // m, 68% radius
    val vAcc = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null // API 26+
    val ele = when {
        Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude() -> l.mslAltitudeMeters
        l.hasAltitude() -> l.altitude                               // WGS84 ellipsoid height
        else -> null
    }
    val timeUtcMs = l.time
    // append <trkpt> to the GPX writer
}

```
### Battery Saver / power-save mode check before recording
```
val pm = getSystemService(PowerManager::class.java)
val saver = pm.isPowerSaveMode
val mode = if (Build.VERSION.SDK_INT >= 28) pm.locationPowerSaveMode else PowerManager.LOCATION_MODE_NO_CHANGE
val gpsWillStopWhenScreenOff = saver && (mode == PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF ||
        mode == PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF)
// LOCATION_MODE_FOREGROUND_ONLY (3) keeps updates for a running foreground service even with screen off.

```
### Import an arbitrary file (e.g. a 1+ GB map) via SAF and stream-copy it into getExternalFilesDir; export GPX via ACTION_CREATE_DOCUMENT and share via FileProvider + ACTION_SEND
```
private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    uri ?: return@registerForActivityResult
    lifecycleScope.launch(Dispatchers.IO) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "map.bin"
        val dest = File(getExternalFilesDir("maps"), name)
        contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out, 1 shl 20) } // 1 MiB buffer; report progress if desired
        }
    }
}
fun pickMapFile() = pickMap.launch(arrayOf("*/*"))   // ACTION_OPEN_DOCUMENT, type */*

private val saveGpx = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri: Uri? ->
    uri ?: return@registerForActivityResult
    lifecycleScope.launch(Dispatchers.IO) {
        contentResolver.openOutputStream(uri, "w")!!.use { out -> currentTrackFile.inputStream().use { it.copyTo(out) } }
    }
}
fun exportGpx() = saveGpx.launch("ride-2026-09-07.gpx")  // never overwrites; system appends (1)

fun shareGpx(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/gpx+xml")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, getString(R.string.share_track)))
}

```
