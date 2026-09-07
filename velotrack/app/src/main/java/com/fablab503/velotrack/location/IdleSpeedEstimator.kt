package com.fablab503.velotrack.location

import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.model.GpsFix

/**
 * Effective speed for the idle (not recording) GPS feed, mirroring the rule the recording
 * PointFilter applies: trust the receiver's speed above [minTrustedReportedMps], otherwise derive it
 * from the displacement since the previous fix when that displacement exceeds the position noise.
 * Receivers (and the emulator) report 0 m/s at low speed or not at all; riding mode and auto-record
 * would never trigger from those values.
 */
class IdleSpeedEstimator(
    private val minTrustedReportedMps: Float = 0.3f,
    private val maxGapS: Double = 10.0,
) {
    private var prev: GpsFix? = null

    /** Returns the speed to use for [fix] (m/s), or null when nothing usable is known yet. */
    fun update(fix: GpsFix): Float? {
        val previous = prev
        prev = fix
        val reported = fix.speedMps?.takeIf { !it.isNaN() && it >= 0f }
        if (reported != null && reported > minTrustedReportedMps) return reported
        if (previous == null) return reported
        val dtS = (fix.timeMs - previous.timeMs) / 1000.0
        if (dtS <= 0.0 || dtS > maxGapS) return reported
        val distM = Geo.distanceM(previous.latLon, fix.latLon)
        val noiseM = maxOf(fix.accuracyM ?: 0f, MIN_NOISE_M).toDouble()
        return if (distM > noiseM) (distM / dtS).toFloat() else reported ?: 0f
    }

    fun reset() {
        prev = null
    }

    private companion object {
        const val MIN_NOISE_M = 2f
    }
}
