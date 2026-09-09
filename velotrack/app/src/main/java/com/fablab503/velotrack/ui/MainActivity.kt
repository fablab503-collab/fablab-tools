package com.fablab503.velotrack.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityMainBinding
import com.fablab503.velotrack.databinding.DialogPlacePickerBinding
import com.fablab503.velotrack.download.Bands
import com.fablab503.velotrack.download.Geocoder
import com.fablab503.velotrack.download.MapDownloadService
import com.fablab503.velotrack.download.MapLibrary
import com.fablab503.velotrack.download.RangeClient
import com.fablab503.velotrack.download.WeatherClient
import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.location.GpsSource
import com.fablab503.velotrack.location.HeadingEstimator
import com.fablab503.velotrack.location.IdleSpeedEstimator
import com.fablab503.velotrack.map.MapController
import com.fablab503.velotrack.model.CameraMode
import com.fablab503.velotrack.model.Favorite
import com.fablab503.velotrack.model.FavoriteKind
import com.fablab503.velotrack.model.GpsFix
import com.fablab503.velotrack.model.GpsStatus
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.model.RecordingStatus
import com.fablab503.velotrack.model.RideState
import com.fablab503.velotrack.model.ScreenMode
import com.fablab503.velotrack.model.TrackSummary
import com.fablab503.velotrack.recording.RideController
import com.fablab503.velotrack.recording.RideSession
import com.fablab503.velotrack.recording.RideStats
import com.fablab503.velotrack.route.RouteFollower
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.settings.WeatherReading
import com.fablab503.velotrack.storage.FavoritesRepository
import com.fablab503.velotrack.storage.RouteStore
import com.fablab503.velotrack.storage.TrackDatabase
import com.fablab503.velotrack.storage.TrackRepository
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * Map + HUD screen. The MapView is created programmatically and inserted under the HUD overlay.
 *
 * Map data: the four detail-band MBTiles files owned by [MapLibrary] are always the map sources;
 * the style is loaded once they exist and reloaded whenever [MapDownloadService] reports a finished
 * download. The "no map" overlay shows only while no region has been downloaded or imported.
 *
 * Position source: while [RideSession] is idle and the recording service is not running, this
 * activity runs its own [GpsSource] for the puck; while a ride is recorded, the service is the only
 * GPS client and the UI renders `RideSession.state` (last fix, heading, stats, satellites).
 *
 * Favourites: Home, Work and the Favourites sheet start *guidance*, a marker plus a straight dashed
 * line to the place and a HUD line with distance and relative direction. "Where am I" reads the
 * named map feature under the puck from the rendered tiles (fully offline).
 */
