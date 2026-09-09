package com.fablab503.velotrack.wear

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.sync.WearSync
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The whole watch app: the ride the phone is recording, and the buttons to control it.
 *
 * Nothing is computed here. Every number arrives from the phone over the Data Layer, which means
 * the watch cannot disagree with the handset about how far you have gone - a real hazard if both
 * sides ran their own copy of the statistics.
 *
 * Two channels, for two different jobs. Ride state arrives as a **DataItem**, which the Data Layer
 * stores, so a watch that was asleep or out of range is handed the current ride the moment it
 * reconnects. Button taps leave as **Messages**, which are not stored, because replaying a stale
 * "stop" on reconnect would end a ride the rider is still on.
 */
class WearMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private lateinit var speedText: TextView
    private lateinit var speedUnit: TextView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var statusText: TextView
    private lateinit var primaryButton: Button
    private lateinit var stopButton: Button

    /** Null until the phone has told us anything: that is a different screen from a ride at zero. */
    private var ride: WearSync.Ride? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_main)

        speedText = findViewById(R.id.speedText)
        speedUnit = findViewById(R.id.speedUnit)
        distanceText = findViewById(R.id.distanceText)
        timeText = findViewById(R.id.timeText)
        statusText = findViewById(R.id.statusText)
        primaryButton = findViewById(R.id.primaryButton)
        stopButton = findViewById(R.id.stopButton)

        primaryButton.setOnClickListener { onPrimaryClicked() }
        stopButton.setOnClickListener { send(WearSync.CMD_STOP) }

        render()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        // The stored DataItem is only delivered on change, so a watch opened mid-ride would show
        // nothing until the next GPS fix. Read what is already there, then ask for a fresh push.
        readStoredRide()
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
            ride = DataMapItem.fromDataItem(event.dataItem).dataMap.toRide()
        }
        render()
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
                ride = found
                render()
            }
        }
    }

    private fun onPrimaryClicked() {
        val current = ride
        val command = when {
            current == null -> WearSync.CMD_START
            current.status == WearSync.STATUS_RECORDING -> WearSync.CMD_PAUSE
            current.isActive -> WearSync.CMD_RESUME
            else -> WearSync.CMD_START
        }
        send(command)
    }

    /**
     * Sends a command to every connected node. There is normally exactly one - the paired phone -
     * but addressing them all avoids having to guess which node holds the app.
     */
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

    private fun render() {
        val current = ride
        if (current == null) {
            // Never heard from the phone. Saying so is more use than a convincing row of zeros.
            speedText.text = getString(R.string.wear_no_value)
            speedUnit.text = WearFormat.speedUnit(false)
            distanceText.text = getString(R.string.wear_no_value)
            timeText.text = getString(R.string.wear_no_value)
            statusText.text = getString(R.string.wear_waiting_for_phone)
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
        timeText.text = WearFormat.duration(current.elapsedMs)

        statusText.text = when (current.status) {
            WearSync.STATUS_RECORDING -> getString(R.string.wear_recording)
            WearSync.STATUS_PAUSED -> getString(R.string.wear_paused)
            WearSync.STATUS_AUTO_PAUSED -> getString(R.string.wear_auto_paused)
            else ->
                if (current.hasFix) getString(R.string.wear_ready)
                else getString(R.string.wear_searching)
        }

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
    }
}
