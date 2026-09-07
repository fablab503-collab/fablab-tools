package com.fablab503.velotrack.location

import com.fablab503.velotrack.model.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdleSpeedEstimatorTest {

    private fun fix(timeMs: Long, lat: Double, lon: Double, speed: Float?, accuracy: Float = 5f) = GpsFix(
        timeMs = timeMs, elapsedNs = timeMs * 1_000_000, lat = lat, lon = lon, ele = null,
        verticalAccuracyM = null, speedMps = speed, bearingDeg = null, bearingAccuracyDeg = null, accuracyM = accuracy,
    )

    @Test
    fun reportedSpeedIsTrustedWhenAboveThreshold() {
        val e = IdleSpeedEstimator()
        assertEquals(4.2f, e.update(fix(0, 37.0, -122.0, 4.2f)))
    }

    @Test
    fun firstFixWithoutSpeedIsUnknown() {
        val e = IdleSpeedEstimator()
        assertNull(e.update(fix(0, 37.0, -122.0, null)))
    }

    @Test
    fun zeroReportedSpeedIsDerivedFromDisplacement() {
        val e = IdleSpeedEstimator()
        e.update(fix(0, 37.0, -122.0, 0f))
        // ~16.7 m north in 10 s = 1.67 m/s (6 km/h)
        val v = e.update(fix(10_000, 37.0 + 16.7 / 111_320.0, -122.0, 0f))!!
        assertEquals(1.67f, v, 0.05f)
    }

    @Test
    fun displacementWithinAccuracyNoiseStaysAtReportedZero() {
        val e = IdleSpeedEstimator()
        e.update(fix(0, 37.0, -122.0, 0f, accuracy = 20f))
        val v = e.update(fix(1_000, 37.0 + 3.0 / 111_320.0, -122.0, 0f, accuracy = 20f))
        assertEquals(0f, v!!, 0.0001f)
    }

    @Test
    fun longGapFallsBackToReported() {
        val e = IdleSpeedEstimator()
        e.update(fix(0, 37.0, -122.0, 0f))
        val v = e.update(fix(60_000, 37.01, -122.0, 0f))
        assertEquals(0f, v!!, 0.0001f)
    }
}
