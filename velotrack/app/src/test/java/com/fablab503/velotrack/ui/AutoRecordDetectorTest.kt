package com.fablab503.velotrack.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecordDetectorTest {

    private val fast = 6f / 3.6f
    private val slow = 3f / 3.6f
    private val cutoff = 50f

    private fun AutoRecordDetector.fix(speed: Float?, tMs: Long, accuracy: Float = 10f): Boolean =
        onFix(speed, accuracy, cutoff, tMs)

    @Test
    fun threeFastFixesOverTenSecondsFireOnce() {
        val d = AutoRecordDetector()
        assertFalse(d.fix(fast, 0))
        assertFalse(d.fix(fast, 5_000))
        assertFalse(d.fix(fast, 9_999))
        assertTrue(d.fix(fast, 10_000))
        // Exactly once until reset.
        assertFalse(d.fix(fast, 11_000))
        assertFalse(d.fix(fast, 30_000))
    }

    @Test
    fun needsAtLeastThreeFixesEvenOverTenSeconds() {
        val d = AutoRecordDetector()
        assertFalse(d.fix(fast, 0))
        assertFalse(d.fix(fast, 12_000))
        assertTrue(d.fix(fast, 13_000))
    }

    @Test
    fun slowFixResetsTheRun() {
        val d = AutoRecordDetector()
        assertFalse(d.fix(fast, 0))
        assertFalse(d.fix(fast, 5_000))
        assertFalse(d.fix(slow, 6_000))
        // Only 2 fixes / 5 s since the restart.
        assertFalse(d.fix(fast, 7_000))
        assertFalse(d.fix(fast, 12_000))
        assertTrue(d.fix(fast, 17_000))
    }

    @Test
    fun unknownSpeedAndPoorAccuracyCountAsSlow() {
        val d = AutoRecordDetector()
        assertFalse(d.fix(fast, 0))
        assertFalse(d.fix(fast, 5_000))
        assertFalse(d.fix(null, 6_000))
        assertFalse(d.fix(fast, 7_000))
        assertFalse(d.fix(fast, 12_000, accuracy = 80f))
        assertFalse(d.fix(fast, 13_000))
        assertFalse(d.fix(fast, 18_000))
        assertTrue(d.fix(fast, 23_000))
    }

    @Test
    fun resetAllowsANewDetection() {
        val d = AutoRecordDetector()
        d.fix(fast, 0)
        d.fix(fast, 5_000)
        assertTrue(d.fix(fast, 10_000))
        d.reset()
        assertFalse(d.fix(fast, 11_000))
        assertFalse(d.fix(fast, 16_000))
        assertTrue(d.fix(fast, 21_000))
    }

    @Test
    fun cooldownBlocksDetection() {
        val d = AutoRecordDetector()
        d.cooldown(0)
        // Two minutes of riding straight after a stop: nothing.
        var t = 1_000L
        while (t < 120_000L) {
            assertFalse(d.fix(fast, t))
            t += 5_000
        }
        // Once the cooldown is over the run starts from scratch.
        assertFalse(d.fix(fast, 121_000))
        assertFalse(d.fix(fast, 126_000))
        assertTrue(d.fix(fast, 131_000))
    }

    @Test
    fun cooldownAfterFiringPreventsAnImmediateSecondStart() {
        val d = AutoRecordDetector()
        d.fix(fast, 0)
        d.fix(fast, 5_000)
        assertTrue(d.fix(fast, 10_000))
        d.cooldown(10_000)
        assertFalse(d.fix(fast, 11_000))
        assertFalse(d.fix(fast, 60_000))
        assertFalse(d.fix(fast, 129_000))
    }
}
