package com.fablab503.velotrack.recording

import android.content.Context

/** Thin helper for the UI; every call delegates to [RecordingService]'s companion functions. */
object RideController {
    fun startNew(context: Context) = RecordingService.start(context, null)

    fun resumeUnfinished(context: Context, trackId: Long) = RecordingService.start(context, trackId)

    fun pause(context: Context) = RecordingService.pause(context)

    fun resume(context: Context) = RecordingService.resume(context)

    fun stop(context: Context) = RecordingService.stop(context)
}
