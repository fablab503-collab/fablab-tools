package com.fablab503.velotrack.ui

/**
 * Detects that the rider has set off while no recording is running, so the app can start one by
 * itself. Pure Kotlin (no Android imports) so the rules are unit tested on the JVM.
 *
 * Riding is detected after at least [minFixes] consecutive fast fixes (speed at or above
 * [minSpeedMps], accuracy within the caller's cutoff) spanning at least [minDurationMs]. A slow or
 * inaccurate fix restarts the count. After [cooldown] nothing is detected for [cooldownMs].
 */
class AutoRecordDetector(
    private val minSpeedMps: Float = 5f / 3.6f,
    private val minDurationMs: Long = 10_000,
    private val minFixes: Int = 3,
    private val cooldownMs: Long = 120_000,
) {
    /** Time of the first fix in the current run of fast fixes, or null. */
    private var fastSinceMs: Long? = null

    /** Number of fixes in the current run of fast fixes. */
    private var fastFixes = 0

    /** Detection is suppressed until this monotonic time, or null when not cooling down. */
    private var cooldownUntilMs: Long? = null

    /** Set once detection fired; cleared by [reset] / [cooldown]. */
    private var fired = false

    /**
     * Returns true exactly once when riding is detected; call [reset] after acting. speed null or
     * accuracy > [accuracyCutoffM] count as slow.
     */
    fun onFix(speedMps: Float?, accuracyM: Float, accuracyCutoffM: Float, nowMs: Long): Boolean {
        val until = cooldownUntilMs
        if (until != null) {
            if (nowMs < until) {
                clearRun()
                return false
            }
            cooldownUntilMs = null
        }
        val fast = speedMps != null && speedMps >= minSpeedMps && accuracyM <= accuracyCutoffM
        if (!fast) {
            clearRun()
            return false
        }
        val since = fastSinceMs ?: nowMs.also { fastSinceMs = it }
        fastFixes++
        if (fired) return false
        if (fastFixes >= minFixes && nowMs - since >= minDurationMs) {
            fired = true
            return true
        }
        return false
    }

    /** Forgets the current run of fast fixes so detection can fire again later. */
    fun reset() {
        clearRun()
        fired = false
    }

    /** Suppress detection until nowMs + cooldownMs (call when a recording stops). */
    fun cooldown(nowMs: Long) {
        reset()
        cooldownUntilMs = nowMs + cooldownMs
    }

    private fun clearRun() {
        fastSinceMs = null
        fastFixes = 0
    }
}
