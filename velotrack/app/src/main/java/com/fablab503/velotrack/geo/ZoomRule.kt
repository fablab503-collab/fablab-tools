package com.fablab503.velotrack.geo

import kotlin.math.abs

/** Speed-to-zoom rule for the follow camera. */
object ZoomRule {
    private val speedsKmh = doubleArrayOf(0.0, 10.0, 20.0, 30.0, 45.0, 70.0)
    private val zooms = doubleArrayOf(17.5, 17.5, 17.0, 16.5, 16.0, 15.5)

    /** Linear interpolation over (0,17.5) (10,17.5) (20,17.0) (30,16.5) (45,16.0) (70,15.5); clamps beyond. */
    fun targetZoom(speedKmh: Double): Double {
        val s = if (speedKmh.isNaN()) 0.0 else speedKmh
        if (s <= speedsKmh[0]) return zooms[0]
        val lastIndex = speedsKmh.size - 1
        if (s >= speedsKmh[lastIndex]) return zooms[lastIndex]
        for (i in 1..lastIndex) {
            if (s <= speedsKmh[i]) {
                val s0 = speedsKmh[i - 1]
                val s1 = speedsKmh[i]
                val z0 = zooms[i - 1]
                val z1 = zooms[i]
                val f = (s - s0) / (s1 - s0)
                return z0 + (z1 - z0) * f
            }
        }
        return zooms[lastIndex]
    }
}

/**
 * Applies [ZoomRule] with hysteresis, a change-rate limit and a block after user gestures.
 *
 * - The first call returns the target zoom directly.
 * - A change smaller than [hysteresis] is ignored (returns null).
 * - After [onUserGesture] auto-zoom is blocked for [gestureBlockMs].
 * - The zoom may move at most [maxRatePerSecond] * elapsed seconds since the last applied change.
 */
class ZoomController(
    private val hysteresis: Double = 0.25,
    private val maxRatePerSecond: Double = 0.1,
    private val gestureBlockMs: Long = 10_000,
) {
    private var lastGestureMs: Long? = null
    private var lastAppliedMs: Long? = null

    fun onUserGesture(nowMs: Long) {
        lastGestureMs = nowMs
        // Measure the rate limit from the gesture so the camera eases back rather than snapping.
        lastAppliedMs = nowMs
    }

    /** Returns the zoom to apply now, or null if the camera zoom should stay. */
    fun next(speedKmh: Double, currentZoom: Double, nowMs: Long): Double? {
        val gesture = lastGestureMs
        if (gesture != null && nowMs - gesture < gestureBlockMs) return null

        val target = ZoomRule.targetZoom(speedKmh)
        val delta = target - currentZoom
        if (abs(delta) < hysteresis) return null

        val last = lastAppliedMs
        if (last == null) {
            lastAppliedMs = nowMs
            return target
        }

        val elapsedSeconds = (nowMs - last).coerceAtLeast(0L) / 1000.0
        val maxChange = maxRatePerSecond * elapsedSeconds
        if (maxChange <= 0.0) return null

        val step = delta.coerceIn(-maxChange, maxChange)
        lastAppliedMs = nowMs
        return currentZoom + step
    }
}
