package com.fablab503.velotrack.recording

import com.fablab503.velotrack.model.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointFilterTest {

    /** Metres per degree of latitude on the 6371008.8 m sphere used by Geo.haversineM. */
    private val metersPerDeg = 6371008.8 * Math.PI / 180.0

    /** A fix [northM] metres north of the equator at [tSec] seconds. */
    private fun fix(
        tSec: Double,
        northM: Double,
        accuracy: Float? = 10f,
        speed: Float? = 5f,
    ): GpsFix = GpsFix(
        timeMs = (tSec * 1000).toLong(),
        elapsedNs = (tSec * 1e9).toLong(),
        lat = northM / metersPerDeg,
        lon = 0.0,
        ele = null,
        verticalAccuracyM = null,
        speedMps = speed,
        bearingDeg = null,
        bearingAccuracyDeg = null,
        accuracyM = accuracy,
    )

    @Test
    fun rejectsPoorAccuracyAndAcceptsGoodAccuracy() {
        val filter = PointFilter(FilterConfig())
        val bad = filter.offer(fix(0.0, 0.0, accuracy = 80f))
        assertFalse(bad.accepted)
        assertFalse(bad.store)
        assertNull(bad.point)

        val good = filter.offer(fix(1.0, 0.0, accuracy = 10f))
        assertTrue(good.accepted)
    }

    @Test
    fun rejectsFixWithoutAccuracy() {
        val filter = PointFilter(FilterConfig())
        assertFalse(filter.offer(fix(0.0, 0.0, accuracy = null)).accepted)
    }

    @Test
    fun firstAcceptedFixIsStored() {
        val filter = PointFilter(FilterConfig())
        val d = filter.offer(fix(0.0, 0.0))
        assertTrue(d.accepted)
        assertTrue(d.store)
        assertNotNull(d.point)
        assertFalse(d.newSegment)
        assertEquals(0, d.point?.segment)
    }

    @Test
    fun distanceThinningStoresOnlyAfterFiveMetres() {
        val filter = PointFilter(FilterConfig())
        assertTrue(filter.offer(fix(0.0, 0.0, speed = 3f)).store)

        val near = filter.offer(fix(1.0, 2.0, speed = 3f))
        assertTrue(near.accepted)
        assertFalse(near.store)
        assertNull(near.point)

        val far = filter.offer(fix(2.0, 6.0, speed = 3f))
        assertTrue(far.accepted)
        assertTrue(far.store)
        assertNotNull(far.point)
    }

    @Test
    fun stationaryDriftIsNotStored() {
        val filter = PointFilter(FilterConfig())
        assertTrue(filter.offer(fix(0.0, 0.0, accuracy = 10f, speed = 0f)).store)
        // Moved 8 m (>= 5 m) but less than 2 x 10 m accuracy while slower than 1 m/s: drift.
        val drift = filter.offer(fix(1.0, 8.0, accuracy = 10f, speed = 0.2f))
        assertTrue(drift.accepted)
        assertFalse(drift.store)
    }

    @Test
    fun rejectsImplausibleJump() {
        val filter = PointFilter(FilterConfig())
        assertTrue(filter.offer(fix(0.0, 0.0)).accepted)
        // 500 m in 1 s is far beyond 30 m/s * 1 s + 10 m + 10 m.
        val jump = filter.offer(fix(1.0, 500.0))
        assertFalse(jump.accepted)
        assertFalse(jump.store)
    }

    @Test
    fun autoPausesAfterTenSlowSecondsAndResumesWhenMoving() {
        val filter = PointFilter(FilterConfig())
        var last = filter.offer(fix(0.0, 0.0, speed = 5f))
        assertFalse(last.autoPaused)
        // 0.5 m/s for 11 seconds, standing still.
        for (s in 1..11) {
            last = filter.offer(fix(s.toDouble(), 0.0, speed = 0.5f))
        }
        assertTrue(last.autoPaused)
        assertFalse(last.store)

        val moving = filter.offer(fix(12.0, 30.0, speed = 2f))
        assertTrue(moving.accepted)
        assertFalse(moving.autoPaused)
    }

    @Test
    fun autoPauseCanBeDisabled() {
        val filter = PointFilter(FilterConfig(autoPauseEnabled = false))
        var last = filter.offer(fix(0.0, 0.0, speed = 0.5f))
        for (s in 1..20) {
            last = filter.offer(fix(s.toDouble(), 0.0, speed = 0.5f))
        }
        assertFalse(last.autoPaused)
    }

    @Test
    fun largeGapStartsNewSegment() {
        val filter = PointFilter(FilterConfig())
        assertEquals(0, filter.segment)
        val first = filter.offer(fix(0.0, 0.0))
        assertTrue(first.store)
        assertEquals(0, first.point?.segment)

        // 250 m in 20 s: plausible (30 m/s * 20 s), but beyond the 200 m segment gap.
        val gap = filter.offer(fix(20.0, 250.0))
        assertTrue(gap.accepted)
        assertTrue(gap.store)
        assertTrue(gap.newSegment)
        assertEquals(1, filter.segment)
        assertEquals(1, gap.point?.segment)
    }

    @Test
    fun longTimeGapStartsNewSegment() {
        val filter = PointFilter(FilterConfig())
        assertTrue(filter.offer(fix(0.0, 0.0)).store)
        // 50 m after 7 minutes.
        val gap = filter.offer(fix(7.0 * 60.0, 50.0))
        assertTrue(gap.store)
        assertTrue(gap.newSegment)
        assertEquals(1, filter.segment)
    }

    @Test
    fun startSegmentConstructorIsHonoured() {
        val filter = PointFilter(FilterConfig(), 3)
        assertEquals(3, filter.segment)
        val d = filter.offer(fix(0.0, 0.0))
        assertEquals(3, d.point?.segment)
    }

    @Test
    fun forcePauseStoresNothing() {
        val filter = PointFilter(FilterConfig())
        assertTrue(filter.offer(fix(0.0, 0.0)).store)
        filter.forcePause(true)
        val paused = filter.offer(fix(1.0, 20.0))
        assertTrue(paused.accepted)
        assertFalse(paused.store)
        filter.forcePause(false)
        assertTrue(filter.offer(fix(2.0, 40.0)).store)
    }

    @Test
    fun zeroReportedSpeedWhileMovingUsesDerivedSpeedAndDoesNotAutoPause() {
        // Emulators and some receivers report speed 0 while the position clearly moves (5.5 m/s here).
        val filter = PointFilter(FilterConfig())
        filter.offer(fix(0.0, 0.0, accuracy = 5f, speed = 0f))
        var last = filter.offer(fix(1.0, 5.5, accuracy = 5f, speed = 0f))
        assertEquals(5.5f, last.speedMps, 0.2f)
        for (i in 2..14) last = filter.offer(fix(i.toDouble(), i * 5.5, accuracy = 5f, speed = 0f))
        assertFalse(last.autoPaused)
        assertTrue(last.store)
    }

    @Test
    fun zeroReportedSpeedWhileStationaryStillAutoPauses() {
        // Position jitter below the accuracy radius must not be mistaken for movement.
        val filter = PointFilter(FilterConfig())
        var last = filter.offer(fix(0.0, 0.0, accuracy = 8f, speed = 0f))
        for (i in 1..12) last = filter.offer(fix(i.toDouble(), if (i % 2 == 0) 0.0 else 3.0, accuracy = 8f, speed = 0f))
        assertTrue(last.autoPaused)
    }

    @Test
    fun derivesSpeedWhenReceiverReportsNone() {
        val filter = PointFilter(FilterConfig())
        filter.offer(fix(0.0, 0.0, speed = null))
        val d = filter.offer(fix(1.0, 10.0, speed = null))
        assertTrue(d.accepted)
        assertEquals(10f, d.speedMps, 0.1f)
    }
}
