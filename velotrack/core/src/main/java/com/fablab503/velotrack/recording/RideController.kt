package com.fablab503.velotrack.recording

import android.content.Context

/** Thin helper for the UI; every call delegates to [RecordingService]'s companion functions. */
object RideController {
    fun startNew(context: Context) = RecordingService.start(context, null)

    /**
     * Adds to an existing ride instead of starting a new one. Used both by crash recovery ("this
     * ride was never finished") and by Continue in the ride list ("add today's leg to that tour").
     * The service handles the difference; from here they are the same request.
     */
    fun continueTrack(context: Context, trackId: Long) = RecordingService.start(context, trackId)

    fun pause(context: Context) = RecordingService.pause(context)

    fun resume(context: Context) = RecordingService.resume(context)

    fun stop(context: Context) = RecordingService.stop(context)
}
