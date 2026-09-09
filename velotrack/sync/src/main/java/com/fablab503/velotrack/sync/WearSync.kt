package com.fablab503.velotrack.sync

/**
 * The contract between the phone and the watch.
 *
 * Two channels, because they have different needs. Ride state goes phone to watch as a **DataItem**
 * (`PATH_RIDE`): the Data Layer keeps the last value, so a watch that was out of range or asleep
 * gets the current ride the moment it reconnects, without asking. Commands go watch to phone as a
 * **Message** (`PATH_COMMAND`): a tap is an event, not a state, and replaying a stale "stop" on
 * reconnect would be wrong.
 *
 * Everything here is plain strings and primitives on purpose. The Data Layer matches keys by
 * string, and a key that exists on one side only fails silently - no exception, no log, the watch
 * just shows nothing. Both modules depending on this file is what stops that happening.
 *
 * The Data Layer also requires the two apps to share an application ID *and* a signing certificate.
 * That is why the watch module carries the same `applicationId` and the same `.debug` suffix as the
 * phone: a debug watch talks to a debug phone, a Play-signed watch to a Play-signed phone, and the
 * pairs never cross.
 */
object WearSync {

    /** Phone to watch: the current ride, as a DataItem so it survives a disconnection. */
    const val PATH_RIDE = "/velotrack/ride"

    /** Watch to phone: one of the `CMD_` values below, as a Message. */
    const val PATH_COMMAND = "/velotrack/command"

    // Keys inside the ride DataItem.
    const val KEY_STATUS = "status"
    const val KEY_SPEED_MPS = "speed_mps"
    const val KEY_DISTANCE_M = "distance_m"
    const val KEY_ELAPSED_MS = "elapsed_ms"
    const val KEY_MOVING_MS = "moving_ms"
    const val KEY_HAS_FIX = "has_fix"
    const val KEY_SATELLITES = "satellites"
    const val KEY_IMPERIAL = "imperial"

    /**
     * Milliseconds since the epoch, rewritten on every publish. The Data Layer drops a DataItem
     * whose bytes are byte-for-byte identical to the one already stored, so without a value that
     * always changes, a stationary rider's watch would stop updating its clock.
     */
    const val KEY_UPDATED_AT = "updated_at"

    // Commands.
    const val CMD_START = "start"
    const val CMD_PAUSE = "pause"
    const val CMD_RESUME = "resume"
    const val CMD_STOP = "stop"

    /** Asks the phone to publish the current state now, for a watch that just opened the app. */
    const val CMD_SYNC = "sync"

    // Status values. Deliberately the same spelling as RecordingStatus so the mapping stays obvious,
    // but written out here so the watch module does not need to depend on the phone's model classes.
    const val STATUS_IDLE = "IDLE"
    const val STATUS_RECORDING = "RECORDING"
    const val STATUS_AUTO_PAUSED = "AUTO_PAUSED"
    const val STATUS_PAUSED = "PAUSED"

    /** What the watch renders. Built on the phone, read on the watch. */
    data class Ride(
        val status: String = STATUS_IDLE,
        val speedMps: Float = 0f,
        val distanceM: Double = 0.0,
        val elapsedMs: Long = 0L,
        val movingMs: Long = 0L,
        val hasFix: Boolean = false,
        val satellites: Int = 0,
        val imperial: Boolean = false,
    ) {
        /** True while the ride is live in any form, which is when Pause and Stop make sense. */
        val isActive: Boolean
            get() = status == STATUS_RECORDING || status == STATUS_PAUSED || status == STATUS_AUTO_PAUSED
    }
}
