package com.fablab503.velotrack.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityMainBinding
import com.fablab503.velotrack.location.GpsSource
import com.fablab503.velotrack.location.HeadingEstimator
import com.fablab503.velotrack.map.MapController
import com.fablab503.velotrack.model.CameraMode
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
import com.fablab503.velotrack.storage.MapFileStore
import com.fablab503.velotrack.storage.RouteStore
import com.fablab503.velotrack.storage.TrackDatabase
import com.fablab503.velotrack.storage.TrackRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import java.io.File
import java.util.Date
import kotlin.math.roundToInt

/**
 * Map + HUD screen. The MapView is created programmatically and inserted under the HUD overlay.
 *
 * Position source: while [RideSession] is idle and the recording service is not running, this
 * activity runs its own [GpsSource] for the puck; while a ride is recorded, the service is the only
 * GPS client and the UI renders `RideSession.state` (last fix, heading, stats, satellites).
 */
class MainActivity : AppCompatActivity(), GpsSource.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var mapFileStore: MapFileStore
    private lateinit var routeStore: RouteStore
    private lateinit var gpsSource: GpsSource
    private lateinit var mapView: MapView
    private lateinit var mapController: MapController

    private var map: MapLibreMap? = null
    private val heading = HeadingEstimator()
    private val handler = Handler(Looper.getMainLooper())

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

    // Map file and route.
    private var styleRequested = false
    private var loadedMapPath: String? = null
    private var loadedRouteKey: String? = null
    private var routeFollower: RouteFollower? = null
    private var offRoute = false
    private var routeLoadJob: Job? = null

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

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClockAndBattery()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    private val gpsRetryRunnable = Runnable { refreshLocalGps() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = TrackDatabase.get(this)
        repo = TrackRepository(db)
        mapFileStore = MapFileStore(this, prefs)
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
            .setPrefetchesTiles(false)
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
            mapController.onMapReady(m)
            showPendingTrack()
        }

        setupButtons()
        updateModeButton()
        pendingViewTrackId = viewTrackIdFrom(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RideSession.state.collect { render(it) }
            }
        }

        if (!hasFineLocation()) requestLocationPermissions(null)
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
        applyScreenMode()
        updateGpsBanner()
        refreshMapFile()
        refreshRoute()
        updateModeButton()
        render(RideSession.state.value)
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        activityStarted = false
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(gpsRetryRunnable)
        refreshLocalGps() // stops the local feed while not visible
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
        val basePaddingTop = topPanel.paddingTop
        val basePaddingBottom = controls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            topPanel.updatePadding(top = basePaddingTop + bars.top)
            controls.updatePadding(bottom = basePaddingBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupButtons() {
        binding.btnRecord.setOnClickListener { onRecordClicked() }
        binding.btnPause.setOnClickListener {
            if (RideSession.state.value.status == RecordingStatus.PAUSED) {
                RideController.resume(this)
            } else {
                RideController.pause(this)
            }
        }
        binding.btnStop.setOnClickListener { confirmStop() }
        binding.btnRecenter.setOnClickListener {
            mapController.recenter()
            updateModeButton()
        }
        binding.btnToggle3d.setOnClickListener { toggleFollowMode() }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.btnGpsSettings.setOnClickListener { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        binding.btnMapFiles.setOnClickListener { startActivity(Intent(this, MapFilesActivity::class.java)) }
    }

    private fun toggleFollowMode() {
        val next = if (prefs.followMode == CameraMode.FOLLOW_3D) CameraMode.FOLLOW_2D else CameraMode.FOLLOW_3D
        prefs.followMode = next
        mapController.cameraMode = next
        updateModeButton()
    }

    private fun updateModeButton() {
        val label = if (prefs.followMode == CameraMode.FOLLOW_3D) R.string.btn_mode_3d else R.string.btn_mode_2d
        binding.btnToggle3d.text = getString(label)
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.btnMenu)
        popup.inflate(R.menu.main_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_tracks -> startActivity(Intent(this, TracksActivity::class.java))
                R.id.action_import_route -> routeLauncher.launch(arrayOf("*/*"))
                R.id.action_clear_route -> clearRoute()
                R.id.action_map_files -> startActivity(Intent(this, MapFilesActivity::class.java))
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
    private fun launchService(start: () -> Unit) {
        stopLocalGps()
        start()
        // If the service could not start (permission revoked, etc.) the state stays IDLE and the
        // local feed is restored by this retry.
        handler.removeCallbacks(gpsRetryRunnable)
        handler.postDelayed(gpsRetryRunnable, SERVICE_START_GRACE_MS)
    }

    private fun confirmStop() {
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
            MaterialAlertDialogBuilder(this@MainActivity)
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
    }

    override fun onFix(fix: GpsFix) {
        if (!localGpsRunning) return
        localLastFix = fix
        val headingDeg = heading.update(fix)
        val nowMs = System.currentTimeMillis()
        mapController.updatePosition(fix, headingDeg, nowMs)
        updateRoute(fix.latLon, nowMs)
        renderLiveHud(fix, localGpsStatus)
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

        binding.btnRecord.isVisible = !active
        binding.btnPause.isVisible = active
        binding.btnStop.isVisible = active
        if (active) {
            val paused = status == RecordingStatus.PAUSED
            binding.btnPause.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
            binding.btnPause.contentDescription = getString(if (paused) R.string.btn_resume else R.string.btn_pause)
        }

        binding.statusText.isVisible = active
        when (status) {
            RecordingStatus.RECORDING -> {
                binding.statusText.text = getString(R.string.hud_status_recording)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.record_red))
            }
            RecordingStatus.AUTO_PAUSED -> {
                binding.statusText.text = getString(R.string.hud_status_auto_paused)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.pause_amber))
            }
            RecordingStatus.PAUSED -> {
                binding.statusText.text = getString(R.string.hud_status_paused)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.pause_amber))
            }
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
            renderLiveHud(fix, state.gps)
            if (fix != null && fix !== lastRenderedFix) {
                lastRenderedFix = fix
                val nowMs = System.currentTimeMillis()
                mapController.updatePosition(fix, state.headingDeg, nowMs)
                updateRoute(fix.latLon, nowMs)
            }
            syncTrackOnMap(state)
        } else {
            renderLiveHud(localLastFix, localGpsStatus)
        }

        refreshLocalGps()
        updateGpsBanner()
    }

    private fun renderLiveHud(fix: GpsFix?, gps: GpsStatus) {
        val units = prefs.units
        binding.speedUnit.text = Format.speedUnit(units)
        val speed = fix?.speedMps
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
    }

    private fun applyScreenMode() {
        val lp = window.attributes
        when (prefs.screenMode) {
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

    // ---------------------------------------------------------------- map file

    private fun refreshMapFile() {
        val active = mapFileStore.activeMap()
        binding.noMapOverlay.isVisible = active == null
        val path = active?.absolutePath
        if (styleRequested && path == loadedMapPath) return
        styleRequested = true
        loadedMapPath = path
        mapController.loadStyle(active) { error -> onStyleLoaded(active, error) }
    }

    private fun onStyleLoaded(file: File?, error: String?) {
        if (error == null || isFinishing || isDestroyed) return
        if (file != null) {
            // Corrupt or unsupported archive: deactivate it, tell the user, fall back to the black base.
            mapFileStore.setActive(null)
            loadedMapPath = null
            binding.noMapOverlay.isVisible = true
            showMessage(getString(R.string.map_load_failed, error))
            mapController.loadStyle(null) { }
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
                binding.routeText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.hud_text))
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
            binding.routeText.setTextColor(ContextCompat.getColor(this, R.color.warning))
        } else {
            binding.routeText.text = getString(R.string.route_remaining, Format.distance(progress.distanceRemainingM, units))
            binding.routeText.setTextColor(ContextCompat.getColor(this, R.color.hud_text))
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

        private const val OFF_ROUTE_DELAY_MS = 10_000L
        private const val GPS_RETRY_MS = 1_000L
        private const val SERVICE_START_GRACE_MS = 3_000L
        private const val VIEW_TRACK_ZOOM = 15.0
        private const val MIN_BOUNDS_DEG = 1e-5
        private const val FRAME_PADDING_DP = 32f
        private const val FRAME_EASE_MS = 800

        /** Off-route: three long pulses. Back on route: one short buzz. (timings: off, on, off, on…) */
        private val OFF_ROUTE_PATTERN = longArrayOf(0L, 400L, 200L, 400L, 200L, 400L)
        private val BACK_ON_ROUTE_PATTERN = longArrayOf(0L, 150L)
    }
}
