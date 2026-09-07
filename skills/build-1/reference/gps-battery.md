# gps-battery

## Recommendation
Recommended defaults for the cycling app (each justified by what OpenTracks, OsmAnd and Organic Maps actually ship):

1. Location source: android.location.LocationManager, GPS_PROVIDER only, via androidx LocationManagerCompat + LocationRequestCompat (interval 1000 ms, QUALITY_HIGH_ACCURACY, minUpdateDistance 0, maxUpdateDelay 0). OpenTracks (Apache-2.0, minSdk 26) and Organic Maps' native provider do exactly this; OsmAnd requests minTime 0/minDistance 0. Do not try to save power with a longer interval: GPS stays powered for any interval < ~30 s (OsmAnd docs), and a live 3D map needs 1 Hz. Never stop GPS during auto-pause (no A-GPS without internet -> slow re-acquisition).

2. Service: foreground service with foregroundServiceType="location" (+ FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS), started while the activity is visible (no ACCESS_BACKGROUND_LOCATION needed), holding a PARTIAL_WAKE_LOCK acquired with a long timeout (OpenTracks holds one for the whole recording). Show the dontkillmyapp battery-optimisation exemption dialog once (OsmAnd does).

3. Point filtering/storage: accept fix if hasAccuracy && accuracy <= 50 m (OpenTracks + OsmAnd default); use <= 25 m for distance/elevation accumulation. Reject jumps where distance > 30 m/s*dt + acc_prev + acc_new. Store when moved >= 5 m from last stored point (OsmAnd docs; OpenTracks uses 10 m — at 25 km/h that is every 1-2 s anyway), start a new <trkseg> when gap > 200 m (OpenTracks) or > 6 min (OsmAnd). Auto-pause: speed < 1.0 m/s sustained 10 s (OpenTracks idle 10 s; Organic Maps' 0.7 m/s and OsmAnd's 0.5/1.5 m/s bracket it), resume at >= 1.5 m/s; don't accumulate moving time or points while paused; ignore stationary GPS drift (displacement < 2x accuracy while slow).

4. Storage: SQLite/Room is the source of truth (both OpenTracks and OsmAnd do this, exporting GPX afterwards). 150k rows ~ 10 MB. Insert in transactions every 10 points / 5 s. Additionally keep an always-valid GPX 1.1 on disk with the footer-overwrite + fsync approach (header: version="1.1", creator, xmlns GPX/1/1, xsi:schemaLocation; trkpt lat/lon 6 decimals via Locale.ROOT, <ele> 1 decimal before <time> ISO-8601 Z). On restart, truncate any partial line and re-append the footer.

5. Stats: distance from Location.distanceTo (WGS84) at 1 Hz, haversine for bulk recomputation; elevation gain via hysteresis: barometer 3 m (OpenTracks ALTITUDE_CHANGE_DIFF_M, alpha 0.3) or GPS-only 5-10 m (Strava uses 10 m) with vertical accuracy > 20 m discarded.

6. Heading: use Location.getBearing() when hasBearing && bearing != 0 && speed > 1.5 m/s (optionally bearingAccuracy < 45 deg); keep the last GPS bearing for 3 s after slowing (Organic Maps kGpsBearingLifetimeSec) and then FREEZE the heading by default when stopped (zero sensor cost, no spinning). Optional 'compass when stopped' setting: TYPE_ROTATION_VECTOR (fallback TYPE_GEOMAGNETIC_ROTATION_VECTOR) at SENSOR_DELAY_UI (60 ms), registered only while stopped and screen on, sin/cos low-pass alpha 0.04 and a 1 deg change threshold (OsmAnd), plus GeomagneticField declination and remapCoordinateSystem for the mount orientation.

7. Camera: pitch ~50 deg while riding (Organic Maps 45-55 deg, FOV 60), ~30 deg when stopped; zoom by speed 17.5 (<10 km/h) -> 17 (20) -> 16.5 (30) -> 16 (45) -> 15.5 (70 km/h), linear interpolation like Organic Maps' table, only above 7 km/h (OsmAnd), with 0.25 zoom hysteresis, <= 0.1 zoom/s change rate, and a 10 s block after any user gesture; rider marker at 1/3 from bottom.

8. Display/power: render on demand (one frame per fix + animations, cap 30 fps), no 3D buildings/hillshade/contours (what Organic Maps' power-saving and OsmAnd's advice disable), dark map style with pure #000000 background and few labels (OLED: black pixels off; Google reported ~60% display saving at full brightness), FLAG_KEEP_SCREEN_ON only while recording AND the activity is resumed, per-window screenBrightness override with an in-app slider and auto-dim to ~0.1 after 30 s without touch, optional 'screen off, voice/vibration cues' mode. Airplane mode is safe and recommended: GNSS is not among Android's airplane-mode radios. Budget: GPS + CPU awake ~5%/h screen off (OsmAnd measures 5-6%/h; AOSP profile gps.on ~50 mA), screen on adds 6%+/h (12%/h for navigation with screen on) -> a 40 h ride requires external power for the map but recording alone can survive ~15-20 h on a 4000-5000 mAh phone.

9. Track display: precompute RDP-simplified polylines per zoom (tolerance ~2 px) and cache in SQLite; draw only the live tail raw.

## Facts
- [verified] OpenTracks license is Apache-2.0; the GitHub repo was archived on 2025-08-24 (read-only) and development moved to https://codeberg.org/OpenTracksApp/OpenTracks (latest commit seen 2026-09-03, 186 releases). build.gradle on GitHub main: versionName v4.22.0, minSdk 26, targetSdk 36, compileSdk 36; deps include androidx.core:core:1.17.0 and androidx.core:core-location-altitude:1.0.0-alpha03.
  src: https://github.com/OpenTracksApp/OpenTracks
