package com.fablab503.velotrack.location

import com.fablab503.velotrack.model.GpsFix

/**
 * Course-up heading from GPS bearing only. The heading is updated when the rider is moving
 * faster than [minSpeedMps], the fix carries a bearing, and the bearing accuracy (when reported)
 * is below [maxBearingAccuracyDeg]. Otherwise the last good heading is held so the map does not
 * spin at a stop.
 */
class HeadingEstimator(
    private val minSpeedMps: Float = 1.5f,
    private val maxBearingAccuracyDeg: Float = 45f,
) {
    /** Last good heading in degrees [0, 360), null until the first one. */
    var headingDeg: Float? = null
        private set

    /** Feeds a fix; returns the current heading (possibly unchanged, possibly still null). */
    fun update(fix: GpsFix): Float? {
        val speed = fix.speedMps ?: return headingDeg
        val bearing = fix.bearingDeg ?: return headingDeg
        if (speed.isNaN() || bearing.isNaN()) return headingDeg
        if (speed <= minSpeedMps) return headingDeg
        val accuracy = fix.bearingAccuracyDeg
        if (accuracy != null && !accuracy.isNaN() && accuracy >= maxBearingAccuracyDeg) return headingDeg
        headingDeg = normalize(bearing)
        return headingDeg
    }

    fun reset() {
        headingDeg = null
    }

    private fun normalize(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        if (d >= 360f) d -= 360f
        return d
    }
}
