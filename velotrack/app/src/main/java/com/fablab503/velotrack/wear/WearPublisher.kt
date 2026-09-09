package com.fablab503.velotrack.wear

import android.content.Context
import android.util.Log
import com.fablab503.velotrack.model.RideState
import com.fablab503.velotrack.model.Units
import com.fablab503.velotrack.recording.RideSession
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.sync.WearSync
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Mirrors the ride onto a paired watch.
 *
 * This observes [RideSession] rather than living inside RecordingService, so the recording path is
 * untouched: the service writes its state exactly as before and does not know a watch exists. The
 * collector runs for the life of the process, which is the same life as the recording service that
 * keeps the process up.
 *
 * Publishing is throttled to [MIN_INTERVAL_MS]. A ride emits a new state on every GPS fix, and the
 * Data Layer is a synchronised store, not a firehose - pushing at 1 Hz is plenty for a display a
 * rider glances at, and it keeps the Bluetooth link quiet.
 */
object WearPublisher {

    private const val TAG = "WearPublisher"
    private const val MIN_INTERVAL_MS = 1000L

    private var scope: CoroutineScope? = null
    private var lastPublishedAt = 0L

    /** Starts mirroring. Safe to call twice; the second call is ignored. */
    fun start(context: Context) {
        if (scope != null) return
        val appContext = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            RideSession.state.collectLatest { state ->
                val now = System.currentTimeMillis()
                val idle = state.status.name == WearSync.STATUS_IDLE
                // Always publish a change of status immediately; only rate-limit the steady stream
                // of fixes during a ride, where one update a second is all a watch face can show.
                if (!idle && now - lastPublishedAt < MIN_INTERVAL_MS) return@collectLatest
                lastPublishedAt = now
                publish(appContext, state)
            }
        }
    }

    /** Pushes the current state on demand, for a watch that has just opened the app. */
    fun publishNow(context: Context) {
        val newScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        newScope.launch { publish(context.applicationContext, RideSession.state.value) }
    }

    private fun publish(context: Context, state: RideState) {
        val imperial = try {
            Prefs(context).units == Units.IMPERIAL
        } catch (e: Exception) {
            false
        }
        val request = PutDataMapRequest.create(WearSync.PATH_RIDE).apply {
            dataMap.putString(WearSync.KEY_STATUS, state.status.name)
            dataMap.putFloat(WearSync.KEY_SPEED_MPS, state.speedMps ?: 0f)
            dataMap.putDouble(WearSync.KEY_DISTANCE_M, state.stats.distanceM)
            dataMap.putLong(WearSync.KEY_ELAPSED_MS, state.stats.elapsedMs)
            dataMap.putLong(WearSync.KEY_MOVING_MS, state.stats.movingMs)
            dataMap.putBoolean(WearSync.KEY_HAS_FIX, state.gps.hasFix)
            dataMap.putInt(WearSync.KEY_SATELLITES, state.gps.satellitesUsed)
            dataMap.putBoolean(WearSync.KEY_IMPERIAL, imperial)
            // Without this the Data Layer discards an identical payload and a stopped rider's
            // watch would freeze rather than keep ticking.
            dataMap.putLong(WearSync.KEY_UPDATED_AT, System.currentTimeMillis())
        }
        val req = request.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(context).putDataItem(req)
        } catch (e: Exception) {
            // No Play Services, no paired watch, or the link is down. None of that is an error the
            // rider needs to hear about, and none of it may interrupt the ride.
            Log.d(TAG, "ride not mirrored to a watch: ${e.message}")
        }
    }
}
