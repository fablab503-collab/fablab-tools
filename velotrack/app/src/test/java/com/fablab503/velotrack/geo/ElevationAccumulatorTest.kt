package com.fablab503.velotrack.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationAccumulatorTest {
    @Test
    fun startsAtZero() {
        val acc = ElevationAccumulator()
        assertEquals(0.0, acc.gainM, 0.0)
        assertEquals(0.0, acc.lossM, 0.0)
    }

    @Test
    fun plusMinusThreeMetreNoiseAccumulatesNothing() {
        val acc = ElevationAccumulator()
        for (i in 0 until 500) {
            val noise = if (i % 2 == 0) -3.0 else 3.0
            acc.add(100.0 + noise, 5f)
        }
        assertEquals(0.0, acc.gainM, 1e-9)
        assertEquals(0.0, acc.lossM, 1e-9)
    }

    @Test
    fun pseudoRandomNoiseAccumulatesNothing() {
        val acc = ElevationAccumulator()
        acc.add(100.0, null) // anchor at the true level, then noise around it
        var seed = 12345L
        for (i in 0 until 1000) {
            // Simple LCG in [-3, 3].
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val u = ((seed ushr 11).toDouble() / (1L shl 53).toDouble())
            acc.add(100.0 + (u * 6.0 - 3.0), null)
        }
        assertEquals(0.0, acc.gainM, 1e-9)
        assertEquals(0.0, acc.lossM, 1e-9)
    }

    @Test
    fun monotonicClimbOf20MetresAccumulatesAbout20() {
        val acc = ElevationAccumulator()
        for (i in 0..20) acc.add(i.toDouble(), 3f)
        // With alpha smoothing the last anchor lags the true altitude.
        assertTrue("gain was ${acc.gainM}", acc.gainM >= 14.0 && acc.gainM <= 20.0)
        assertEquals(0.0, acc.lossM, 1e-9)
    }

    @Test
    fun monotonicDescentAccumulatesLoss() {
        val acc = ElevationAccumulator()
        for (i in 0..20) acc.add(100.0 - i, 3f)
        assertTrue("loss was ${acc.lossM}", acc.lossM >= 14.0 && acc.lossM <= 20.0)
        assertEquals(0.0, acc.gainM, 1e-9)
    }

    @Test
    fun climbThenSettleAccumulatesFullAmount() {
        val acc = ElevationAccumulator()
        for (i in 0..20) acc.add(i.toDouble(), 3f)
        // Hold at 20 m: the smoothed value converges and the remaining delta is booked once it
        // exceeds the threshold, or stays below it. Total gain never exceeds the true climb.
        for (i in 0 until 100) acc.add(20.0, 3f)
        assertTrue(acc.gainM >= 14.0 && acc.gainM <= 20.0 + 1e-9)
    }

    @Test
    fun ignoresSamplesWithPoorVerticalAccuracy() {
        val acc = ElevationAccumulator(maxVerticalAccuracyM = 20f)
        acc.add(0.0, 5f)
        for (i in 1..50) acc.add(i * 10.0, 25f) // all rejected
        assertEquals(0.0, acc.gainM, 1e-9)
        // Accuracy exactly at the limit is accepted.
        for (i in 1..50) acc.add(100.0, 20f)
        assertTrue(acc.gainM > 0.0)
    }

    @Test
    fun nullAccuracyIsAccepted() {
        val acc = ElevationAccumulator()
        acc.add(0.0, null)
        for (i in 1..50) acc.add(100.0, null)
        assertTrue(acc.gainM > 50.0)
    }

    @Test
    fun ignoresNaNAndInfinite() {
        val acc = ElevationAccumulator()
        acc.add(0.0, null)
        acc.add(Double.NaN, null)
        acc.add(Double.POSITIVE_INFINITY, null)
        acc.add(Double.NEGATIVE_INFINITY, null)
        assertEquals(0.0, acc.gainM, 0.0)
        assertEquals(0.0, acc.lossM, 0.0)
    }

    @Test
    fun resetClearsState() {
        val acc = ElevationAccumulator()
        acc.add(0.0, null)
        for (i in 1..50) acc.add(100.0, null)
        assertTrue(acc.gainM > 0.0)
        acc.reset()
        assertEquals(0.0, acc.gainM, 0.0)
        assertEquals(0.0, acc.lossM, 0.0)
        // After reset the first sample is a fresh anchor: a step to a very different level
        // does not create gain on its own.
        acc.add(500.0, null)
        assertEquals(0.0, acc.gainM, 0.0)
    }

    @Test
    fun customThresholdIsRespected() {
        val strict = ElevationAccumulator(thresholdM = 1.0, alpha = 1.0)
        strict.add(0.0, null)
        strict.add(1.5, null)
        assertEquals(1.5, strict.gainM, 1e-9)

        val loose = ElevationAccumulator(thresholdM = 10.0, alpha = 1.0)
        loose.add(0.0, null)
        loose.add(9.0, null)
        assertEquals(0.0, loose.gainM, 1e-9)
        loose.add(10.0, null)
        assertEquals(10.0, loose.gainM, 1e-9)
    }
}