- [verified] OpenTracks location provider: android.location.LocationManager with LocationManager.GPS_PROVIDER (no fused/Play Services). Request built as `new LocationRequestCompat.Builder(gpsInterval.toMillis()).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).setMaxUpdateDelayMillis(0)` and registered with `LocationManagerCompat.requestLocationUpdates(locationManager, LOCATION_PROVIDER, locationRequest, handler::post, this)`; no min-distance is set on the request, filtering is done client-side.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/java/de/dennisguse/opentracks/sensors/GpsManager.java
- [verified] OpenTracks concrete recording defaults (res/values/settings.xml): min_sampling_interval_default = 0 s (i.e. LocationRequest interval 0 ms = as fast as GNSS delivers, typically 1 Hz; options 0,2,3,4,5,10,20,30,60,120,300,480,900,1800 s), recording_distance_interval_default = 10 m (options 1,2,5,10,20,50,100), max_recording_distance_default = 200 m (options 50..5000; a new segment is started when exceeded), recording_gps_accuracy_default = 50 m (options 10..2000; 'Location is ignored if its accuracy is greater than %1$s'), idle_duration_default = 10 s (options 0,5,10,15,30,45,60,120; PreferencesUtils converts with Duration.ofSeconds).
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/res/values/settings.xml
- [verified] OpenTracks accuracy filter: a fix is dropped when `!trackPoint.getPosition().fulfillsAccuracy(thresholdHorizontalAccuracy)` (logs 'Ignore newTrackPoint. Poor accuracy.'); fulfillsAccuracy = hasHorizontalAccuracy() && horizontalAccuracy < threshold. Distance between points uses android Location.distanceTo (WGS84 inverse), or BLE wheel-sensor distance when available.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/java/de/dennisguse/opentracks/data/models/Position.java
- [verified] OpenTracks storage logic (TrackRecordingManager.onNewTrackPoint): first point of a segment is always stored; a point is stored when distanceToLastStoredTrackPoint >= recordingDistanceInterval; if distance > maxRecordingDistance the point becomes TrackPoint.Type.SEGMENT_START_AUTOMATIC (new <trkseg>); sensor-only points are stored at most every hard-coded `Duration.ofSeconds(10)` (minStorageInterval); an unsaved 'lastTrackPoint' is inserted before a triggering point so the last position is not lost.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/java/de/dennisguse/opentracks/services/TrackRecordingManager.java
- [verified] OpenTracks idle/auto-pause: a Handler timer `handler.postDelayed(ON_IDLE, idleDuration.toMillis())` is (re)scheduled every time a point is stored (i.e. after moving >= recordingDistanceInterval); if no stored point arrives for idleDuration (default 10 s) onIdle() inserts a TrackPoint.Type.IDLE point and notifies the IdleObserver (TrackRecordingService announces idle via voice). idleDuration 0 disables. Moving time is not accumulated while the segment is idle; it resumes when movingDistance >= recordingDistanceInterval. GPS is NOT switched off while idle.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/java/de/dennisguse/opentracks/services/TrackRecordingManager.java
- [verified] OpenTracks foreground service: TrackRecordingService uses foregroundServiceType 'location|connectedDevice' (ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION + FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE). Manifest permissions: FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION (minSdk 34), FOREGROUND_SERVICE_CONNECTED_DEVICE (34), ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION, WAKE_LOCK, VIBRATE, POST_NOTIFICATIONS (33), Bluetooth permissions. No INTERNET permission.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/AndroidManifest.xml
- [verified] OpenTracks holds a wake lock for the whole recording: SystemUtils.acquireWakeLock creates `powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)` and calls `wakeLock.acquire()` with no timeout (@SuppressLint("WakelockTimeout")); it is acquired when sensors start and released on stop.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/java/de/dennisguse/opentracks/util/SystemUtils.java
- [verified] OpenTracks GPS status: GpsStatusManager.SIGNAL_LOST_THRESHOLD = Duration.ofSeconds(30) (+ sampling interval) marks GPS_SIGNAL_LOST; horizontal accuracy worse than the recording distance interval is treated as GPS_SIGNAL_BAD.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/java/de/dennisguse/opentracks/sensors/GpsStatusManager.java
- [verified] OpenTracks GPX export (GPXTrackExporter): writes `<?xml version="1.0" encoding="UTF-8"?>`, `<gpx version="1.1" creator="..." xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2" xmlns:opentracks="http://opentracksapp.com/xmlschemas/v1" ...>`; trkpt lat/lon via DecimalFormat with 6 fraction digits, grouping off; `<ele>` with 1 fraction digit; `<time>` ISO-8601; a new <trkseg> at SEGMENT_START_AUTOMATIC. Export is done afterwards from the SQLite ContentProvider via a TrackPointIterator, not written live during recording.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/java/de/dennisguse/opentracks/io/file/exporter/GPXTrackExporter.java
- [verified] OpenTracks barometric elevation gain: PressureSensorUtils ALTITUDE_CHANGE_DIFF_M = 3.0f (a change is only counted once the smoothed altitude moved >= 3 m, capped to +/-3 m per step), EXPONENTIAL_SMOOTHING = 0.3f, p0 = SensorManager.PRESSURE_STANDARD_ATMOSPHERE, altitude via SensorManager.getAltitude (barometric formula p(h)=p0*(1-0.0065h/T0)^5.255). TrackStatisticsUpdater prefers barometer altitude gain (trackPoint.hasAltitudeGain()) over GPS altitude; GPS altitude only updates min/max extremities.
  src: https://raw.githubusercontent.com/OpenTracksApp/OpenTracks/main/src/main/java/de/dennisguse/opentracks/sensors/PressureSensorUtils.java
- [verified] Organic Maps power saving (libs/map/power_management): Scheme {None, Normal, EconomyMedium, EconomyMaximum, Auto}; Facilities {Buildings3d, PerspectiveView, TrackRecording, TrafficJams, GpsTrackingForTraffic, OsmEditsUploading, UgcUploading, BookmarkCloudUploading, MapDownloader}. EconomyMedium disables PerspectiveView and BookmarkCloudUploading; EconomyMaximum disables everything except MapDownloader (incl. 3D buildings, perspective view, track recording). Auto: battery < 20% -> EconomyMaximum, < 30% -> EconomyMedium, else Normal (PowerManager::OnBatteryLevelReceived). UI strings: 'Power saving mode' = Never / 'When battery is low' / 'Always'; '3D buildings are disabled in power saving mode'.
  src: https://raw.githubusercontent.com/organicmaps/organicmaps/master/libs/map/power_management/power_management_schemas.cpp
- [verified] Organic Maps Android UI has 'Keep the screen on' (enable_keep_screen_on: 'When enabled, the screen will always be on when displaying the map.'), 'Perspective view', '3D buildings', 'Auto zoom', 'Night Mode' map style and 'Night style when navigating in the dark' (pref_auto_night_in_navigation_title).
  src: https://raw.githubusercontent.com/organicmaps/organicmaps/master/android/app/src/main/res/values/strings.xml
- [verified] Organic Maps Android location: AndroidNativeProvider uses LocationManagerCompat + `new LocationRequestCompat.Builder(interval).setQuality(QUALITY_HIGH_ACCURACY)` on every enabled provider except PASSIVE_PROVIDER; LocationHelper intervals: INTERVAL_FOLLOW_MS = 100, INTERVAL_NAVIGATION_MS = 100, INTERVAL_TRACK_RECORDING = 100, INTERVAL_NOT_FOLLOW_MS = 3000, LOCATION_UPDATE_TIMEOUT_MS = 30 s, AGPS_EXPIRATION_TIME_MS = 16 h. LocationUtils.isAccuracySatisfied accepts any GPS-provider fix (accuracy may be 0 on some devices) and requires accuracy > 0 for other providers; isLocationBetterThanLast uses a speed-based plausibility bound (newAccuracy < lastAccuracy + speed*dt).
  src: https://raw.githubusercontent.com/organicmaps/organicmaps/master/android/sdk/src/main/java/app/organicmaps/sdk/location/LocationHelper.java
