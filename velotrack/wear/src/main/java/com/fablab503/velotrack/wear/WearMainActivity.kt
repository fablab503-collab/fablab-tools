package com.fablab503.velotrack.wear

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fablab503.velotrack.model.RecordingStatus
import com.fablab503.velotrack.recording.RideController
import com.fablab503.velotrack.recording.RideSession
import com.fablab503.velotrack.sync.WearSync
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The watch app. It runs in one of two modes and switches between them by itself.
 *
 * **Mirror** - a phone is reachable and recording. Every number comes from the phone, and the
 * buttons send it commands. Two devices computing their own distance would eventually disagree,
 * and a bike computer that argues with itself is worse than one that shows nothing.
 *
 * **On the watch** - no phone. The watch records with its own GPS through exactly the same
 * [RideController] and [RideSession] the phone uses, because that engine now lives in :core. The
 * ride lands in the watch's own database and is a real ride, not a cache of one.
 *
 * Which mode is live is never hidden: the dot is green when the phone is there and red when it is
 * not, and the label beside it names the device actually doing the recording.
 */
class WearMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private lateinit var speedText: TextView
    private lateinit var speedUnit: TextView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var statusText: TextView
    private lateinit var sourceText: TextView
    private lateinit var connectionDot: View
    private lateinit var primaryButton: Button
    private lateinit var stopButton: Button

    /** Null until the phone has said something. Different from a ride sitting at zero. */
    private var phoneRide: WearSync.Ride? = null
    private var phoneConnected = false

    /** Set once the rider starts a ride here, so a phone reconnecting cannot steal the display. */
    private var recordingLocally = false

    /**
     * The ride clock, held here rather than read straight from the engine.
     *
     * `movingMs` only advances when a GPS fix arrives, so a rider in a tunnel or stopped at lights
     * would watch a dead clock. These two carry the last figure the engine gave and the wall-clock
     * instant it arrived, so the display can keep counting between fixes while the ride is running,
     * and freeze the moment it is not.
     */
    private var clockBaseMs = 0L
    private var clockBaseWallMs = 0L
    private var clockRunning = false

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLocalRide() else statusText.setText(R.string.wear_needs_location)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_main)

        speedText = findViewById(R.id.speedText)
        speedUnit = findViewById(R.id.speedUnit)
        distanceText = findViewById(R.id.distanceText)
        timeText = findViewById(R.id.timeText)
        statusText = findViewById(R.id.statusText)
        sourceText = findViewById(R.id.sourceText)
        connectionDot = findViewById(R.id.connectionDot)
        primaryButton = findViewById(R.id.primaryButton)
        stopButton = findViewById(R.id.stopButton)

        primaryButton.setOnClickListener { onPrimaryClicked() }
        stopButton.setOnClickListener { onStopClicked() }

        // The local engine publishes on every fix whether or not anything is recording, so this is
        // also what drives the display in "on the watch" mode.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RideSession.state.collect { render() }
            }
        }
        // Fixes arrive about once a second at best and not at all without sky. The clock is redrawn
        // on its own beat so it never looks stalled while the ride is genuinely running.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(1000L)
                    if (clockRunning) timeText.text = WearFormat.duration(rideClockMs())
                }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        readStoredRide()
        refreshConnection()
        send(WearSync.CMD_SYNC)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != WearSync.PATH_RIDE) continue
            phoneRide = DataMapItem.fromDataItem(event.dataItem).dataMap.toRide()
            phoneConnected = true
        }
        render()
    }

    /**
     * Asks which nodes are reachable. This is a poll on purpose and only on resume: the watch is
     * either in front of the rider or it is not, and holding a capability listener open through a
     * whole ride costs battery for a dot.
     */
    private fun refreshConnection() {
        lifecycleScope.launch {
            val connected = withContext(Dispatchers.IO) {
                try {
                    Wearable.getCapabilityClient(this@WearMainActivity)
                        .getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                        .await().nodes.isNotEmpty()
                } catch (e: Exception) {
                    try {
                        Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await().isNotEmpty()
                    } catch (e2: Exception) {
                        false
                    }
                }
            }
            phoneConnected = connected
            if (!connected) phoneRide = null
            render()
        }
    }

    private fun readStoredRide() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                try {
                    Wearable.getDataClient(this@WearMainActivity).dataItems.await()
                        .firstOrNull { it.uri.path == WearSync.PATH_RIDE }
                        ?.let { DataMapItem.fromDataItem(it).dataMap.toRide() }
                } catch (e: Exception) {
                    Log.d(TAG, "no stored ride yet: ${e.message}")
                    null
                }
            }
            if (found != null) {
                phoneRide = found
                render()
            }
        }
    }

    // ---- controls -------------------------------------------------------------------------------

    private fun onPrimaryClicked() {
        if (useLocal()) {
            when (RideSession.state.value.status) {
                RecordingStatus.RECORDING -> RideController.pause(this)
                RecordingStatus.PAUSED, RecordingStatus.AUTO_PAUSED -> RideController.resume(this)
                RecordingStatus.IDLE -> ensurePermissionThenStart()
            }
            render()
            return
        }
        val current = phoneRide
        send(
            when {
                current == null -> WearSync.CMD_START
                current.status == WearSync.STATUS_RECORDING -> WearSync.CMD_PAUSE
                current.isActive -> WearSync.CMD_RESUME
                else -> WearSync.CMD_START
            }
        )
    }

    private fun onStopClicked() {
        if (useLocal()) {
            RideController.stop(this)
            recordingLocally = false
            render()
        } else {
            send(WearSync.CMD_STOP)
        }
    }

    private fun ensurePermissionThenStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startLocalRide() else requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startLocalRide() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) {
            // Some watches have no GNSS at all. Saying so beats a ride that records nothing.
            statusText.setText(R.string.wear_no_gps_hardware)
            return
        }
        recordingLocally = true
        RideController.startNew(this)
        render()
    }

    /** True when the watch's own engine owns the display: recording here, or no phone to ask. */
    private fun useLocal(): Boolean =
        recordingLocally || RideSession.state.value.status != RecordingStatus.IDLE || !phoneConnected

    private fun send(command: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val nodes = Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await()
                    if (nodes.isEmpty()) {
                        Log.i(TAG, "no connected phone for '$command'")
                        return@withContext
                    }
                    val client = Wearable.getMessageClient(this@WearMainActivity)
                    for (node in nodes) {
                        client.sendMessage(node.id, WearSync.PATH_COMMAND, command.toByteArray()).await()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "command '$command' did not reach the phone: ${e.message}")
                }
            }
        }
    }

    // ---- rendering ------------------------------------------------------------------------------

    /**
     * Records what the engine last reported and whether the ride is running, so [rideClockMs] can
     * carry on between updates. Called on every render so a status change takes effect at once.
     */
    private fun updateClock(movingMs: Long, running: Boolean, idle: Boolean) {
        if (idle) {
            // A finished or not-yet-started ride: the next one begins at zero.
            clockBaseMs = 0L
            clockBaseWallMs = System.currentTimeMillis()
            clockRunning = false
            return
        }
        // Never let the clock run backwards. movingMs only advances on a GPS fix the filter
        // accepts, so it lags the wall clock; re-basing straight onto it made a pause visibly
        // reset the ride to 0:00.
        val base = maxOf(movingMs, rideClockMs())
        if (running != clockRunning || base > clockBaseMs) {
            clockBaseMs = base
            clockBaseWallMs = System.currentTimeMillis()
        }
        clockRunning = running
    }

    private fun rideClockMs(): Long =
        if (clockRunning) clockBaseMs + (System.currentTimeMillis() - clockBaseWallMs) else clockBaseMs

    private fun render() {
        connectionDot.backgroundTintList =
            ColorStateList.valueOf(if (phoneConnected) DOT_CONNECTED else DOT_ALONE)
        if (useLocal()) renderLocal() else renderPhone()
    }

    private fun renderLocal() {
        val state = RideSession.state.value
        val imperial = phoneRide?.imperial ?: false
        sourceText.setText(R.string.wear_source_watch)
        speedText.text = WearFormat.speed(state.speedMps ?: 0f, imperial)
        speedUnit.text = WearFormat.speedUnit(imperial)
        distanceText.text = getString(
            R.string.wear_distance_value,
            WearFormat.distance(state.stats.distanceM, imperial),
            WearFormat.distanceUnit(imperial),
        )
        // Moving time, not elapsed: elapsed is wall-clock and keeps counting through a pause,
        // which reads as a stuck app on a wrist.
        updateClock(
            state.stats.movingMs,
            running = state.status == RecordingStatus.RECORDING,
            idle = state.status == RecordingStatus.IDLE,
        )
        timeText.text = WearFormat.duration(rideClockMs())

        statusText.setText(
            when (state.status) {
                RecordingStatus.RECORDING -> R.string.wear_recording
                RecordingStatus.PAUSED -> R.string.wear_paused
                RecordingStatus.AUTO_PAUSED -> R.string.wear_auto_paused
                RecordingStatus.IDLE -> if (state.gps.hasFix) R.string.wear_ready else R.string.wear_searching
            }
        )
        primaryButton.setText(
            when (state.status) {
                RecordingStatus.RECORDING -> R.string.wear_pause
                RecordingStatus.PAUSED, RecordingStatus.AUTO_PAUSED -> R.string.wear_resume
                RecordingStatus.IDLE -> R.string.wear_start
            }
        )
        stopButton.visibility =
            if (state.status == RecordingStatus.IDLE) View.GONE else View.VISIBLE
    }

    private fun renderPhone() {
        val current = phoneRide
        sourceText.setText(R.string.wear_source_phone)
        if (current == null) {
            speedText.text = getString(R.string.wear_no_value)
            speedUnit.text = WearFormat.speedUnit(false)
            distanceText.text = getString(R.string.wear_no_value)
            timeText.text = getString(R.string.wear_no_value)
            statusText.setText(R.string.wear_waiting_for_phone)
            primaryButton.setText(R.string.wear_start)
            stopButton.visibility = View.GONE
            return
        }
        val imperial = current.imperial
        speedText.text = WearFormat.speed(current.speedMps, imperial)
        speedUnit.text = WearFormat.speedUnit(imperial)
        distanceText.text = getString(
            R.string.wear_distance_value,
            WearFormat.distance(current.distanceM, imperial),
            WearFormat.distanceUnit(imperial),
        )
        // Same reason as the local path: the clock must stop when the ride is paused.
        updateClock(
            current.movingMs,
            running = current.status == WearSync.STATUS_RECORDING,
            idle = !current.isActive,
        )
        timeText.text = WearFormat.duration(rideClockMs())
        statusText.setText(
            when (current.status) {
                WearSync.STATUS_RECORDING -> R.string.wear_recording
                WearSync.STATUS_PAUSED -> R.string.wear_paused
                WearSync.STATUS_AUTO_PAUSED -> R.string.wear_auto_paused
                else -> if (current.hasFix) R.string.wear_ready else R.string.wear_searching
            }
        )
        primaryButton.setText(
            when {
                current.status == WearSync.STATUS_RECORDING -> R.string.wear_pause
                current.isActive -> R.string.wear_resume
                else -> R.string.wear_start
            }
        )
        stopButton.visibility = if (current.isActive) View.VISIBLE else View.GONE
    }

    private companion object {
        const val TAG = "WearMain"

        /** Advertised by the phone app in res/values/wear.xml. */
        const val CAPABILITY_PHONE = "velotrack_phone"

        val DOT_CONNECTED = Color.parseColor("#FF7BE38B")
        val DOT_ALONE = Color.parseColor("#FFFF8A80")
    }
}
