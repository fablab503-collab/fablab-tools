package com.fablab503.velotrack.recording

/**
 * Decides when to ask "are you still riding?".
 *
 * A ride that is paused is not a ride that is over, so nothing here ever stops a recording by
 * itself: a phone left in a jersey pocket during a two-hour lunch has produced a two-hour ride
 * with a two-hour pause, which is annoying, while a ride ended automatically at the top of a col
 * because the rider stopped to eat is data destroyed. This class only decides when to *ask*.
 *
 * The clock is the caller's monotonic clock (`SystemClock.elapsedRealtime()` in the service), so
 * it keeps counting while the phone is asleep and is immune to the wall clock moving.
 *
 * Pure Kotlin (no Android imports) so the timing rules are unit tested on the JVM.
 */
class InactivityReminder(afterMs: Long) {

    /** Millis of no movement before the question is asked; 0 or less disables it entirely. */
    var afterMs: Long = afterMs
        set(value) {
            field = value
            asked = false
        }

    /** Last moment the rider was actually moving, or null until the first sample. */
    private var lastMovingMs: Long? = null

    /** True once the question has been asked for this stretch of stillness. */
    private var asked = false

    val enabled: Boolean get() = afterMs > 0L

    /** True while the question is on screen and has not been answered. */
    val pending: Boolean get() = asked

    /** The rider is moving: the ride is plainly still happening. */
    fun onMoving(nowMs: Long) {
        lastMovingMs = nowMs
        asked = false
    }

    /**
     * The rider is not moving (paused, auto-paused, or simply not producing fixes). Returns true
     * exactly once per stretch of stillness, at the moment the question should be asked.
     */
    fun onStill(nowMs: Long): Boolean {
        val since = lastMovingMs ?: run {
            // No movement seen yet: time it from the first still sample rather than from zero, so
            // a ride started indoors while waiting for a fix does not ask immediately.
            lastMovingMs = nowMs
            return false
        }
        if (!enabled || asked) return false
        if (nowMs - since < afterMs) return false
        asked = true
        return true
    }

    /** "Keep recording": start the clock again from here, and allow a later question. */
    fun snooze(nowMs: Long) {
        lastMovingMs = nowMs
        asked = false
    }

    /** A new ride, or a ride that ended. */
    fun reset() {
        lastMovingMs = null
        asked = false
    }

    /**
     * Millis until the question could be asked without further input, or null when it never will
     * be (disabled, already asked, or nothing sampled yet). Never negative.
     */
    fun nextCheckDelayMs(nowMs: Long): Long? {
        if (!enabled || asked) return null
        val since = lastMovingMs ?: return null
        return (since + afterMs - nowMs).coerceAtLeast(0L)
    }
}
