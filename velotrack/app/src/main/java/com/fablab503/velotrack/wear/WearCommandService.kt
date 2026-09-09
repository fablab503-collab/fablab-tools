package com.fablab503.velotrack.wear

import android.util.Log
import com.fablab503.velotrack.recording.RideController
import com.fablab503.velotrack.sync.WearSync
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the watch's button taps and turns them into the same calls the phone's own buttons make.
 *
 * The system starts this service when a message arrives, so the watch can begin a ride while the
 * phone's screen is off and the app is not running. [RideController] is the phone UI's own entry
 * point, so a ride started from the wrist is identical in every way to one started on the handset -
 * same foreground service, same notification, same database row.
 */
class WearCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearSync.PATH_COMMAND) {
            super.onMessageReceived(event)
            return
        }
        val command = String(event.data, Charsets.UTF_8)
        Log.i(TAG, "watch command: $command")
        when (command) {
            WearSync.CMD_START -> RideController.startNew(this)
            WearSync.CMD_PAUSE -> RideController.pause(this)
            WearSync.CMD_RESUME -> RideController.resume(this)
            WearSync.CMD_STOP -> RideController.stop(this)
            WearSync.CMD_SYNC -> Unit // handled by the publish below
            else -> Log.w(TAG, "unknown command from watch: $command")
        }
        // Answer every command, including an unknown one, with the current truth. A command that
        // did not take effect - stop with nothing recording, say - would otherwise leave the watch
        // showing whatever it optimistically drew.
        //
        // The recording service starts asynchronously, so RideSession has not caught up yet here.
        // That is deliberately not handled by publishing twice: WearPublisher collects RideSession
        // for the life of the process, so the new status goes out by itself a moment later.
        WearPublisher.publishNow(this)
    }

    private companion object {
        const val TAG = "WearCommandService"
    }
}
