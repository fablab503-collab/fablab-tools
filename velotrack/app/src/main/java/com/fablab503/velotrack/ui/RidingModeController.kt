package com.fablab503.velotrack.ui

/**
 * Decides when the map controls hide while riding. Pure Kotlin (no Android imports) so the timing
 * rules are unit tested on the JVM.
 *
 * Rules: the controls hide once the effective speed has stayed at or above [enterSpeedMps] for
 * [enterAfterMs]; they show again once the speed has stayed below it for [exitAfterMs], or on any
 * touch, after which they hide again [touchShowMs] later while the rider is still fast. All clocks
 * are the caller's monotonic clock (`SystemClock.elapsedRealtime()` in the app).
 */
class RidingModeController(
    private val enterSpeedMps: Float = 5f / 3.6f,
    private val enterAfterMs: Long = 3_000,
    private val exitAfterMs: Long = 2_000,
    private val touchShowMs: Long = 8_000,
) {
    /** True when the controls should be hidden. */
    var hidden: Boolean = false
        private set

    /** Start of the current run of fast samples, or null while slow / unknown. */
    private var fastSinceMs: Long? = null

    /** Start of the current run of slow samples, or null while fast. */
    private var slowSinceMs: Long? = null

    /** Last touch that must keep the controls visible, or null once consumed. */
    private var touchedAtMs: Long? = null

    /**
     * Feed the effective speed (null = unknown → treated as 0) with a monotonic clock; returns true
     * when [hidden] changed.
     */
    fun onSpeed(speedMps: Float?, nowMs: Long): Boolean {
        val fast = (speedMps ?: 0f) >= enterSpeedMps
        if (fast) {
            slowSinceMs = null
            if (fastSinceMs == null) fastSinceMs = nowMs
        } else {
            fastSinceMs = null
            if (slowSinceMs == null) slowSinceMs = nowMs
        }
        return evaluate(nowMs)
    }

    /**
     * Any touch: show controls; they hide again after [touchShowMs] while still fast. Returns true
     * when [hidden] changed.
     */
    fun onTouch(nowMs: Long): Boolean {
        touchedAtMs = nowMs
        if (!hidden) return false
        hidden = false
        return true
    }

    /** Recording stopped / activity paused: show controls and reset timers. Returns true when [hidden] changed. */
    fun reset(): Boolean {
        fastSinceMs = null
        slowSinceMs = null
        touchedAtMs = null
        val changed = hidden
        hidden = false
        return changed
    }

    /**
     * Millis until the state may change without new input (a hide once the enter timer and the
     * touch timeout have both elapsed, or a show once the exit timer has), or null when nothing is
     * pending. Never negative.
     */
    fun nextCheckDelayMs(nowMs: Long): Long? {
        val deadline = if (hidden) {
            val slowSince = slowSinceMs ?: return null
            slowSince + exitAfterMs
        } else {
            val fastSince = fastSinceMs ?: return null
            val touchDeadline = touchedAtMs?.let { it + touchShowMs } ?: Long.MIN_VALUE
            maxOf(fastSince + enterAfterMs, touchDeadline)
        }
        return (deadline - nowMs).coerceAtLeast(0L)
    }

    /** Applies the timers to the current run of samples; returns true when [hidden] flipped. */
    private fun evaluate(nowMs: Long): Boolean {
        if (hidden) {
            val slowSince = slowSinceMs ?: return false
            if (nowMs - slowSince < exitAfterMs) return false
            hidden = false
            touchedAtMs = null
            return true
        }
        val fastSince = fastSinceMs ?: return false
        if (nowMs - fastSince < enterAfterMs) return false
        val touched = touchedAtMs
        if (touched != null && nowMs - touched < touchShowMs) return false
        hidden = true
        touchedAtMs = null
        return true
    }
}