- [verified] Organic Maps heading fusion (libs/drape_frontend/my_position_controller.cpp): kMinSpeedThresholdMps = 0.7 ('for the pedestrian mode 2.5 km/h'), kGpsBearingLifetimeSec = 3.0, kMaxUpdateLocationInvervalSec = 30, kMaxBlockAutoZoomTimeSec = 10, kZoomThreshold = 10, kMaxScaleZoomLevel = 16, kDefaultAutoZoom = 16. GPS bearing is applied when `(!m_isCompassAvailable || glueArrowInRouting || isMovingFast) && info.HasBearing()` where isMovingFast = HasSpeed && speed > 0.7 m/s; compass updates are ignored while `m_lastGPSBearingTimer.ElapsedSeconds() < kGpsBearingLifetimeSec` (3 s), so heading falls back to compass only after 3 s without a fast GPS fix.
  src: https://raw.githubusercontent.com/organicmaps/organicmaps/master/libs/drape_frontend/my_position_controller.cpp
- [verified] Organic Maps auto-zoom by speed (CalculateZoomBySpeed, meters-per-pixel vs km/h, linear interpolation): 3D/perspective table {20:0.25, 40:0.75, 60:1.50, 75:2.50, 85:3.75, 95:6.00}; 2D table {20:0.70, 40:1.25, 60:2.25, 75:3.00, 85:3.75, 95:6.00}; default speed 80 km/h if unknown; result divided by visual scale. Perspective (3D) constants in libs/geometry/screenbase.cpp: kPerspectiveAngleFOV = pi/3 (60 deg), kMaxPerspectiveAngle1 = pi/4 (45 deg), kMaxPerspectiveAngle2 = 55 deg, perspective auto-enabled between kStartPerspectiveScale1 = 1.7e-5 and kEndPerspectiveScale1 = 0.3e-5 / kEndPerspectiveScale2 = 0.13e-5.
  src: https://raw.githubusercontent.com/organicmaps/organicmaps/master/libs/geometry/screenbase.cpp
- [verified] Organic Maps compass (SensorHelper.java): registers Sensor.TYPE_ROTATION_VECTOR, falling back to TYPE_GEOMAGNETIC_ROTATION_VECTOR, at SensorManager.SENSOR_DELAY_UI; uses getRotationMatrixFromVector + getOrientation and corrects for display rotation (LocationUtils.correctCompassAngle); listener is unregistered when not needed.
  src: https://raw.githubusercontent.com/organicmaps/organicmaps/master/android/sdk/src/main/java/app/organicmaps/sdk/location/SensorHelper.java
- [verified] Organic Maps maintainer on all-day battery: 'You won't see your precise (GPS-based) location on the map without High Accuracy setting' and 'You won't get reliably recorded tracks with Battery Save mode enabled, and without adding OM to the list of exceptions, as described in https://dontkillmyapp.com'. No measured mAh figures given.
  src: https://github.com/orgs/organicmaps/discussions/12530
- [verified] OsmAnd documented typical battery use on a mid-level device: 'Screen on (typically) 6%' per hour, 'GPS active (typically) 5%' per hour, 'Track recording with screen off 6%' per hour, 'Navigation with screen on 12%' per hour. Recommendations: configure 'Screen control during navigation' to keep display off except at turns, reduce GPS accuracy/map redraw frequency, disable 3D relief/contour layers, turn off auto zoom (prevents frequent redraws).
  src: https://osmand.net/docs/user/troubleshooting/general/
- [verified] OsmAnd track-recording battery doc: 'Effect on battery use (order of magnitude) seems about 5% per hour on older systems up to Android 4.4, and 2-3% for newer systems'; 'For intervals >=30sec, we turn GPS on only for each sampling point', which 'reduces battery usage to an order of magnitude 1.2% per hour for 30-second track recording'. For intervals <= 15 s GPS stays continuously on. Background recording works without 'Always allow' location because a foreground-service notification is shown.
  src: https://osmand.net/docs/user/troubleshooting/track-recording-issues/
- [verified] OsmAnd trip-recording defaults (OsmandSettings.java): SAVE_TRACK_INTERVAL default 5000 ms (CAR 3000, BICYCLE 5000, PEDESTRIAN 10000), SAVE_TRACK_MIN_DISTANCE 0 m, SAVE_TRACK_PRECISION 50.0 m (max accepted accuracy), SAVE_TRACK_MIN_SPEED 0, AUTO_SPLIT_RECORDING true; SavingTrackHelper.shouldRecordLocation requires time since last > interval, distance >= minDistance (if >0), accuracy <= precision, speed >= minSpeed; DB->GPX split: same segment if gap < 6 min, new segment if < 2 h, new track otherwise; one GPX file per day (yyyy-MM-dd). Docs recommend 'Minimum displacement' 5 m over a min-speed filter. OsmAndLocationProvider.ACCURACY_FOR_GPX_AND_ROUTING = 50.
  src: https://raw.githubusercontent.com/osmandapp/OsmAnd/master/OsmAnd/src/net/osmand/plus/settings/backend/OsmandSettings.java