class MainActivity : AppCompatActivity(), GpsSource.Listener, FavoritesSheet.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var favorites: FavoritesRepository
    private lateinit var mapLibrary: MapLibrary
    private lateinit var routeStore: RouteStore
    private lateinit var gpsSource: GpsSource
    private lateinit var mapView: MapView
    private lateinit var mapController: MapController

    private var map: MapLibreMap? = null
    private val heading = HeadingEstimator()
    private val idleSpeed = IdleSpeedEstimator()
    private val handler = Handler(Looper.getMainLooper())

    // Weather chip near the clock: an occasional, explicit-feeling network read (never on a
    // recording tick, only on the once-a-minute clock tick and only when stale or far away), same
    // client the map downloader already uses.
    /** Shared by the address lookup in the Set Home / Set Work dialog. Built only if used. */
    private val placeSearchClient by lazy { RangeClient.defaultClient() }

    private val httpClient by lazy { RangeClient.defaultClient() }
    private var weatherFetchInFlight = false

    // Theme (dark / light / auto by sun), riding mode and auto record.
    private lateinit var nightMode: NightModeManager
    private val ridingMode = RidingModeController()
    private val autoRecord = AutoRecordDetector()
    /** Last effective speed handed to [ridingMode]; replayed by [ridingModeRunnable] when a timer expires. */
    private var ridingSpeedMps: Float? = null
    private var ridingSpeedAtMs = 0L
    /** True while the crash-recovery dialog is on screen; auto-record must not start a second ride then. */
    private var recoveryDialogShowing = false
    /** Status of the last rendered [RideState]; detects the non-IDLE → IDLE transition (recording stopped). */
    private var lastStatus: RecordingStatus = RecordingStatus.IDLE

    // Idle (not recording) GPS feed.
    private var activityStarted = false
    private var localGpsRunning = false
    private var localGpsStatus = GpsStatus()
    private var localLastFix: GpsFix? = null

    // Recording state bookkeeping.
    private var lastRenderedFix: GpsFix? = null
    private var trackOnMapId: Long? = null
    /** Stored points already drawn for [trackOnMapId]; -1 while the history is being loaded. */
    private var trackPointsOnMap = -1
    private var trackLoadJob: Job? = null

    // Map data and route.
    /** The four band files once [MapLibrary.ensureBandFiles] has run; null until then. */
    private var bandFiles: List<File>? = null
    private var downloadReceiverRegistered = false
    private var loadedRouteKey: String? = null
    private var routeFollower: RouteFollower? = null
    private var offRoute = false
    private var routeLoadJob: Job? = null

    // Guidance to a favourite and the "where am I" line.
    private var guidanceTarget: Favorite? = null
    private var placeQueriedAtMs = 0L
    private var placeQueriedAt: LatLon? = null
    private var placeName: String? = null

    // One-shot UI.
    private var batterySaverWarned = false
    private var recoveryChecked = false
    private var pendingViewTrackId: Long? = null
    private var pendingAfterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val action = pendingAfterPermission
            pendingAfterPermission = null
            if (hasFineLocation()) {
                refreshLocalGps()
                action?.invoke()
            } else if (action != null) {
                showPermissionDenied()
            }
            updateGpsBanner()
        }

    private val routeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importRoute(uri)
        }

    /**
     * Download map / Map data screens report RESULT_OK when the band files changed under MapLibre's
     * feet. This activity is stopped while they are on top, so [downloadReceiver] misses the
     * service's `done` broadcast; the result is the reliable signal to rebuild the map.
     */
    /**
     * Set while [PlacePickerActivity] is on top: what to do with the point the rider chose. Cleared
     * on every result, including a cancel, so a stale callback can never fire against a later pick.
     */
    private var onPlacePicked: ((LatLon, String) -> Unit)? = null

    private val placePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val handler = onPlacePicked
            onPlacePicked = null
            if (result.resultCode != RESULT_OK || handler == null) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val lat = data.getDoubleExtra(PlacePickerActivity.EXTRA_LAT, Double.NaN)
            val lon = data.getDoubleExtra(PlacePickerActivity.EXTRA_LON, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return@registerForActivityResult
            handler(LatLon(lat, lon), data.getStringExtra(PlacePickerActivity.EXTRA_NAME).orEmpty())
        }

    private val mapDataLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) onMapDataChanged()
        }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClockAndBattery()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    private val gpsRetryRunnable = Runnable { refreshLocalGps() }

    /** Riding mode timers (touch timeout, enter/exit delays) can expire without a new fix. */
    private val ridingModeRunnable = Runnable {
        val now = SystemClock.elapsedRealtime()
        // A speed sample older than a few seconds (GPS lost, tunnel) no longer proves the rider is fast.
        val speed = if (now - ridingSpeedAtMs > RIDING_SPEED_STALE_MS) null else ridingSpeedMps
        if (ridingMode.onSpeed(speed, now)) applyRidingMode()
        scheduleRidingModeCheck(now)
    }

    /** Auto theme: re-evaluate at the next sunrise/sunset while this screen is resumed. */
    private val nightCheckRunnable = Runnable {
        if (nightMode.needsChange()) nightMode.apply() // AppCompat recreates this activity
        scheduleNightCheck() // re-arm in case it did not
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_THEME_MODE) nightMode.apply()
    }

    /** A finished download changes the band files under MapLibre's feet: reload the style. */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MapDownloadService.ACTION_PROGRESS) return
            if (intent.getStringExtra(MapDownloadService.EXTRA_PHASE) == PHASE_DONE) onMapDataChanged()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        nightMode = NightModeManager(prefs)
        db = TrackDatabase.get(this)
        repo = TrackRepository(db)
        favorites = FavoritesRepository(db)
        mapLibrary = MapLibrary(this, prefs, db)
        routeStore = RouteStore(this, db, prefs)
        gpsSource = GpsSource(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        // MapLibre.getInstance() ran in VeloTrackApp.onCreate.
        val options = MapLibreMapOptions.createFromAttributes(this)
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
            // Four band sources carry the same labels; without this, one band's labels would suppress another's.
            .crossSourceCollisions(false)
        mapView = MapView(this, options)
        binding.mapContainer.addView(
            mapView,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        mapView.onCreate(savedInstanceState)

        mapController = MapController(this, mapView, prefs)
        mapController.onUserGesture = { updateModeButton() }
        mapView.getMapAsync { m ->
            map = m
            // setPrefetchesTiles(false) is deprecated in favour of setPrefetchZoomDelta; a delta
            // of 0 is the documented way to disable prefetching outright. Off because every tile
            // here comes from a bundled offline file, so there is nothing to usefully prefetch
            // and no network to spend doing it.
            m.setPrefetchZoomDelta(0)
            mapController.onMapReady(m)
            applyMapThemeColors()
            showPendingTrack()
            refreshFavoriteMarkers()
        }

        setupButtons()
        setHudHidden(prefs.hudHidden, animate = false)
        // Landscape puts the statistics panel over the left of the map: tell the camera how much.
        binding.topPanel.doOnLayout { panel ->
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            mapController.cameraPaddingLeftPx = if (landscape) panel.width.toDouble() else 0.0
        }
        // Rotation recreates this activity; guidance to a favourite must not be lost with it.
        savedInstanceState?.getLong(KEY_GUIDANCE_FAV, -1L)?.takeIf { it >= 0 }?.let { id ->
            lifecycleScope.launch {
                val fav = withContext(Dispatchers.IO) { runCatching { favorites.get(id) }.getOrNull() }
                if (fav != null && !isFinishing && !isDestroyed && guidanceTarget == null) startGuidance(fav)
            }
        }
        updateModeButton()
        pendingViewTrackId = viewTrackIdFrom(intent)
        mapController.lightMap = !isNightUi()
        prepareMapData()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RideSession.state.collect { render(it) }
            }
        }

        // Registered for the activity's whole life: the theme is changed from Settings while this
        // screen is stopped behind it, so an onResume/onPause registration would never hear it.
        prefs.registerListener(prefsListener)

        if (!hasFineLocation()) requestLocationPermissions(null)
    }

    /** Any touch (before dispatch, ACTION_DOWN only) brings the controls back in riding mode. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        onScreenTouched()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = viewTrackIdFrom(intent)
        if (id != null) {
            pendingViewTrackId = id
            showPendingTrack()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        activityStarted = true
        registerDownloadReceiver()
        refreshLocalGps()
        handler.removeCallbacks(clockRunnable)
        clockRunnable.run()
        if (!recoveryChecked) {
            recoveryChecked = true
            checkRecovery()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (nightMode.needsChange()) {
            // Sunset/sunrise passed (or the theme was changed) while away. AppCompat normally recreates
            // this activity; carry on regardless, the calls below are harmless either way.
            nightMode.apply()
        }
        scheduleNightCheck()
        applyScreenMode()
        updateGpsBanner()
        refreshNoMapOverlay()
        refreshRoute()
        updateModeButton()
        render(RideSession.state.value)
    }

    override fun onPause() {
        handler.removeCallbacks(nightCheckRunnable)
        handler.removeCallbacks(ridingModeRunnable)
        if (ridingMode.reset()) applyRidingMode(animate = false)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        activityStarted = false
        (RideSession.state.value.lastFix ?: localLastFix)?.let { prefs.lastPosition = it.latLon }
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(gpsRetryRunnable)
        refreshLocalGps() // stops the local feed while not visible
        unregisterDownloadReceiver()
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
        guidanceTarget?.let { outState.putLong(KEY_GUIDANCE_FAV, it.id) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        prefs.unregisterListener(prefsListener)
        stopLocalGps()
        mapController.onDestroy()
        mapView.onDestroy()
        super.onDestroy()
        // TrackDatabase is process-wide and shared with the recording service; never close it here.
    }

    // ---------------------------------------------------------------- buttons and menu

    /**
     * Android 15+ draws the app edge to edge, so the HUD and the controls would sit under the
     * status and navigation bars. Add their heights as padding; the map itself stays full screen.
     */
    private fun applySystemBarInsets() {
        val topPanel = binding.topPanel
        val controls = binding.controls
        val showHud = binding.btnShowHud
        val basePaddingTop = topPanel.paddingTop
        val basePaddingLeft = topPanel.paddingLeft
        val basePaddingBottom = controls.paddingBottom
        val basePaddingRight = controls.paddingRight
        val baseShowHudMargin = (showHud.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // In landscape the panel and the button column also meet the side bars and cut-outs.
            topPanel.updatePadding(top = basePaddingTop + bars.top, left = basePaddingLeft + bars.left)
            controls.updatePadding(bottom = basePaddingBottom + bars.bottom, right = basePaddingRight + bars.right)
            // The chevron is a sibling of the panels, so it carries the inset as a margin.
            showHud.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = baseShowHudMargin + bars.top + basePaddingTop
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupButtons() {
        binding.btnRecord.onTapWithFeedback { onRecordClicked() }
        binding.btnPause.onTapWithFeedback {
            if (RideSession.state.value.status == RecordingStatus.PAUSED) {
                RideController.resume(this)
            } else {
                RideController.pause(this)
            }
        }
        binding.btnStop.onTapWithFeedback { confirmStop() }
        binding.btnRecenter.onTapWithFeedback {
            mapController.recenter()
            updateModeButton()
        }
        binding.btnToggle3d.onTapWithFeedback { toggleFollowMode() }
        binding.btnMenu.onTapWithFeedback { showMenu() }
        // The statistics card covers the top of the map; a tap folds it away, the chevron restores it.
        binding.hudPanel.onTapWithFeedback { setHudHidden(true, animate = true) }
        binding.btnShowHud.onTapWithFeedback { setHudHidden(false, animate = true) }
        binding.btnGpsSettings.setOnClickListener { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        binding.btnDownloadMap.onTapWithFeedback { openDownloadMap() }

        binding.btnHome.onTapWithFeedback { onFixedFavoriteClicked(FavoriteKind.HOME) }
        binding.btnHome.onHoldWithFeedback { showFixedFavoriteMenu(FavoriteKind.HOME) }
        binding.btnWork.onTapWithFeedback { onFixedFavoriteClicked(FavoriteKind.WORK) }
        binding.btnWork.onHoldWithFeedback { showFixedFavoriteMenu(FavoriteKind.WORK) }
        binding.btnFavorites.onTapWithFeedback { showFavoritesSheet() }
        binding.btnStopGuidance.onTapWithFeedback { stopGuidance() }
        binding.guidanceText.setOnClickListener { stopGuidance() }
    }

    /**
     * A click that answers the finger. The map controls are used at speed, with gloves, without
     * looking, so every one of them ticks. Android routes this through the system's touch-feedback
     * setting, so a rider who turns haptics off keeps them off.
     */
    private fun View.onTapWithFeedback(action: () -> Unit) {
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            action()
        }
    }

    /** Long press with the heavier tick Android uses for held gestures. */
    private fun View.onHoldWithFeedback(action: () -> Unit) {
        setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            action()
            true
        }
    }

    private fun toggleFollowMode() {
        val next = if (prefs.followMode == CameraMode.FOLLOW_3D) CameraMode.FOLLOW_2D else CameraMode.FOLLOW_3D
        prefs.followMode = next
        mapController.cameraMode = next
        updateModeButton()
    }

    /** The toggle shows the view it switches to: a flat map while in 3D, the 3D glyph while in 2D. */
    private fun updateModeButton() {
        val in3d = prefs.followMode == CameraMode.FOLLOW_3D
        binding.btnToggle3d.setImageResource(if (in3d) R.drawable.ic_map else R.drawable.ic_3d_rotation)
        binding.btnToggle3d.contentDescription = getString(R.string.cd_toggle_view)
    }

    /** Hands the theme (dynamic colour when available) to the map so track, route and puck match the UI. */
    private fun applyMapThemeColors() {
        val root = binding.root
        val primary = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
        mapController.setThemeColors(
            trackColor = primary,
            routeColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorTertiary),
            puckColor = primary,
            puckOnColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnPrimary),
        )
    }

    /** Fills the status chip: text, leading icon and icon tint from a theme colour attribute. */
    private fun setStatusChip(textRes: Int, iconRes: Int, tintAttr: Int) {
        val chip = binding.statusText
        chip.setText(textRes)
        chip.setChipIconResource(iconRes)
        chip.chipIconTint = ColorStateList.valueOf(MaterialColors.getColor(chip, tintAttr))
    }

    /** A dialog, menu or sheet is about to open: bring the controls back so nothing fades behind it. */
    private fun showControlsForDialog() {
        if (ridingMode.reset()) applyRidingMode()
    }

    private fun showMenu() {
        showControlsForDialog()
        val popup = PopupMenu(this, binding.btnMenu)
        popup.inflate(R.menu.main_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_tracks -> startActivity(Intent(this, TracksActivity::class.java))
                R.id.action_stats -> startActivity(Intent(this, StatsActivity::class.java))
                R.id.action_import_route -> routeLauncher.launch(arrayOf("*/*"))
                R.id.action_clear_route -> clearRoute()
                R.id.action_download_map -> openDownloadMap()
                R.id.action_map_files -> mapDataLauncher.launch(Intent(this, MapFilesActivity::class.java))
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.action_about -> AboutDialog.show(this)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    // ---------------------------------------------------------------- recording control

    private fun onRecordClicked() {
        if (RideSession.state.value.status != RecordingStatus.IDLE) return
        if (!hasFineLocation() || !hasNotificationPermission()) {
            requestLocationPermissions { startRecording() }
            return
        }
        startRecording()
    }

    private fun startRecording() {
        if (!hasFineLocation()) {
            showPermissionDenied()
            return
        }
        if (!batterySaverWarned && batterySaverStopsGps()) {
            batterySaverWarned = true
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_saver_title)
                .setMessage(R.string.battery_saver_body)
                .setPositiveButton(R.string.dialog_continue) { _, _ -> launchService { RideController.startNew(this) } }
                .setNeutralButton(R.string.battery_saver_settings) { _, _ ->
                    openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }
        launchService { RideController.startNew(this) }
    }

    /** Stops the local GPS feed first so the service is the only client, then starts it. */
    private fun launchService(start: () -> Unit): Boolean {
        stopLocalGps()
        val started = try {
            start()
            true
        } catch (e: IllegalStateException) {
            // Foreground start refused (the activity just left the foreground): keep the idle feed.
            Log.w(TAG, "service start refused", e)
            false
        }
        // If the service could not start (permission revoked, etc.) the state stays IDLE and the
        // local feed is restored by this retry.
        handler.removeCallbacks(gpsRetryRunnable)
        handler.postDelayed(gpsRetryRunnable, SERVICE_START_GRACE_MS)
        return started
    }

    private fun confirmStop() {
        showControlsForDialog()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_confirm_title)
            .setMessage(R.string.stop_confirm_body)
            .setPositiveButton(R.string.btn_stop) { _, _ -> RideController.stop(this) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun batterySaverStopsGps(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (!pm.isPowerSaveMode) return false
        if (Build.VERSION.SDK_INT < 28) return false
        val mode = pm.locationPowerSaveMode
        return mode == PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF ||
            mode == PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF
    }

    // ---------------------------------------------------------------- recovery

    private fun checkRecovery() {
        if (RideSession.serviceRunning) return
        lifecycleScope.launch {
            val unfinished: TrackSummary = withContext(Dispatchers.IO) {
                runCatching { repo.findUnfinished() }.getOrNull()
            } ?: return@launch
            if (RideSession.serviceRunning || RideSession.state.value.status != RecordingStatus.IDLE) return@launch
            if (isFinishing || isDestroyed) return@launch
            recoveryDialogShowing = true
            MaterialAlertDialogBuilder(this@MainActivity)
                .setOnDismissListener { recoveryDialogShowing = false }
                .setTitle(R.string.recovery_title)
                .setMessage(
                    getString(R.string.recovery_body, unfinished.name, Format.distance(unfinished.distanceM, prefs.units))
                )
                .setPositiveButton(R.string.recovery_resume) { _, _ -> resumeUnfinished(unfinished.id) }
                .setNegativeButton(R.string.recovery_finish) { _, _ -> finishUnfinished(unfinished) }
                .show()
        }
    }

    private fun resumeUnfinished(trackId: Long) {
        if (!hasFineLocation() || !hasNotificationPermission()) {
            requestLocationPermissions { resumeUnfinished(trackId) }
            return
        }
        launchService { RideController.resumeUnfinished(this, trackId) }
    }

    /** Marks the track finished with statistics rebuilt from its stored points. */
    private fun finishUnfinished(track: TrackSummary) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val nowMs = System.currentTimeMillis()
                    val stats = RideStats.rebuild(repo.pointsAsSequence(track.id), track.startedAtMs, track.movingMs)
                    repo.finishTrack(track.id, stats.snapshot(nowMs), nowMs)
                }
            }
        }
    }

    // ---------------------------------------------------------------- permissions

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermissions(then: (() -> Unit)?) {
        pendingAfterPermission = then
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun showPermissionDenied() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_denied_title)
            .setMessage(R.string.permission_denied_body)
            .setPositiveButton(R.string.permission_app_settings) { _, _ ->
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openSettings(action: String, data: Uri? = null) {
        val intent = Intent(action)
        if (data != null) intent.data = data
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No settings screen for this action on this device; nothing sensible to do.
        }
    }

    // ---------------------------------------------------------------- local GPS feed (idle)

    /** Starts or stops the activity's own GPS client depending on visibility and recording state. */
    private fun refreshLocalGps() {
        val state = RideSession.state.value
        val idle = state.status == RecordingStatus.IDLE
        val eligible = activityStarted && idle && hasFineLocation()
        val want = eligible && !RideSession.serviceRunning
        if (want && !localGpsRunning) {
            localGpsRunning = true
            gpsSource.start(this)
        } else if (!want && localGpsRunning) {
            stopLocalGps()
        }
        // The service publishes IDLE slightly before it clears serviceRunning; poll until it does.
        handler.removeCallbacks(gpsRetryRunnable)
        if (eligible && RideSession.serviceRunning) {
            handler.postDelayed(gpsRetryRunnable, GPS_RETRY_MS)
        }
    }

    private fun stopLocalGps() {
        if (!localGpsRunning) return
        localGpsRunning = false
        gpsSource.stop()
        localGpsStatus = GpsStatus()
        idleSpeed.reset()
    }

    override fun onFix(fix: GpsFix) {
        if (!localGpsRunning) return
        localLastFix = fix
        val headingDeg = heading.update(fix)
        val nowMs = System.currentTimeMillis()
        mapController.updatePosition(fix, headingDeg, nowMs)
        updateRoute(fix.latLon, nowMs)
        updateGuidance(fix, headingDeg)
        updatePlace(fix, nowMs)
        val speed = idleSpeed.update(fix) // receiver speed, or derived when it reports 0
        onSpeedSample(speed)
        checkAutoRecord(fix, speed)
        if (!localGpsRunning) return // an auto start just handed GPS to the service
        renderLiveHud(fix, localGpsStatus, speed)
    }

    override fun onStatus(status: GpsStatus) {
        localGpsStatus = status
        if (RideSession.state.value.status == RecordingStatus.IDLE) renderGps(status, localLastFix)
        updateGpsBanner()
    }

    // ---------------------------------------------------------------- rendering

    private fun render(state: RideState) {
        val units = prefs.units
        val status = state.status
        val active = status != RecordingStatus.IDLE

        if (status != lastStatus) {
            lastStatus = status
            if (!active) {
                // Recording stopped: show the controls again and hold auto-record for a while.
                autoRecord.cooldown(SystemClock.elapsedRealtime())
                ridingSpeedMps = null
                handler.removeCallbacks(ridingModeRunnable)
                if (ridingMode.reset()) applyRidingMode()
            }
            applyScreenMode()
        }

        binding.btnRecord.isVisible = !active
        binding.btnPause.isVisible = active
        binding.btnStop.isVisible = active
        if (active) {
            val paused = status == RecordingStatus.PAUSED
            binding.btnPause.setIconResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
            binding.btnPause.text = getString(if (paused) R.string.btn_resume else R.string.btn_pause)
        }

        binding.statusText.isVisible = active
        when (status) {
            RecordingStatus.RECORDING ->
                setStatusChip(R.string.status_recording, R.drawable.ic_recording_dot, androidx.appcompat.R.attr.colorError)
            RecordingStatus.AUTO_PAUSED ->
                setStatusChip(R.string.status_auto_paused, R.drawable.ic_pause_circle, com.google.android.material.R.attr.colorTertiary)
            RecordingStatus.PAUSED ->
                setStatusChip(R.string.status_paused, R.drawable.ic_pause, com.google.android.material.R.attr.colorOnSurfaceVariant)
            RecordingStatus.IDLE -> Unit
        }

        // Stats stay visible after a stop (the service leaves trackId set) until the next start.
        val showStats = active || state.trackId != null
        val stats = state.stats
        val dash = getString(R.string.hud_no_value)
        binding.distanceText.text = if (showStats) Format.distance(stats.distanceM, units) else dash
        binding.movingTimeText.text = if (showStats) Format.duration(stats.movingMs) else dash
        binding.avgSpeedText.text = if (showStats) Format.speed(stats.avgMovingSpeedMps, units) else dash
        binding.elevationText.text = if (showStats) Format.elevation(stats.elevationGainM, units) else dash

        val error = state.error
        binding.errorText.isVisible = error != null
        if (error != null) binding.errorText.text = getString(R.string.error_prefix, error)

        if (active) {
            val fix = state.lastFix
            renderLiveHud(fix, state.gps, state.speedMps)
            if (fix != null && fix !== lastRenderedFix) {
                lastRenderedFix = fix
                val nowMs = System.currentTimeMillis()
                mapController.updatePosition(fix, state.headingDeg, nowMs)
                updateRoute(fix.latLon, nowMs)
                updateGuidance(fix, state.headingDeg)
                updatePlace(fix, nowMs)
                onSpeedSample(state.speedMps)
            }
            syncTrackOnMap(state)
        } else {
            renderLiveHud(localLastFix, localGpsStatus)
        }

        refreshLocalGps()
        updateGpsBanner()
    }

    /** [speedOverride] is the filtered speed published by the recording service; null when idle. */
    private fun renderLiveHud(fix: GpsFix?, gps: GpsStatus, speedOverride: Float? = null) {
        val units = prefs.units
        binding.speedUnit.text = Format.speedUnit(units)
        val speed = speedOverride ?: fix?.speedMps
        binding.speedText.text = if (speed != null) {
            Format.speedValue(speed.toDouble(), units)
        } else {
            getString(R.string.hud_speed_placeholder)
        }
        renderGps(gps, fix)
    }

    private fun renderGps(gps: GpsStatus, fix: GpsFix?) {
        val accuracy = fix?.accuracyM
        binding.gpsText.text = when {
            !gps.providerEnabled -> getString(R.string.gps_off_banner)
            !gps.hasFix && gps.satellitesTotal == 0 -> getString(R.string.hud_gps_searching)
            gps.hasFix && accuracy != null ->
                getString(R.string.hud_gps_status, gps.satellitesUsed, gps.satellitesTotal, accuracy.roundToInt())
            else -> getString(R.string.hud_gps_status_no_accuracy, gps.satellitesUsed, gps.satellitesTotal)
        }
        // Green once a fix is active, red while searching or the location provider is off -- a
        // glance a rider can read in bright sunlight without parsing the "GPS 9/14" text. Purely
        // decorative for accessibility (importantForAccessibility="no" in the layout): gpsText
        // right next to it already says the same thing in words for a screen reader.
        val dotColor = if (gps.hasFix) {
            ContextCompat.getColor(this, R.color.gps_fix_ok)
        } else {
            MaterialColors.getColor(binding.gpsStatusDot, androidx.appcompat.R.attr.colorError)
        }
        binding.gpsStatusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
    }

    private fun updateGpsBanner() {
        binding.gpsBanner.isVisible = !gpsSource.isGpsEnabled
    }

    private fun updateClockAndBattery() {
        binding.clockText.text = DateFormat.getTimeFormat(this).format(Date())
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
        binding.batteryText.text = if (pct in 0..100) {
            getString(R.string.hud_battery, pct)
        } else {
            getString(R.string.hud_no_value)
        }
        maybeRefreshWeather()
    }

    /**
     * Shows the cached reading immediately (so the chip is never blank just because the network
     * call hasn't finished), then refreshes it in the background when it is stale or the rider has
     * moved far enough that it is probably describing the wrong town. Runs off the once-a-minute
     * clock tick, not a dedicated timer: weather does not need to be fresher than that.
     */
    private fun maybeRefreshWeather() {
        val pos = currentPosition() ?: prefs.lastPosition ?: return
        val cached = prefs.weather
        renderWeather(cached)
        if (weatherFetchInFlight) return
        val ageMs = cached?.let { System.currentTimeMillis() - it.fetchedAtMs } ?: Long.MAX_VALUE
        val movedM = cached?.let { Geo.distanceM(it.at, pos) } ?: Double.MAX_VALUE
        if (ageMs < WEATHER_MAX_AGE_MS && movedM < WEATHER_REFRESH_DISTANCE_M) return
        weatherFetchInFlight = true
        lifecycleScope.launch {
            val now = WeatherClient.current(httpClient, pos.lat, pos.lon)
            weatherFetchInFlight = false
            if (isFinishing || isDestroyed || now == null) return@launch
            val reading = WeatherReading(now.temperatureC, now.symbolCode, pos, System.currentTimeMillis())
            prefs.weather = reading
            renderWeather(reading)
        }
    }

    private fun renderWeather(reading: WeatherReading?) {
        if (reading == null) {
            binding.weatherChip.isVisible = false
            return
        }
        binding.weatherIcon.text = weatherEmoji(reading.symbolCode)
        val temp = Format.temperature(reading.temperatureC, prefs.units)
        binding.weatherTemp.text = temp
        binding.weatherChip.isVisible = true
        binding.weatherChip.contentDescription = getString(R.string.cd_weather_chip, temp)
        binding.weatherChip.setOnClickListener {
            val url = WeatherClient.officialForecastUrl(reading.at.lat, reading.at.lon)
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { toast(R.string.hud_weather_open_failed) }
        }
    }

    /**
     * Keep-on / dim apply only while a ride is being recorded; when idle the system setting rules.
     * Derived from state (not from a click) because window flags die with a recreated activity.
     */
    private fun applyScreenMode() {
        val recording = RideSession.state.value.status != RecordingStatus.IDLE
        val mode = if (recording) prefs.screenMode else ScreenMode.SYSTEM
        val lp = window.attributes
        when (mode) {
            ScreenMode.KEEP_ON -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            ScreenMode.DIM -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                lp.screenBrightness = prefs.dimLevel
            }
            ScreenMode.SYSTEM -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        window.attributes = lp
    }

    // ---------------------------------------------------------------- riding mode, auto record, theme

    /** Feeds one effective speed sample to the riding-mode controller and re-arms its timer. */
    private fun onSpeedSample(speedMps: Float?) {
        val now = SystemClock.elapsedRealtime()
        ridingSpeedMps = speedMps
        ridingSpeedAtMs = now
        if (ridingMode.onSpeed(speedMps, now)) applyRidingMode()
        scheduleRidingModeCheck(now)
    }

    private fun onScreenTouched() {
        val now = SystemClock.elapsedRealtime()
        if (ridingMode.onTouch(now)) applyRidingMode()
        scheduleRidingModeCheck(now)
    }

    private fun scheduleRidingModeCheck(nowMs: Long) {
        handler.removeCallbacks(ridingModeRunnable)
        val delay = ridingMode.nextCheckDelayMs(nowMs) ?: return
        handler.postDelayed(ridingModeRunnable, delay.coerceAtLeast(RIDING_MIN_RECHECK_MS))
    }

    /**
     * Fades the whole controls container (FAB column and action row) to match [RidingModeController.hidden].
     * Hidden ends GONE with alpha restored: an alpha-0 VISIBLE view would still eat map taps, and a
     * cancelled fade-out never runs its end action, so the show path resets alpha itself.
     */
    private fun applyRidingMode(animate: Boolean = true) {
        val controls = binding.controls
        controls.animate().cancel()
        if (ridingMode.hidden) {
            if (!animate) {
                controls.isVisible = false
                controls.alpha = 1f
                return
            }
            controls.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                controls.isVisible = false
                controls.alpha = 1f
            }
        } else {
            if (!animate) {
                controls.alpha = 1f
                controls.isVisible = true
                return
            }
            controls.alpha = 0f
            controls.isVisible = true
            controls.animate().alpha(1f).setDuration(FADE_MS)
        }
    }

    /**
     * Idle path only: starts a recording by itself once the rider has been moving for a while.
     * Starting the location foreground service from a resumed activity needs no user tap.
     */
    private fun checkAutoRecord(fix: GpsFix, speedMps: Float?) {
        if (!prefs.autoRecord) {
            autoRecord.reset()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val detected = autoRecord.onFix(speedMps, fix.accuracyM ?: Float.MAX_VALUE, prefs.accuracyCutoffM, now)
        if (!detected) return
        if (RideSession.state.value.status != RecordingStatus.IDLE || recoveryDialogShowing ||
            !hasFineLocation() || !hasNotificationPermission() ||
            (!batterySaverWarned && batterySaverStopsGps())
        ) {
            // Anything that would need a dialog or a permission prompt is left to the Record button.
            autoRecord.reset()
            return
        }
        autoRecord.cooldown(now)
        if (launchService { RideController.startNew(this) }) toast(R.string.auto_record_started)
    }

    /** Arms [nightCheckRunnable] for the next sunrise/sunset (plus slack); nothing to do for DARK/LIGHT. */
    private fun scheduleNightCheck() {
        handler.removeCallbacks(nightCheckRunnable)
        val delay = nightMode.millisUntilNextCheck() ?: return
        handler.postDelayed(nightCheckRunnable, delay + NIGHT_CHECK_SLACK_MS)
    }

    private fun isNightUi(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ---------------------------------------------------------------- track on map

    /** Keeps the drawn track in step with the ride being recorded. */
    private fun syncTrackOnMap(state: RideState) {
        val id = state.trackId ?: return
        if (id != trackOnMapId) {
            trackOnMapId = id
            trackPointsOnMap = -1
            pendingViewTrackId = null
            mapController.clearTrack()
            trackLoadJob?.cancel()
            trackLoadJob = lifecycleScope.launch {
                val points = withContext(Dispatchers.IO) {
                    runCatching { repo.loadPoints(id) }.getOrDefault(emptyList())
                }
                if (trackOnMapId != id) return@launch
                mapController.setTrackHistory(points.map { it.latLon })
                trackPointsOnMap = points.size
                val current = RideSession.state.value
                if (current.trackId == id && current.stats.pointCount > points.size) {
                    current.lastFix?.let { mapController.appendTrackPoint(it.latLon) }
                    trackPointsOnMap = current.stats.pointCount
                }
            }
            return
        }
        if (trackPointsOnMap >= 0 && state.stats.pointCount > trackPointsOnMap) {
            // The fix that raised the stored-point count is the point that was stored.
            state.lastFix?.let { mapController.appendTrackPoint(it.latLon) }
            trackPointsOnMap = state.stats.pointCount
        }
    }

    private fun viewTrackIdFrom(intent: Intent?): Long? {
        val id = intent?.getLongExtra(EXTRA_VIEW_TRACK_ID, -1L) ?: -1L
        return if (id >= 0) id else null
    }

    /** Draws a saved track and frames it (north-up, free camera). Ignored while a ride is recorded. */
    private fun showPendingTrack() {
        val id = pendingViewTrackId ?: return
        val m = map ?: return
        pendingViewTrackId = null
        if (RideSession.state.value.status != RecordingStatus.IDLE) return
        trackOnMapId = id
        trackPointsOnMap = -1
        trackLoadJob?.cancel()
        trackLoadJob = lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) {
                runCatching { repo.loadPoints(id) }.getOrDefault(emptyList())
            }.map { it.latLon }
            if (trackOnMapId != id) return@launch
            mapController.setTrackHistory(points)
            trackPointsOnMap = points.size
            if (points.isEmpty()) return@launch
            mapController.cameraMode = CameraMode.FREE
            frameTrack(m, points)
        }
    }

    private fun frameTrack(m: MapLibreMap, points: List<LatLon>) {
        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        for (p in points) {
            if (p.lat > north) north = p.lat
            if (p.lat < south) south = p.lat
            if (p.lon > east) east = p.lon
            if (p.lon < west) west = p.lon
        }
        val boundsUpdate = if (north - south > MIN_BOUNDS_DEG || east - west > MIN_BOUNDS_DEG) {
            val padPx = (FRAME_PADDING_DP * resources.displayMetrics.density).toInt()
            runCatching {
                CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(north, east, south, west), 0.0, 0.0, padPx)
            }.getOrNull()
        } else {
            null
        }
        val update = boundsUpdate ?: CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(points[0].lat, points[0].lon))
                .zoom(VIEW_TRACK_ZOOM)
                .tilt(0.0)
                .bearing(0.0)
                .build()
        )
        m.easeCamera(update, FRAME_EASE_MS, true)
    }

    // ---------------------------------------------------------------- map data

    /** Creates the (possibly empty) band files off the main thread, then loads the style over them. */
    private fun prepareMapData() {
        lifecycleScope.launch {
            val files: List<File> = withContext(Dispatchers.IO) {
                runCatching { mapLibrary.ensureBandFiles() }
                Bands.ALL.map { band -> mapLibrary.bandFile(band) }
            }
            bandFiles = files
            reloadStyle()
            refreshNoMapOverlay()
        }
    }

    /**
     * Map tiles were added or removed. MapLibre keeps one read-only SQLite connection per band file
     * and remembers tiles it already answered as "no content"; reloading the style (even through a
     * blank one) is not enough to make a viewport that was empty before the download fill in, so the
     * whole activity is recreated. Downloads are rare and the recreate takes about two seconds; a
     * running recording lives in its service and is unaffected.
     */
    private fun onMapDataChanged() {
        if (isFinishing || isDestroyed) return
        recreate()
    }

    /** (Re)loads the style with all four band sources; a no-op until the band files are known. */
    private fun reloadStyle() {
        val files = bandFiles ?: return
        mapController.lightMap = !isNightUi()
        mapController.loadStyle(files) { error -> onStyleLoaded(error) }
    }

    private fun onStyleLoaded(error: String?) {
        if (isFinishing || isDestroyed) return
        if (error != null) {
            showMessage(getString(R.string.map_load_failed, error))
            return
        }
        // A fresh style has no marker or guidance line yet, and the place lookup needs rendered tiles.
        guidanceTarget?.let { target ->
            mapController.setTarget(target.latLon, target.kind)
            currentFix()?.let { fix -> mapController.setGuidanceLine(fix.latLon, target.latLon) }
        }
        placeQueriedAt = null
        placeName = null
        binding.placeText.isVisible = false
    }

    /** The overlay invites a first download only while no region has been downloaded or imported. */
    private fun refreshNoMapOverlay() {
        lifecycleScope.launch {
            val empty = withContext(Dispatchers.IO) {
                runCatching { mapLibrary.listRegions().isEmpty() }.getOrDefault(true)
            }
            binding.noMapOverlay.isVisible = empty
        }
    }

    /** Opens the Download screen with the rider's position and the camera target as candidate centres. */
    private fun openDownloadMap() {
        val intent = Intent(this, DownloadMapActivity::class.java)
        val fix = RideSession.state.value.lastFix ?: localLastFix
        if (fix != null) {
            intent.putExtra(DownloadMapActivity.EXTRA_LAT, fix.lat)
            intent.putExtra(DownloadMapActivity.EXTRA_LON, fix.lon)
        } else {
            prefs.lastPosition?.let { p ->
                intent.putExtra(DownloadMapActivity.EXTRA_LAT, p.lat)
                intent.putExtra(DownloadMapActivity.EXTRA_LON, p.lon)
            }
        }
        map?.cameraPosition?.target?.let { target ->
            intent.putExtra(DownloadMapActivity.EXTRA_CENTER_LAT, target.latitude)
            intent.putExtra(DownloadMapActivity.EXTRA_CENTER_LON, target.longitude)
        }
        mapDataLauncher.launch(intent)
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(MapDownloadService.ACTION_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        downloadReceiverRegistered = true
    }

    private fun unregisterDownloadReceiver() {
        if (!downloadReceiverRegistered) return
        downloadReceiverRegistered = false
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    // ---------------------------------------------------------------- route following

    private fun refreshRoute() {
        routeLoadJob?.cancel()
        routeLoadJob = lifecycleScope.launch {
            val thresholdM = prefs.offRouteM
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val route = routeStore.activeRoute()
                    if (route == null) null else Pair(route, routeStore.loadPoints(route.id))
                }.getOrNull()
            }
            val key = loaded?.let { "${it.first.id}:$thresholdM" }
            if (key == loadedRouteKey) return@launch
            loadedRouteKey = key
            offRoute = false
            if (loaded == null || loaded.second.size < 2) {
                routeFollower = null
                mapController.setRoute(null)
                binding.routeText.isVisible = false
            } else {
                mapController.setRoute(loaded.second)
                routeFollower = RouteFollower(loaded.second, thresholdM, OFF_ROUTE_DELAY_MS)
                binding.routeText.isVisible = true
                binding.routeText.setTextColor(
                    MaterialColors.getColor(binding.routeText, com.google.android.material.R.attr.colorOnSurface)
                )
                binding.routeText.text =
                    getString(R.string.route_remaining, Format.distance(loaded.first.distanceM, prefs.units))
            }
        }
    }

    private fun updateRoute(pos: LatLon, nowMs: Long) {
        val follower = routeFollower ?: return
        val progress = follower.update(pos, nowMs)
        val units = prefs.units
        binding.routeText.isVisible = true
        if (progress.offRoute) {
            binding.routeText.text = getString(R.string.route_off_route, Format.distance(progress.distanceToRouteM, units))
            binding.routeText.setTextColor(MaterialColors.getColor(binding.routeText, androidx.appcompat.R.attr.colorError))
        } else {
            binding.routeText.text = getString(R.string.route_remaining, Format.distance(progress.distanceRemainingM, units))
            binding.routeText.setTextColor(
                MaterialColors.getColor(binding.routeText, com.google.android.material.R.attr.colorOnSurface)
            )
        }
        if (progress.offRoute != offRoute) {
            offRoute = progress.offRoute
            vibrate(if (progress.offRoute) OFF_ROUTE_PATTERN else BACK_ON_ROUTE_PATTERN)
        }
    }

    private fun importRoute(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching { routeStore.importGpx(uri) }
            result
                .onSuccess { route ->
                    routeStore.setActive(route.id)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.route_imported, route.name, Format.distance(route.distanceM, prefs.units)),
                        Toast.LENGTH_LONG,
                    ).show()
                    refreshRoute()
                }
                .onFailure { e ->
                    showMessage(getString(R.string.route_import_failed, e.message ?: e.javaClass.simpleName))
                }
        }
    }

    /** Deactivates and deletes the active route (there is no separate route list to manage it from). */
    private fun clearRoute() {
        val id = prefs.activeRouteId
        if (id == null) {
            Toast.makeText(this, R.string.route_none_active, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { routeStore.delete(id) } }
            routeStore.setActive(null)
            Toast.makeText(this@MainActivity, R.string.route_cleared, Toast.LENGTH_SHORT).show()
            refreshRoute()
        }
    }

    @Suppress("DEPRECATION") // Context.VIBRATOR_SERVICE is the only option below API 31
    private fun vibrate(pattern: LongArray) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            // Vibration is a convenience; never let it break the ride screen.
        }
    }

    // ---------------------------------------------------------------- favourites

    /** The rider's last fix from whichever feed is active (recording service or idle GPS). */
    private fun currentFix(): GpsFix? = RideSession.state.value.lastFix ?: localLastFix

    private fun currentPosition(): LatLon? = currentFix()?.latLon

    private fun currentHeading(): Float? {
        val state = RideSession.state.value
        return if (state.status != RecordingStatus.IDLE) state.headingDeg else heading.headingDeg
    }

    private fun mapCentre(): LatLon? = map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }

    private fun fixedName(kind: FavoriteKind): String =
        getString(if (kind == FavoriteKind.HOME) R.string.fav_home else R.string.fav_work)

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /** Home / Work tap: set the place when unset, otherwise start guidance to it. */
    /**
     * Tap Home or Work: say where it is. Guidance lives inside the statistics card, so a rider who
     * folded that card away used to see nothing at all happen; unfold it, and say the distance out
     * loud as well so the tap is acknowledged before the first fix arrives. Not set yet: ask where
     * it is. Holding the button opens the menu that moves it (see [showFixedFavoriteMenu]).
     */
    private fun onFixedFavoriteClicked(kind: FavoriteKind) {
        lifecycleScope.launch {
            val fav = withContext(Dispatchers.IO) { runCatching { favorites.getByKind(kind) }.getOrNull() }
            if (isFinishing || isDestroyed) return@launch
            if (fav == null) {
                showPlacePicker(kind)
                return@launch
            }
            if (prefs.hudHidden) setHudHidden(false, animate = true)
            startGuidance(fav)
            val fix = currentFix()
            val message = if (fix == null) {
                getString(R.string.guidance_no_position, fav.name)
            } else {
                getString(
                    R.string.guidance_format,
                    fav.name,
                    Format.distance(Geo.distanceM(fix.latLon, fav.latLon), prefs.units),
                    directionLabel(Geo.bearingDeg(fix.latLon, fav.latLon), currentHeading()),
                )
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** "Set Home / Work": name field plus two ways to pick the location (my position, map centre). */
    // ---------------------------------------------------------------- folding the statistics card

    /**
     * Shows or hides the statistics card. The card slides up behind the status bar and fades out;
     * the chevron takes its place. [animate] is false on start-up, where the card must simply be in
     * the state the rider left it without a visible jump.
     */
    private fun setHudHidden(hidden: Boolean, animate: Boolean) {
        prefs.hudHidden = hidden
        val card = binding.hudPanel
        val chevron = binding.btnShowHud
        card.animate().cancel()
        chevron.animate().cancel()

        if (!animate) {
            card.isVisible = !hidden
            card.alpha = 1f
            card.translationY = 0f
            chevron.isVisible = hidden
            chevron.alpha = 1f
            return
        }

        if (hidden) {
            card.animate()
                .alpha(0f)
                .translationY(-card.height.toFloat())
                .setDuration(HUD_FOLD_MS)
                .withEndAction {
                    card.isVisible = false
                    card.translationY = 0f
                    chevron.alpha = 0f
                    chevron.isVisible = true
                    chevron.animate().alpha(1f).setDuration(HUD_FOLD_MS).start()
                }
                .start()
        } else {
            chevron.animate()
                .alpha(0f)
                .setDuration(HUD_FOLD_MS)
                .withEndAction {
                    chevron.isVisible = false
                    card.alpha = 0f
                    card.translationY = -card.height.toFloat()
                    card.isVisible = true
                    card.animate().alpha(1f).translationY(0f).setDuration(HUD_FOLD_MS).start()
                }
                .start()
        }
    }

    // ---------------------------------------------------------------- choosing a place on a map

    /**
     * Opens the map picker. [onPicked] receives the chosen point and the label the rider typed
     * (or the name the map gave the spot when they typed nothing).
     */
    private fun launchPlacePicker(title: String, name: String, at: LatLon?, onPicked: (LatLon, String) -> Unit) {
        onPlacePicked = onPicked
        showControlsForDialog()
        placePickerLauncher.launch(PlacePickerActivity.intent(this, title, name, at ?: currentPosition() ?: mapCentre()))
    }

    private fun showPlacePicker(kind: FavoriteKind) {
        showControlsForDialog()
        val defaultName = fixedName(kind)
        val b = DialogPlacePickerBinding.inflate(layoutInflater)
        b.bodyText.text = getString(R.string.fav_set_body, defaultName)
        b.nameInput.setText(defaultName)
        b.nameInput.setSelection(defaultName.length)

        // Leaving both emoji fields untouched means null here, and FavoritesRepository.upsertFixed
        // then keeps whatever emoji this kind already had -- so "didn't touch the icon" correctly
        // means "no change" rather than "clear it back to the kind's own icon."
        var customEmoji: String? = null
        var chipEmoji: String? = null
        EmojiPicker.populate(b.emojiGroup, null) { picked ->
            chipEmoji = picked
            if (picked != null) {
                customEmoji = null
                b.emojiCustomInput.setText("")
            }
        }
        b.emojiCustomInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.trim().orEmpty()
                customEmoji = typed.ifEmpty { null }
                if (typed.isNotEmpty() && b.emojiGroup.checkedChipId != View.NO_ID) {
                    chipEmoji = null
                    b.emojiGroup.clearCheck()
                }
            }
        })

        // A found address becomes the location to save, and the positive button stops meaning
        // "my position" once one is held: typing an address then pressing it must not silently
        // save the rider's own position instead.
        var foundAt: LatLon? = null
        var foundName: String? = null

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fav_set_title, defaultName))
            .setView(b.root)
            .setPositiveButton(R.string.fav_my_position, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        var searching = false
        fun runAddressSearch() {
            val query = b.addressInput.text?.toString()?.trim().orEmpty()
            if (query.isEmpty() || searching) return
            searching = true
            b.addressResult.isVisible = true
            b.addressResult.text = getString(R.string.picker_search_searching)
            lifecycleScope.launch {
                val results = withContext(Dispatchers.IO) {
                    runCatching { Geocoder.search(placeSearchClient, query) }.getOrNull()
                }
                searching = false
                if (isFinishing || isDestroyed) return@launch
                val first = results?.firstOrNull()
                when {
                    results == null -> b.addressResult.setText(R.string.picker_search_failed)
                    first == null -> b.addressResult.setText(R.string.picker_search_no_results)
                    else -> {
                        foundAt = first.at
                        foundName = first.name
                        b.addressResult.text = getString(R.string.fav_address_found, first.name)
                        // The button now saves the address, not the rider's position.
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(R.string.fav_use_address)
                    }
                }
            }
        }
        b.addressLayout.setEndIconOnClickListener { runAddressSearch() }
        b.addressInput.setOnEditorActionListener { _, actionId, event ->
            val submitted = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submitted) runAddressSearch()
            submitted
        }
        b.btnChooseOnMap.setOnClickListener {
            val typed = b.nameInput.text?.toString()?.trim().orEmpty()
            val label = typed.ifEmpty { defaultName }
            val emoji = customEmoji ?: chipEmoji
            dialog.dismiss()
            lifecycleScope.launch {
                // Start the picker where this place already is, so moving Home nudges the existing
                // pin instead of hunting for it again; otherwise start at the rider or the map.
                val existing = withContext(Dispatchers.IO) {
                    runCatching { favorites.getByKind(kind) }.getOrNull()
                }?.latLon
                if (isFinishing || isDestroyed) return@launch
                launchPlacePicker(getString(R.string.fav_set_title, defaultName), label, existing) { at, name ->
                    saveFixedFavorite(kind, name.ifEmpty { label }, at, emoji)
                }
            }
        }
        dialog.setOnShowListener {
            fun enteredName(): String {
                val typed = b.nameInput.text?.toString()?.trim() ?: ""
                return if (typed.isEmpty()) defaultName else typed
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // An address that was looked up wins: the rider asked for that spot, not this one.
                val address = foundAt
                if (address != null) {
                    val typed = b.nameInput.text?.toString()?.trim().orEmpty()
                    saveFixedFavorite(kind, typed.ifEmpty { defaultName }, address, customEmoji ?: chipEmoji)
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val pos = currentPosition()
                if (pos == null) {
                    toast(R.string.fav_no_position)
                    return@setOnClickListener
                }
                saveFixedFavorite(kind, enteredName(), pos, customEmoji ?: chipEmoji)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Home / Work long-press: Set from my position / Set from map centre / Clear (when set). */
    private fun showFixedFavoriteMenu(kind: FavoriteKind) {
        showControlsForDialog()
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { runCatching { favorites.getByKind(kind) }.getOrNull() }
            if (isFinishing || isDestroyed) return@launch
            val name = existing?.name ?: fixedName(kind)
            // Same choices as the first-time dialog: here, or the map picker (which opens on the
            // current pin, so moving Home is a nudge rather than a hunt).
            val items = mutableListOf(getString(R.string.fav_set_here), getString(R.string.fav_choose_on_map))
            if (existing != null) items += getString(R.string.fav_clear)
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(name)
                .setItems(items.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> {
                            val pos = currentPosition()
                            if (pos == null) toast(R.string.fav_no_position) else saveFixedFavorite(kind, name, pos)
                        }
                        1 -> launchPlacePicker(name, name, existing?.latLon) { at, picked ->
                            saveFixedFavorite(kind, picked.ifEmpty { name }, at)
                        }
                        2 -> existing?.let { deleteFavorite(it, null) }
                    }
                }
                .show()
        }
    }

    private fun saveFixedFavorite(kind: FavoriteKind, name: String, at: LatLon, emoji: String? = null) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { favorites.upsertFixed(kind, name, at.lat, at.lon, emoji) }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (saved == null) {
                toast(R.string.fav_save_failed)
                return@launch
            }
            Toast.makeText(this@MainActivity, getString(R.string.fav_saved, saved.name), Toast.LENGTH_SHORT).show()
            // Guidance to a place that just moved follows it.
            if (guidanceTarget?.kind == kind) startGuidance(saved)
            refreshFavoriteMarkers()
        }
    }

    /** Adds or updates a favourite from the dialog input, then refreshes the sheet if it is still open. */
    private fun saveFavorite(existing: Favorite?, input: FavoriteInput, sheet: FavoritesSheet) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    if (existing == null) {
                        favorites.add(
                            input.name, input.description, input.kind,
                            input.location.lat, input.location.lon, input.emoji,
                        )
                    } else {
                        val updated = existing.copy(
                            name = input.name,
                            description = input.description,
                            kind = input.kind,
                            lat = input.location.lat,
                            lon = input.location.lon,
                            emoji = input.emoji,
                        )
                        favorites.update(updated)
                        updated
                    }
                }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (saved == null) {
                toast(R.string.fav_save_failed)
                return@launch
            }
            if (guidanceTarget?.id == saved.id) startGuidance(saved)
            if (sheet.isAdded) sheet.reload()
            refreshFavoriteMarkers()
        }
    }

    private fun deleteFavorite(fav: Favorite, sheet: FavoritesSheet?) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { favorites.delete(fav.id) } }
            if (isFinishing || isDestroyed) return@launch
            if (guidanceTarget?.id == fav.id) stopGuidance()
            Toast.makeText(this@MainActivity, getString(R.string.fav_cleared, fav.name), Toast.LENGTH_SHORT).show()
            if (sheet != null && sheet.isAdded) sheet.reload()
            refreshFavoriteMarkers()
        }
    }

    /** Redraws every saved place's marker on the map; call after any add, edit or delete. */
    private fun refreshFavoriteMarkers() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { favorites.list() }.getOrDefault(emptyList()) }
            if (isFinishing || isDestroyed) return@launch
            mapController.setFavorites(list)
        }
    }

    private fun showFavoritesSheet() {
        if (supportFragmentManager.findFragmentByTag(FavoritesSheet.TAG) != null) return
        showControlsForDialog()
        FavoritesSheet().show(supportFragmentManager, FavoritesSheet.TAG)
    }

    // FavoritesSheet.Listener

    override fun favoritesPosition(): LatLon? = currentPosition()

    override fun onFavoriteChosen(fav: Favorite) {
        startGuidance(fav)
    }

    override fun onAddFavorite(sheet: FavoritesSheet) {
        showFavoriteDialog(null, sheet)
    }

    override fun onEditFavorite(sheet: FavoritesSheet, fav: Favorite) {
        showFavoriteDialog(fav, sheet)
    }

    /**
     * The add / edit dialog, with its "Choose on map" branch wired to the picker. The typed name,
     * description and kind survive the trip: only the location comes back from the map.
     */
    private fun showFavoriteDialog(existing: Favorite?, sheet: FavoritesSheet) {
        AddFavoriteDialog.show(
            context = this,
            existing = existing,
            myPosition = currentPosition(),
            mapCentre = mapCentre(),
            onSave = { input -> saveFavorite(existing, input, sheet) },
            onPickOnMap = { name, description, kind, emoji, startAt ->
                launchPlacePicker(getString(R.string.picker_title), name, startAt) { at, pickedName ->
                    saveFavorite(
                        existing,
                        FavoriteInput(name.ifEmpty { pickedName }, description, kind, at, emoji),
                        sheet,
                    )
                }
            },
        )
    }

    override fun onDeleteFavorite(sheet: FavoritesSheet, fav: Favorite) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_delete_title)
            .setMessage(getString(R.string.fav_delete_body, fav.name))
            .setPositiveButton(R.string.dialog_delete) { _, _ -> deleteFavorite(fav, sheet) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- guidance

    private fun startGuidance(fav: Favorite) {
        guidanceTarget = fav
        mapController.setTarget(fav.latLon, fav.kind)
        binding.guidanceRow.isVisible = true
        val fix = currentFix()
        if (fix != null) {
            updateGuidance(fix, currentHeading())
        } else {
            binding.guidanceText.text = getString(R.string.guidance_no_position, fav.name)
            mapController.setGuidanceLine(null, null)
        }
    }

    private fun stopGuidance() {
        guidanceTarget = null
        mapController.setTarget(null, null)
        mapController.setGuidanceLine(null, null)
        binding.guidanceRow.isVisible = false
    }

    /** Per fix: "→ {name} · {distance} · {direction}" and the straight line from the rider to the place. */
    private fun updateGuidance(fix: GpsFix, headingDeg: Float?) {
        val target = guidanceTarget ?: return
        val from = fix.latLon
        val to = target.latLon
        val distanceM = Geo.distanceM(from, to)
        val bearing = Geo.bearingDeg(from, to)
        binding.guidanceText.text = getString(
            R.string.guidance_format,
            target.name,
            Format.distance(distanceM, prefs.units),
            directionLabel(bearing, headingDeg),
        )
        mapController.setGuidanceLine(from, to)
    }

    /**
     * Direction to the target: relative to the heading when one is known (ahead, ahead-right, …),
     * otherwise the compass point of the bearing (N, NE, …).
     */
    private fun directionLabel(bearingDeg: Double, headingDeg: Float?): String {
        if (headingDeg == null) {
            val index = ((Geo.normalizeDeg(bearingDeg) + 22.5) / 45.0).toInt() % 8
            return getString(COMPASS_LABELS[index])
        }
        val diff = Geo.angleDiffDeg(headingDeg.toDouble(), bearingDeg)
        val a = abs(diff)
        val res = when {
            a <= 22.5 -> R.string.direction_ahead
            a >= 157.5 -> R.string.direction_behind
            diff > 0 -> when {
                a <= 67.5 -> R.string.direction_ahead_right
                a <= 112.5 -> R.string.direction_right
                else -> R.string.direction_behind_right
            }
            else -> when {
                a <= 67.5 -> R.string.direction_ahead_left
                a <= 112.5 -> R.string.direction_left
                else -> R.string.direction_behind_left
            }
        }
        return getString(res)
    }

    // ---------------------------------------------------------------- where am I

    /**
     * Refreshes the place line from the rendered tiles at most every [PLACE_MIN_INTERVAL_MS] and only
     * after moving [PLACE_MIN_MOVE_M] (or on the first fix, or while the last lookup found nothing).
     * Runs on the main thread: the query reads the rendered map.
     */
    private fun updatePlace(fix: GpsFix, nowMs: Long) {
        val last = placeQueriedAt
        if (last != null) {
            if (nowMs - placeQueriedAtMs < PLACE_MIN_INTERVAL_MS) return
            if (placeName != null && Geo.distanceM(last, fix.latLon) < PLACE_MIN_MOVE_M) return
        }
        placeQueriedAtMs = nowMs
        placeQueriedAt = fix.latLon
        val name = runCatching { mapController.placeNameAt(fix.latLon) }
            .onFailure { Log.w(TAG, "place lookup failed", it) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        placeName = name
        binding.placeText.isVisible = name != null
        if (name != null) binding.placeText.text = name
    }

    // ---------------------------------------------------------------- helpers

    private fun showMessage(message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    companion object {
        /** Long extra: id of a saved track to draw on the map (sent by [TracksActivity]). */
        const val EXTRA_VIEW_TRACK_ID = "view_track_id"

        /** [MapDownloadService.EXTRA_PHASE] value that marks a completed download. */
        private const val PHASE_DONE = "done"

        private const val OFF_ROUTE_DELAY_MS = 10_000L
        private const val GPS_RETRY_MS = 1_000L
        private const val SERVICE_START_GRACE_MS = 3_000L
        private const val VIEW_TRACK_ZOOM = 15.0
        private const val MIN_BOUNDS_DEG = 1e-5
        private const val FRAME_PADDING_DP = 32f
        private const val FRAME_EASE_MS = 800
        private const val TAG = "VeloMain"
        private const val PLACE_MIN_INTERVAL_MS = 2_000L
        private const val PLACE_MIN_MOVE_M = 10.0
        private const val FADE_MS = 200L
        private const val RIDING_MIN_RECHECK_MS = 100L
        /** Fold / unfold of the statistics card. */
        private const val HUD_FOLD_MS = 220L

        /** Instance-state key: id of the favourite being guided to, so rotation keeps the arrow. */
        private const val KEY_GUIDANCE_FAV = "guidance_fav_id"

        private const val RIDING_SPEED_STALE_MS = 5_000L
        private const val NIGHT_CHECK_SLACK_MS = 60_000L

        /** Refetch the weather chip once a reading is this old... */
        private const val WEATHER_MAX_AGE_MS = 30 * 60_000L
        /** ...or once the rider has moved this far from where it was fetched, whichever is sooner. */
        private const val WEATHER_REFRESH_DISTANCE_M = 20_000.0

        /** Compass points for 45° sectors starting at north. */
        private val COMPASS_LABELS = intArrayOf(
            R.string.compass_n, R.string.compass_ne, R.string.compass_e, R.string.compass_se,
            R.string.compass_s, R.string.compass_sw, R.string.compass_w, R.string.compass_nw,
        )

        /** Off-route: three long pulses. Back on route: one short buzz. (timings: off, on, off, on…) */
        private val OFF_ROUTE_PATTERN = longArrayOf(0L, 400L, 200L, 400L, 200L, 400L)
        private val BACK_ON_ROUTE_PATTERN = longArrayOf(0L, 150L)
    }
}
