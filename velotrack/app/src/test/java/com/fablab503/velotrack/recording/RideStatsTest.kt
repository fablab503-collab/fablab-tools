package com.fablab503.velotrack.recording

import com.fablab503.velotrack.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStatsTest {

    /** Metres per degree of latitude on the 6371008.8 m sphere used by Geo.haversineM. */
    private val metersPerDeg = 6371008.8 * Math.PI / 180.0

    private fun point(
        tSec: Long,
        northM: Double,
        speed: Float? = 5f,
        segment: Int = 0,
        ele: Double? = null,
    ): TrackPoint = TrackPoint(
        timeMs = tSec * 1000L,
        lat = northM / metersPerDeg,
        lon = 0.0,
        ele = ele,
        speedMps = speed,
        accuracyM = 10f,
        segment = segment,
    )

    @Test
    fun twoPointsOneKilometreApartGiveOneKilometre() {
        val stats = RideStats()
        stats.start(0L)
        stats.addStored(point(0, 0.0), null)
        stats.addStored(point(100, 1000.0), null)
        val snap = stats.snapshot(100_000L)
        assertEquals(1000.0, snap.distanceM, 2.0)
        assertEquals(2, snap.pointCount)
    }

    @Test
    fun segmentChangeSkipsDistance() {
        val stats = RideStats()
        stats.start(0L)
        stats.addStored(point(0, 0.0, segment = 0), null)
        stats.addStored(point(10, 100.0, segment = 0), null)
        // Gap: the jump to the new segment must not count.
        stats.addStored(point(600, 1100.0, segment = 1), null)
        stats.addStored(point(610, 1200.0, segment = 1), null)
        val snap = stats.snapshot(610_000L)
        assertEquals(200.0, snap.distanceM, 1.0)
        assertEquals(4, snap.pointCount)
    }

    @Test
    fun maxSpeedIsTracked() {
        val stats = RideStats()
        stats.start(0L)
        stats.addStored(point(0, 0.0, speed = 5f), null)
        stats.addStored(point(1, 8.0, speed = 8f), null)
        stats.addStored(point(2, 14.0, speed = 6f), null)
        stats.addStored(point(3, 20.0, speed = null), null)
        assertEquals(8.0, stats.snapshot(3000L).maxSpeedMps, 1e-6)
    }

    @Test
    fun averageSpeedIsDistanceOverMovingTime() {
        val stats = RideStats()
        stats.start(0L)
        stats.addStored(point(0, 0.0), null)
        stats.addStored(point(100, 1000.0), null)
        stats.addMovingTime(100_000L)
        val snap = stats.snapshot(150_000L)
        assertEquals(100_000L, snap.movingMs)
        assertEquals(150_000L, snap.elapsedMs)
        assertEquals(snap.distanceM / 100.0, snap.avgMovingSpeedMps, 1e-6)
        assertEquals(10.0, snap.avgMovingSpeedMps, 0.05)
    }

    @Test
    fun averageIsZeroWithoutMovingTime() {
        val stats = RideStats()
        stats.start(0L)
        stats.addStored(point(0, 0.0), null)
        stats.addStored(point(1, 5.0), null)
        assertEquals(0.0, stats.snapshot(1000L).avgMovingSpeedMps, 0.0)
    }

    @Test
    fun negativeMovingTimeIsIgnored() {
        val stats = RideStats()
        stats.start(0L)
        stats.addMovingTime(5_000L)
        stats.addMovingTime(-3_000L)
        assertEquals(5_000L, stats.snapshot(10_000L).movingMs)
    }

    @Test
    fun elevationGainAccumulatesSustainedClimb() {
        val stats = RideStats()
        stats.start(0L)
        // Climb 100 m over 50 points; smoothing lags, so the total is close to but under 100 m.
        for (i in 0 until 50) {
            stats.addStored(point(i.toLong(), i * 10.0, ele = 100.0 + i * 2.0), 5f)
        }
        val snap = stats.snapshot(50_000L)
        assertTrue(snap.elevationGainM > 70.0)
        assertEquals(0.0, snap.elevationLossM, 1e-9)
    }

    @Test
    fun rebuildReplaysPointsAndMovingTime() {
        val points = listOf(
            point(0, 0.0, speed = 4f),
            point(50, 500.0, speed = 9f),
            point(100, 1000.0, speed = 7f),
        )
        val stats = RideStats.rebuild(points.asSequence(), startedAtMs = 0L, movingMs = 100_000L)
        val snap = stats.snapshot(120_000L)
        assertEquals(1000.0, snap.distanceM, 2.0)
        assertEquals(100_000L, snap.movingMs)
        assertEquals(120_000L, snap.elapsedMs)
        assertEquals(9.0, snap.maxSpeedMps, 1e-6)
        assertEquals(3, snap.pointCount)
        assertEquals(10.0, snap.avgMovingSpeedMps, 0.05)
    }

    @Test
    fun startResetsEverything() {
        val stats = RideStats()
        stats.start(0L)
        stats.addStored(point(0, 0.0), null)
        stats.addStored(point(10, 100.0), null)
        stats.addMovingTime(10_000L)
        stats.start(50_000L)
        val snap = stats.snapshot(60_000L)
        assertEquals(0.0, snap.distanceM, 0.0)
        assertEquals(0L, snap.movingMs)
        assertEquals(10_000L, snap.elapsedMs)
        assertEquals(0, snap.pointCount)
    }
}