- [verified] OsmAnd GPS request: AndroidApiLocationServiceHelper calls `locationManager.requestLocationUpdates(GPS_PROVIDER, 0, 0, listener)` (minTime 0, minDistance 0) and additionally subscribes to non-GPS providers; a GitHub issue reports OsmAnd waking the CPU every second even with a 30 s recording interval (#9097, Android 10/LineageOS).
  src: https://raw.githubusercontent.com/osmandapp/OsmAnd/master/OsmAnd/src/net/osmand/plus/helpers/AndroidApiLocationServiceHelper.java
- [verified] OsmAnd heading rules (MapViewTrackingUtilities.java): isSmallSpeedForCompass = !hasSpeed || speed < 0.5 m/s; isSmallSpeedForAnimation = !hasSpeed || NaN || speed < 1.5 m/s; in ROTATE_MAP_BEARING mode the map rotates to -location.getBearing() only if hasBearing() && bearing != 0f ('special case when bearing equals to zero (we don't change anything)'); compass sensor is registered/unregistered depending on smallSpeedForCompass; COMPASS_HEADING_THRESHOLD = 1.0 deg before a heading change is applied; ROTATE_MAP default MANUAL, CAR default BEARING. Compass filtering in OsmAndLocationProvider: sensors at SENSOR_DELAY_UI; Kalman-style low-pass KALMAN_COEFFICIENT = 0.04 on sin/cos of heading (USE_KALMAN_FILTER_FOR_COMPASS default true), else 50-sample rolling average; TYPE_ORIENTATION -> TYPE_ROTATION_VECTOR fallback, or accelerometer+magnetometer if USE_MAGNETIC_FIELD_SENSOR_COMPASS.
  src: https://raw.githubusercontent.com/osmandapp/OsmAnd/master/OsmAnd/src/net/osmand/plus/base/MapViewTrackingUtilities.java
- [verified] OsmAnd auto-zoom/3D: AUTO_ZOOM_MAP default false (CAR true, BICYCLE false); AUTO_ZOOM_MAP_SCALE CAR=FAR, BICYCLE/PEDESTRIAN=CLOSE; AutoZoomMap enum FARTHEST(coef 1.0, maxZoom 16, minDist 400), FAR(1.4, 17, 200), CLOSE(2.0, 19, 50); AutoZoomBySpeedHelper: MIN_AUTO_ZOOM_SPEED = 7/3.6 m/s (~1.94 m/s), time window 60 s (<83 km/h) else 75 s, distToSee = speed*time/coefficient, ZOOM_PER_SECOND = 0.1, MIN_ZOOM_DURATION_MILLIS = 1500, SHOW_DRIVING_SECONDS_V2 = 45; AUTO_ZOOM_3D_ANGLE default 25 (elevation angle from horizon; OsmandMapTileView DEFAULT_ELEVATION_ANGLE = 90 = top-down, MIN_ALLOWED_ELEVATION_ANGLE = 10).
  src: https://raw.githubusercontent.com/osmandapp/OsmAnd/master/OsmAnd/src/net/osmand/plus/views/AutoZoomBySpeedHelper.java
- [verified] OsmAnd 'Screen control' (Android only, Menu > Configure profile > General settings > Screen control): Screen timeout = 'Use system screen timeout' or 'Timeout after wake-up' 5-60 s; Turn screen on via 'Proximity sensor' (wave hand), 'Navigation instructions' (each instruction turns screen on), 'Power button' (OsmAnd over lock screen); settings TURN_SCREEN_ON_TIME_INT default 0, TURN_SCREEN_ON_SENSOR false, TURN_SCREEN_ON_NAVIGATION_INSTRUCTIONS false, TURN_SCREEN_ON_POWER_BUTTON false.
  src: https://osmand.net/docs/user/navigation/guidance/voice-navigation/
- [verified] AOSP power_profile example values (docs, per-OEM actual values differ): screen.on 'Additional power used when screen is turned on at minimum brightness' ~200 mA; screen.full 'Additional power used when screen is at maximum brightness' 100-300 mA; gps.on 'Additional power used when GPS is acquiring a signal' ~50 mA; gps.signalqualitybased 30 mA / 10 mA; radio.active (cellular tx/rx) 100-300 mA; radio.scanning 1.2 mA; wifi.on 2 mA; wifi.active 31 mA; wifi.scan 100 mA; bluetooth.active listed; cpu.idle (suspend) 3 mA; cpu.awake 50 mA.
  src: https://source.android.com/docs/core/power/values
- [verified] Carroll & Heiser, 'An Analysis of Power Consumption in a Smartphone' (USENIX ATC 2010, Openmoko Freerunner, component-level measurements): GPS module 143.1 mW (internal antenna), 166.1 mW (external antenna), 0 when disabled; power did not drop after acquisition ('should only be considered worst-case'); suspended baseline 68.6 mW (GSM ~45% of it); idle awake with backlight off 268.8 mW; LCD backlight 7.8 mW (min) to 414 mW (max), 75 mW at mid slider (level 143); LCD content: 33.1 mW white vs 74.2 mW black on that LCD (up to 43 mW effect); web browsing 352.8 mW WiFi / 429.0 mW GPRS excl. backlight; GSM call ~832-1054 mW; Nexus One OLED power is content dependent (no separate backlight); G1 LCD content affects up to 17 mW.
  src: https://www.usenix.org/legacy/event/atc10/tech/full_papers/Carroll.pdf
- [verified] Airplane mode does not disable GNSS on Android: AOSP SettingsProvider defaults `def_airplane_mode_radios = "cell,bluetooth,uwb,wifi,wimax"` and `def_airplane_mode_toggleable_radios = "bluetooth,wifi"`; neither contains gps/location. GPS receivers are receive-only so airplane mode + location on = GPS works (secondary sources agree).
  src: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/packages/SettingsProvider/res/values/defaults.xml
- [verified] Android Location API javadoc (AOSP android-14.0.0_r1): getBearing(): 'Returns the bearing at the time of this location in degrees. Bearing is the horizontal direction of travel of this device and is unrelated to the device orientation.'; getBearingAccuracyDegrees(), getSpeedAccuracyMetersPerSecond(), getAccuracy(), getVerticalAccuracyMeters() are 68th-percentile (1-sigma) estimates; getSpeed() 'may be more accurate than would be obtained simply by calculating distance / time'; getElapsedRealtimeNanos() is monotonic since boot (use it, not getTime(), for dt); isMock() flags test-provider fixes; distanceTo()/distanceBetween() use the WGS84 ellipsoid inverse formula (Vincenty-type).
  src: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/location/java/android/location/Location.java
- [verified] Android platform LocationRequest (API 31+; androidx LocationRequestCompat back-ports it): Builder(intervalMillis); setQuality QUALITY_HIGH_ACCURACY(100)/QUALITY_BALANCED_POWER_ACCURACY(102)/QUALITY_LOW_POWER(104) 'a hint to providers on how they should weigh power vs accuracy'; setMinUpdateIntervalMillis; setMinUpdateDistanceMeters ('If a potential location update is closer to the last location update than the minimum update distance, then the potential location update will not occur'); setMaxUpdateDelayMillis enables batching; PASSIVE_INTERVAL for passive-only requests.
  src: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/location/java/android/location/LocationRequest.java
- [verified] Android battery guidance for location: three factors are accuracy, frequency, latency; 'Reserve high accuracy for apps that run in the foreground and require real time location updates (for example, a mapping app)'; 'Reserve intervals of a few seconds for foreground use cases'; pass the largest possible setMaxUpdateDelayMillis to batch when latency is acceptable.
  src: https://developer.android.com/develop/sensors-and-location/location/battery
- [verified] Foreground service type 'location' (android:foregroundServiceType="location", FOREGROUND_SERVICE_TYPE_LOCATION): manifest needs FOREGROUND_SERVICE_LOCATION (API 34+), runtime needs ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION and location services enabled; 'you cannot create a location foreground service while your app is in the background, unless you've been granted the ACCESS_BACKGROUND_LOCATION runtime permission' (start it while the activity is visible).
  src: https://developer.android.com/develop/background-work/services/fgs/service-types
- [verified] Keep screen on: 'set the FLAG_KEEP_SCREEN_ON flag in your activity. This flag may only be set in an activity, never in a service'; 'If an app with the FLAG_KEEP_SCREEN_ON flag goes into the background, the system allows the screen to turn off normally'; clear with window.clearFlags(...). Android docs warn 'Keeping the device's screen on can drain the battery quickly'. Equivalent XML: android:keepScreenOn="true".
  src: https://developer.android.com/develop/background-work/background-tasks/awake/screen-on
- [verified] WindowManager.LayoutParams (AOSP source): FLAG_KEEP_SCREEN_ON = 0x00000080 'as long as this window is visible to the user, keep the device's screen turned on and bright'; `public float screenBrightness = BRIGHTNESS_OVERRIDE_NONE` — 'override the user's preferred brightness of the screen. A value of less than 0, the default, means to use the preferred screen brightness. 0 to 1 adjusts the brightness from dark to full bright.'; BRIGHTNESS_OVERRIDE_NONE = -1.0f, BRIGHTNESS_OVERRIDE_OFF = 0.0f (lowest value), BRIGHTNESS_OVERRIDE_FULL = 1.0f; applies only while this window is in front (no WRITE_SETTINGS needed).
  src: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/WindowManager.java
- [verified] Wake locks: 'Creating and holding wake locks can have a dramatic impact on the device's battery life'; use PARTIAL_WAKE_LOCK via newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyClass::Tag").acquire(timeoutMs); hold it in a foreground service (user-visible), same tag every time, release in finally.
  src: https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices
- [verified] Sensors: SENSOR_DELAY_NORMAL = 200,000 us, SENSOR_DELAY_UI = 60,000 us, SENSOR_DELAY_GAME = 20,000 us, SENSOR_DELAY_FASTEST = 0; Sensor.getPower() returns 'the power in mA used by this sensor while in use'; 'choose the slowest sampling rate that still meets the needs of your application'; always unregister listeners when paused; apps targeting API 31+ are capped at 200 Hz. TYPE_GEOMAGNETIC_ROTATION_VECTOR 'doesn't use the gyroscope... accuracy is lower... but the power consumption is reduced'; TYPE_GAME_ROTATION_VECTOR ignores magnetic north (drifts). Use getRotationMatrixFromVector + getOrientation (+ remapCoordinateSystem for screen rotation). GeomagneticField(lat, lon, altM, timeMillis).getDeclination() gives magnetic->true north correction (positive = east).
  src: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- [verified] GPX 1.1 schema (gpx.xsd): targetNamespace http://www.topografix.com/GPX/1/1, elementFormDefault qualified; <gpx> requires version="1.1" (fixed) and creator; child order metadata?, wpt*, rte*, trk*, extensions?; trk: name, cmt, desc, src, link, number, type, extensions, trkseg*; trkseg: trkpt*, extensions?; trkpt (wptType) attributes lat, lon and children in this order: ele, time, magvar, geoidheight, name, cmt, desc, src, link, sym, type, fix, sat, hdop, vdop, pdop, ageofdgpsdata, dgpsid, extensions (all optional).
  src: https://www.topografix.com/GPX/1/1/gpx.xsd
- [verified] Strava elevation-gain threshold: 'climbing to occur consistently for more than 10 meters for activities without strong barometric data, or 2 meters for activities with barometric data' before it is added; more smoothing on non-barometric data.
  src: https://support.strava.com/en-us/articles/15402093-elevation-on-strava-faqs
- [verified] Ramer-Douglas-Peucker: recursive split at the point farthest from the chord, keep if distance > epsilon; expected O(n log n), worst case O(n^2); O(n log n) achievable with convex-hull structures.
  src: https://en.wikipedia.org/wiki/Ramer%E2%80%93Douglas%E2%80%93Peucker_algorithm
- [likely] Google's Android Dev Summit 2018 talk reported OLED (2016 Pixel) power depends on color (white highest, then blue, green, red; black lowest) and that YouTube dark mode saved roughly 60% display power at 100% brightness (other outlets report 43% at full and 14% at 50% brightness). Exact slide numbers only available via secondary press coverage (The Verge/Android Authority/TrustedReviews).
  src: https://www.androidauthority.com/google-dark-mode-battery-923359/
- [unverified] Older survey-type numbers: a phone GPS receiver ~320 mW average and 'nine to thirteen times the idle rate' (ResearchGate/arXiv secondary summaries); treat as order-of-magnitude only.
  src: https://www.researchgate.net/publication/260359199_Location-Based_Services_on_Mobile_Phones_Minimizing_Power_Consumption
- [unverified] OpenTracks README states ACCESS_BACKGROUND_LOCATION is 'required to start recording with GPS while phone is in standby', but the manifest fetched from GitHub main did not list that permission in the extraction; verify before copying OpenTracks' permission set.
  src: https://codeberg.org/OpenTracksApp/OpenTracks

## Gotchas
- Sampling interval < ~30 s does NOT save GPS power: the receiver stays powered continuously (OsmAnd only cycles GPS off for intervals >= 30 s). For a live moving map you need 1 Hz anyway, so request 1000 ms and do all thinning client-side (both OpenTracks and OsmAnd request 0 ms / minDistance 0 and filter afterwards).
- No internet means no A-GPS/XTRA assistance: cold time-to-first-fix can be 30 s to several minutes, and every GPS off/on cycle risks a slow re-acquisition. Keep the receiver on during auto-pause (OpenTracks does); only stop GPS when the user stops recording. Organic Maps re-injects AGPS data every 16 h, which you cannot do without INTERNET.
- Location.getBearing() is direction of travel, not device heading; at rest it is stale or noisy and hasBearing() may flip. OsmAnd additionally treats bearing == 0f as 'no bearing'. Gate GPS bearing on speed (OM 0.7 m/s, OsmAnd 0.5/1.5 m/s) and hold the last bearing for ~3 s (OM kGpsBearingLifetimeSec) instead of snapping to compass.
- Use getElapsedRealtimeNanos() for dt/speed computations, not getTime() (wall clock can jump, GNSS time vs system time differ); check isMock() to reject fake-GPS apps if you care.
- Android 14+: declare FOREGROUND_SERVICE_LOCATION and foregroundServiceType="location"; the service must be started while the activity is visible unless you hold ACCESS_BACKGROUND_LOCATION. Add POST_NOTIFICATIONS (API 33) for the mandatory notification. Users must exempt the app from OEM battery optimizations (dontkillmyapp.com) or long rides get killed.
- FLAG_KEEP_SCREEN_ON is activity-only and is auto-released when the app goes to background; screenBrightness override only applies while your window is in front and 0.0 means 'lowest', not off. Some OEM ROMs clamp or ignore the override; always give the user a manual brightness slider in-app.
- OLED: only true #000000 pixels are off; dark grey still draws current. Blue sub-pixels are least efficient; prefer black background with dim red/amber/white-ish labels. Google's 60% number is at 100% brightness; at 50% brightness the saving is closer to 14%. LCD phones gain nothing from black.
- Screen dominates when it is on: AOSP profile examples put screen.on+screen.full at ~200-500 mA vs gps.on ~50 mA; OsmAnd docs: 'GPS active ~5%/h' vs 'navigation with screen on 12%/h'. A 40 h ride cannot be done on one charge with the screen on; design for screen-off recording (foreground service + partial wake lock) and on-demand map viewing, and assume external power for the map.
- Map redraw is the second-largest cost: OsmAnd docs explicitly recommend disabling auto-zoom and reducing redraw frequency; Organic Maps' power-saving disables 3D buildings and perspective view. Render on demand (one frame per fix plus short animations), cap at 30 fps, disable 3D buildings/hillshade, use a dark style with few labels.
- Wake lock: OpenTracks holds a PARTIAL_WAKE_LOCK without timeout for the whole recording; Android docs say acquire(timeout). Use a long timeout (e.g. 8 h) and re-acquire on each fix batch; keep the same tag; release in finally. A 1 Hz GPS callback already keeps the CPU awake ~1 s each second, so the wake lock mainly prevents doze from suspending between fixes.
- GPS drift while stationary is 2-10 m: a pure distance filter still records a 'cloud' when stopped, and a pure speed filter records slow rolling. Combine: store when moved >= 5 m AND (speed >= 1 m/s OR displacement > 2x accuracy); auto-pause when speed < 1 m/s for 10 s (OpenTracks idle 10 s). Do not count moving time while paused.
- Accuracy cutoff has to tolerate forest/canyon: OpenTracks and OsmAnd both default to 50 m. Distance summation with 50 m points inflates distance; use a stricter threshold (e.g. 25 m) for distance/elevation accumulation than for storage, or weight by accuracy.
- GPS altitude noise is +/-10-20 m: naive summing of deltas inflates gain massively. Use barometer with 3 m hysteresis (OpenTracks) when present; for GPS-only use a >= 5-10 m sustained-change threshold (Strava uses 10 m) after median/exponential smoothing, and ignore points with getVerticalAccuracyMeters() > ~20 m.
- Number formatting: use Locale.ROOT / DecimalFormat with grouping off for lat/lon (6 decimals ~0.1 m) and ele (1 decimal); locale decimal commas produce invalid GPX. Element order matters: <ele> before <time> inside <trkpt>; time as ISO-8601 UTC 'Z'. Put the xsi:schemaLocation on the root or validators complain.
- Writing GPX live is fragile (a crash mid-write leaves an unterminated file). Both OpenTracks and OsmAnd store points in SQLite and export GPX later. If you also want an always-valid GPX on disk, use the footer-overwrite trick (append points, then re-append '</trkseg></trk></gpx>', and truncate the footer before the next batch) with FileOutputStream.getFD().sync().
- LocationRequest (platform) is API 31+; use androidx.core LocationManagerCompat/LocationRequestCompat (core 1.17.0 works, OpenTracks/Organic Maps both do) for minSdk 26.
- Location.distanceTo/distanceBetween is a WGS84 ellipsoid inverse (heavier than haversine but fine at 1 Hz). For bulk recomputation over 150k points in the DB haversine is adequate (<0.5% error).
- Auto-zoom fights user gestures: Organic Maps blocks auto-zoom for 10 s after a user zoom (kMaxBlockAutoZoomTimeSec) and Organic Maps/OsmAnd rate-limit zoom changes (OsmAnd ZOOM_PER_SECOND = 0.1, min 1.5 s). Add hysteresis so zoom does not oscillate around speed thresholds.
- Rotation vector (TYPE_ROTATION_VECTOR) uses the gyroscope and sensor-fusion CPU; keep it registered only while stopped and the screen is on, at SENSOR_DELAY_UI, and unregister on pause. Magnetometer needs declination (GeomagneticField) and is disturbed by handlebar mounts/magnets (dynamo hubs!) — a course-up map that never rotates when stopped is a legitimate low-power default.
- OsmAnd issue #9097: CPU woke every second despite a 30 s interval — schedule nothing on a fast timer; drive everything from the location callback and coalesce DB writes into transactions every ~5-10 s.
- OpenTracks GitHub repository is archived (2025-08-24); read current code on Codeberg. Its defaults are tuned for running/hiking (10 m distance interval); cycling covers 7 m/s at 25 km/h so 10 m filter ~ every 1.4 s.

## Snippets
### AndroidManifest.xml permissions and foreground service declaration (no INTERNET, no Play Services) — mirrors OpenTracks
```
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-feature android:name="android.hardware.location.gps" android:required="true" />

<application ...>
  <service
      android:name=".record.RecordingService"
      android:exported="false"
      android:foregroundServiceType="location" />
</application>
```
### Foreground service: 1 Hz GPS via LocationManagerCompat (androidx.core:core:1.17.0), partial wake lock with timeout, service type LOCATION
```
class RecordingService : Service(), LocationListenerCompat {
    private lateinit var lm: LocationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideTracker::Recording")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(1, notification)

        wakeLock.acquire(8 * 60 * 60 * 1000L) // re-armed periodically
        val request = LocationRequestCompat.Builder(1000L)          // 1 Hz: GNSS native rate
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0f)                        // filter ourselves
            .setMaxUpdateDelayMillis(0L)                           // no batching: live map
            .build()
        if (LocationManagerCompat.hasProvider(lm, LocationManager.GPS_PROVIDER)) {
            LocationManagerCompat.requestLocationUpdates(
                lm, LocationManager.GPS_PROVIDER, request, handler::post, this)
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) { tracker.onFix(location) }

    override fun onDestroy() {
        LocationManagerCompat.removeUpdates(lm, this)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
```
### Point acceptance, jump rejection, distance filter and auto-pause (defaults derived from OpenTracks/OsmAnd/Organic Maps)
```
object Defaults {
    const val MAX_ACCURACY_STORE_M = 50f      // OpenTracks + OsmAnd default
    const val MAX_ACCURACY_STATS_M = 25f      // stricter for distance/elevation sums
    const val MIN_DISTANCE_M = 5f             // OsmAnd docs recommendation; OpenTracks 10 m
    const val NEW_SEGMENT_GAP_M = 200f        // OpenTracks maxRecordingDistance
    const val NEW_SEGMENT_GAP_S = 6 * 60      // OsmAnd segment split
    const val MAX_PLAUSIBLE_SPEED_MPS = 30f   // 108 km/h; bikes rarely exceed ~25 m/s
    const val PAUSE_SPEED_MPS = 1.0f          // 3.6 km/h (OM 0.7, OsmAnd 0.5/1.5)
    const val PAUSE_AFTER_S = 10              // OpenTracks idle_duration_default
    const val RESUME_SPEED_MPS = 1.5f
}

class Tracker(private val db: TrackDao) {
    private var last: Location? = null
    private var lastStored: Location? = null
    private var slowSinceNs = -1L
    var paused = false; private set

    fun onFix(loc: Location) {
        if (loc.isMock) return
        if (!loc.hasAccuracy() || loc.accuracy > Defaults.MAX_ACCURACY_STORE_M) return
        val prev = last
        if (prev != null) {
            val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            if (dt <= 0) return
            val d = prev.distanceTo(loc)
            // jump filter: distance must be explainable by speed + both error radii
            if (d > Defaults.MAX_PLAUSIBLE_SPEED_MPS * dt + prev.accuracy + loc.accuracy) return
        }
        last = loc

        val speed = if (loc.hasSpeed()) loc.speed else prev?.let { it.distanceTo(loc) /
            ((loc.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1e9f) } ?: 0f
        updatePause(loc, speed)

        val ls = lastStored
        val moved = ls == null || ls.distanceTo(loc) >= Defaults.MIN_DISTANCE_M
        val stationaryNoise = ls != null && ls.distanceTo(loc) < 2 * loc.accuracy && speed < Defaults.PAUSE_SPEED_MPS
        if (!paused && moved && !stationaryNoise) {
            val gap = ls != null && (ls.distanceTo(loc) > Defaults.NEW_SEGMENT_GAP_M ||
                (loc.elapsedRealtimeNanos - ls.elapsedRealtimeNanos) / 1e9 > Defaults.NEW_SEGMENT_GAP_S)
            db.insert(loc.toPoint(newSegment = gap))
            lastStored = loc
        }
    }

    private fun updatePause(loc: Location, speed: Float) {
        if (speed < Defaults.PAUSE_SPEED_MPS) {
            if (slowSinceNs < 0) slowSinceNs = loc.elapsedRealtimeNanos
            else if (!paused && (loc.elapsedRealtimeNanos - slowSinceNs) / 1e9 >= Defaults.PAUSE_AFTER_S) paused = true
        } else {
            slowSinceNs = -1
            if (paused && speed >= Defaults.RESUME_SPEED_MPS) paused = false
        }
    }
}
```
### Room/SQLite schema for ~150k points per 1000 km ride (~10 MB), batch inserts, WAL
```
@Entity(tableName = "track_point",
    indices = [Index(value = ["trackId", "tMs"])])
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val tMs: Long,            // System.currentTimeMillis() at fix (for GPX <time>)
    val lat: Double,
    val lon: Double,
    val ele: Double?,         // meters (GPS or baro-corrected)
    val accM: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val segment: Int          // increments on gap -> <trkseg>
)

// Room database builder: WAL is default for Room; keep transactions short.
// Coalesce inserts: buffer points and flush every 10 points or 5 s in one transaction.
@Dao interface TrackDao {
    @Insert fun insertAll(points: List<TrackPoint>)
    @Query("SELECT * FROM track_point WHERE trackId = :id ORDER BY tMs") fun cursor(id: Long): Cursor
}
```
### Haversine (for bulk DB stats; use Location.distanceTo for the live 1 Hz path) and elevation gain with hysteresis (3 m baro / 5–10 m GPS, cf. OpenTracks ALTITUDE_CHANGE_DIFF_M=3, Strava 2 m/10 m)
```
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371008.8
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/** Accumulates gain/loss only after the smoothed altitude moved >= threshold from the last accepted level. */
class ElevationAccumulator(private val thresholdM: Double = 5.0, private val alpha: Double = 0.3) {
    private var smoothed: Double? = null
    private var anchor: Double? = null
    var gainM = 0.0; var lossM = 0.0

    fun add(ele: Double, verticalAccM: Float?) {
        if (verticalAccM != null && verticalAccM > 20f) return       // GPS altitude too noisy
        val s = smoothed?.let { it + alpha * (ele - it) } ?: ele        // exponential smoothing
        smoothed = s
        val a = anchor ?: run { anchor = s; return }
        val d = s - a
        if (d >= thresholdM) { gainM += d; anchor = s }
        else if (d <= -thresholdM) { lossM += -d; anchor = s }
    }
}
// Use thresholdM = 3.0 when altitude comes from a barometer (TYPE_PRESSURE + SensorManager.getAltitude), 5–10 m for GPS-only.
```
### Crash-safe incremental GPX 1.1 writer: file is valid after every flush (footer overwrite + fsync); SQLite stays the source of truth
```
class GpxAppendWriter(private val file: File, creator: String, name: String) {
    private val footer = "</trkseg></trk></gpx>\n"
    private val nf = DecimalFormat("0.000000", DecimalFormatSymbols(Locale.ROOT))
    private val ef = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ROOT))
    private val iso = DateTimeFormatter.ISO_INSTANT   // 2026-09-07T10:15:30Z

    init {
        if (!file.exists() || file.length() == 0L) {
            file.writeText("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="$creator"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
<metadata><time>${iso.format(Instant.now())}</time></metadata>
<trk><name>${name.escapeXml()}</name>
<trkseg>
""" + footer)
        }
    }

    /** Appends a batch of points; newSegment closes/opens <trkseg>. */
    fun append(points: List<TrackPoint>) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(raf.length() - footer.toByteArray().size)   // drop footer
            raf.seek(raf.length())
            val sb = StringBuilder()
            for (p in points) {
                if (p.startsNewSegment) sb.append("</trkseg>\n<trkseg>\n")
                sb.append("<trkpt lat=\"").append(nf.format(p.lat)).append("\" lon=\"").append(nf.format(p.lon)).append("\">")
                p.ele?.let { sb.append("<ele>").append(ef.format(it)).append("</ele>") }   // ele BEFORE time
                sb.append("<time>").append(iso.format(Instant.ofEpochMilli(p.tMs))).append("</time>")
                sb.append("</trkpt>\n")
            }
            sb.append(footer)
            raf.write(sb.toString().toByteArray(Charsets.UTF_8))
            raf.fd.sync()                                             // survive power loss
        }
    }
}
// Recovery on start: if the file does not end with footer (crash mid-write), cut at the last "</trkpt>\n" and re-append footer.
```
### Heading fusion for course-up 3D camera that does not spin when stopped (thresholds from Organic Maps 0.7 m/s + 3 s bearing lifetime, OsmAnd 1 deg threshold and 0.04 low-pass)
```
class HeadingSource(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val R = FloatArray(9); private val orient = FloatArray(3)
    private var declination = 0f
    private var lastGpsBearingNs = 0L
    private var sinAvg = 0.0; private var cosAvg = 1.0           // OsmAnd-style sin/cos low-pass
    var headingDeg = 0f; private set                              // what the camera uses
    var compassEnabled = false; private set

    companion object {
        const val GPS_BEARING_MIN_SPEED = 1.5f   // m/s; OM uses 0.7, OsmAnd 1.5 for animation
        const val GPS_BEARING_LIFETIME_NS = 3_000_000_000L
        const val MIN_CHANGE_DEG = 1.0f          // OsmAnd COMPASS_HEADING_THRESHOLD
        const val ALPHA = 0.04                    // OsmAnd KALMAN_COEFFICIENT
    }

    fun onFix(loc: Location) {
        declination = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(),
            loc.altitude.toFloat(), System.currentTimeMillis()).declination
        val fast = loc.hasSpeed() && loc.speed > GPS_BEARING_MIN_SPEED
        val goodBearing = loc.hasBearing() && loc.bearing != 0f &&
            (Build.VERSION.SDK_INT < 26 || !loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees < 45f)
        if (fast && goodBearing) {
            setHeading(loc.bearing)
            lastGpsBearingNs = SystemClock.elapsedRealtimeNanos()
            if (compassEnabled) stopCompass()                 // gyro/mag off while riding
        } else if (!fast && !compassEnabled && screenOn) {
            startCompass()                                    // optional: else heading just freezes
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (SystemClock.elapsedRealtimeNanos() - lastGpsBearingNs < GPS_BEARING_LIFETIME_NS) return
        SensorManager.getRotationMatrixFromVector(R, e.values)
        SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_X, SensorManager.AXIS_Z, R) // phone upright on bars
        SensorManager.getOrientation(R, orient)
        val mag = Math.toDegrees(orient[0].toDouble()).toFloat() + declination
        val rad = Math.toRadians(mag.toDouble())
        sinAvg = ALPHA * sin(rad) + (1 - ALPHA) * sinAvg
        cosAvg = ALPHA * cos(rad) + (1 - ALPHA) * cosAvg
        setHeading(((Math.toDegrees(atan2(sinAvg, cosAvg)) + 360) % 360).toFloat())
    }

    private fun setHeading(h: Float) {
        val diff = ((h - headingDeg + 540f) % 360f) - 180f
        if (abs(diff) >= MIN_CHANGE_DEG) headingDeg = (h + 360f) % 360f   // camera animates to this
    }
    fun startCompass() { rotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI); compassEnabled = true } }
    fun stopCompass() { sm.unregisterListener(this); compassEnabled = false }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    var screenOn = true
}
```
### Screen handling: keep-on only while recording and visible, per-window brightness override, black OLED theme
```
class MapActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        if (recording.isActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        headingSource.screenOn = true
    }
    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // system also releases it in background
        headingSource.screenOn = false; headingSource.stopCompass()
        super.onPause()
    }
    /** 0.0 = darkest the panel allows, 1.0 = full, -1 = follow system (BRIGHTNESS_OVERRIDE_NONE). */
    fun setMapBrightness(level: Float) {
        window.attributes = window.attributes.apply {
            screenBrightness = level.coerceIn(-1f, 1f)
        }
    }
    // Auto-dim: after 30 s without touch while riding -> setMapBrightness(0.1f); on touch -> restore user value.
}
// themes.xml: <item name="android:windowBackground">@android:color/black</item>
// Map style: pure black background, dark grey roads, amber/red accents; no 3D buildings, no hillshade, minimal labels.
```
### Camera pitch/zoom by speed with hysteresis and rate limiting (numbers from Organic Maps 3D table + OsmAnd CLOSE profile scaled for bicycles)
```
object Camera {
    // speed (km/h) -> zoom; MapLibre-style zoom where z16 ~ 2.4 m/px at equator
    private val table = listOf(0.0 to 17.5, 10.0 to 17.5, 20.0 to 17.0, 30.0 to 16.5, 45.0 to 16.0, 70.0 to 15.5)
    const val PITCH_RIDING_DEG = 50.0   // Organic Maps allows 45–55 deg, FOV 60
    const val PITCH_STOPPED_DEG = 30.0  // flatter when stopped: easier to read surroundings
    const val MIN_AUTOZOOM_SPEED_KMH = 7.0            // OsmAnd MIN_AUTO_ZOOM_SPEED
    const val ZOOM_HYSTERESIS = 0.25
    const val MAX_ZOOM_RATE_PER_S = 0.1               // OsmAnd ZOOM_PER_SECOND
    const val BLOCK_AFTER_GESTURE_MS = 10_000L         // Organic Maps kMaxBlockAutoZoomTimeSec

    fun targetZoom(speedKmh: Double): Double {
        if (speedKmh < MIN_AUTOZOOM_SPEED_KMH) return table.first().second
        val i = table.indexOfFirst { it.first >= speedKmh }.let { if (it < 0) table.lastIndex else it }
        if (i == 0) return table[0].second
        val (s0, z0) = table[i - 1]; val (s1, z1) = table[i]
        return z0 + (speedKmh - s0) / (s1 - s0) * (z1 - z0)
    }
    // Apply: only change if |target - current| > ZOOM_HYSTERESIS, animate at <= 0.1 zoom/s, skip for 10 s after user gesture.
    // Position the rider marker at ~1/3 from bottom (OsmAnd FOCUS_PIXEL_RATIO_Y = 1/3) so the road ahead fills the screen.
}
```
### Douglas–Peucker simplification for drawing 150k points (tolerance in meters chosen per zoom, e.g. ~2 px)
```
fun simplifyRdp(pts: List<LatLon>, toleranceM: Double): List<LatLon> {
    if (pts.size < 3) return pts
    val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.lastIndex] = true
    val stack = ArrayDeque<IntArray>(); stack.add(intArrayOf(0, pts.lastIndex))
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        var maxD = 0.0; var idx = -1
        for (i in a + 1 until b) {
            val d = perpDistanceM(pts[i], pts[a], pts[b])
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD > toleranceM && idx > 0) { keep[idx] = true; stack.add(intArrayOf(a, idx)); stack.add(intArrayOf(idx, b)) }
    }
    return pts.filterIndexed { i, _ -> keep[i] }
}
// perpDistanceM: project to local equirectangular meters (x = dLon*cos(lat0)*111320, y = dLat*110540) and use point-to-segment distance.
// Tolerance by zoom: ~2 px * metersPerPixel(zoom, lat); metersPerPixel = 156543.03 * cos(lat) / 2^zoom.
// Cache simplified levels (z10, z12, z14, z16) in SQLite; draw only the live tail (last ~500 points) unsimplified.
```
