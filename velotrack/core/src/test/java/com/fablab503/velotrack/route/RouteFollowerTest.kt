package com.fablab503.velotrack.route

import com.fablab503.velotrack.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteFollowerTest {

    /** Metres per degree of latitude (and of longitude at the equator) for a 6371008.8 m sphere. */
    private val metersPerDeg = 6371008.8 * Math.PI / 180.0

    private fun offsetM(northM: Double, eastM: Double): LatLon =
        LatLon(northM / metersPerDeg, eastM / metersPerDeg)

    /** A straight 1 km route running north from the equator, one vertex every 100 m. */
    private fun straightRoute(): List<LatLon> = (0..10).map { offsetM(it * 100.0, 0.0) }

    @Test
    fun totalDistanceOfStraightRouteIsOneKilometre() {
        val follower = RouteFollower(straightRoute())
        assertEquals(11, follower.pointCount)
        assertEquals(1000.0, follower.totalDistanceM, 1.0)
    }

    @Test
    fun pointAt30PercentHasAbout700MetresRemaining() {
        val follower = RouteFollower(straightRoute())
        val progress = follower.update(offsetM(300.0, 0.0), 0L)
        assertEquals(700.0, progress.distanceRemainingM, 5.0)
        assertEquals(0.0, progress.distanceToRouteM, 1.0)
        assertFalse(progress.offRoute)
    }

    @Test
    fun remainingDistanceDecreasesAsRiderAdvances() {
        val follower = RouteFollower(straightRoute())
        val first = follower.update(offsetM(250.0, 0.0), 0L)
        val second = follower.update(offsetM(450.0, 0.0), 1_000L)
        val third = follower.update(offsetM(950.0, 0.0), 2_000L)
        assertTrue(second.distanceRemainingM < first.distanceRemainingM)
        assertTrue(third.distanceRemainingM < second.distanceRemainingM)
        assertEquals(50.0, third.distanceRemainingM, 5.0)
    }

    @Test
    fun offRouteOnlyAfterDelayAndClearsImmediately() {
        val follower = RouteFollower(straightRoute(), offRouteThresholdM = 50.0, offRouteDelayMs = 10_000)
        val east80 = offsetM(500.0, 80.0)

        val atStart = follower.update(east80, 0L)
        assertEquals(80.0, atStart.distanceToRouteM, 2.0)
        assertFalse("not yet off-route at t=0", atStart.offRoute)

        val justBefore = follower.update(east80, 9_999L)
        assertFalse("still within the delay", justBefore.offRoute)

        val afterDelay = follower.update(east80, 10_000L)
        assertTrue("off-route after 10 s beyond the threshold", afterDelay.offRoute)

        val stillOff = follower.update(east80, 20_000L)
        assertTrue(stillOff.offRoute)

        val back = follower.update(offsetM(600.0, 0.0), 20_001L)
        assertFalse("back on route clears immediately", back.offRoute)
        assertEquals(0.0, back.distanceToRouteM, 1.0)

        // Leaving again restarts the timer from scratch.
        val leftAgain = follower.update(east80, 25_000L)
        assertFalse(leftAgain.offRoute)
        val leftAgainLater = follower.update(east80, 34_999L)
        assertFalse(leftAgainLater.offRoute)
        val leftAgainExpired = follower.update(east80, 35_000L)
        assertTrue(leftAgainExpired.offRoute)
    }

    @Test
    fun briefExcursionWithinDelayNeverTripsOffRoute() {
        val follower = RouteFollower(straightRoute())
        assertFalse(follower.update(offsetM(500.0, 80.0), 0L).offRoute)
        assertFalse(follower.update(offsetM(500.0, 80.0), 5_000L).offRoute)
        assertFalse(follower.update(offsetM(500.0, 0.0), 6_000L).offRoute)
        // Timer must have been reset: another 9 s off-route is not enough.
        assertFalse(follower.update(offsetM(500.0, 80.0), 7_000L).offRoute)
        assertFalse(follower.update(offsetM(500.0, 80.0), 16_000L).offRoute)
    }

    /** Zig-zag north: 100 m per vertex, alternating 0 m / 20 m east. */
    private fun zigZagRoute(vertices: Int): List<LatLon> =
        (0 until vertices).map { offsetM(it * 100.0, if (it % 2 == 1) 20.0 else 0.0) }

    @Test
    fun skippingAheadBeyondWindowTriggersRescanAndFindsRightSegment() {
        val follower = RouteFollower(zigZagRoute(201), windowSegments = 5)

        // Lock the follower onto the first segment.
        val start = follower.update(offsetM(50.0, 10.0), 0L)
        assertEquals(0, start.nearestSegment)

        // Jump to the midpoint of segment 150 (from vertex 150 at (15000, 0) to vertex 151 at (15100, 20)).
        val jumped = follower.update(offsetM(15_050.0, 10.0), 1_000L)
        assertEquals(150, jumped.nearestSegment)
        assertEquals(0.0, jumped.distanceToRouteM, 1.0)
        assertFalse(jumped.offRoute)

        val segmentLen = Math.hypot(100.0, 20.0)
        val expectedRemaining = follower.totalDistanceM - (150 * segmentLen + segmentLen / 2)
        assertEquals(expectedRemaining, jumped.distanceRemainingM, 5.0)
    }

    @Test
    fun windowSearchTracksNeighbouringSegments() {
        val follower = RouteFollower(zigZagRoute(201), windowSegments = 5)
        follower.update(offsetM(50.0, 10.0), 0L)
        // Advance one segment at a time; each stays within the window.
        for (seg in 1..8) {
            val p = follower.update(offsetM(seg * 100.0 + 50.0, 10.0), seg * 1_000L)
            assertEquals(seg, p.nearestSegment)
        }
    }

    @Test
    fun resetForgetsLastMatch() {
        val follower = RouteFollower(zigZagRoute(201), windowSegments = 5)
        follower.update(offsetM(15_050.0, 10.0), 0L)
        follower.reset()
        val p = follower.update(offsetM(50.0, 10.0), 1_000L)
        assertEquals(0, p.nearestSegment)
        assertFalse(p.offRoute)
    }

    @Test
    fun singlePointRouteReportsDistanceToPoint() {
        val follower = RouteFollower(listOf(offsetM(0.0, 0.0)))
        assertEquals(0.0, follower.totalDistanceM, 0.0)
        assertEquals(1, follower.pointCount)
        val p = follower.update(offsetM(0.0, 30.0), 0L)
        assertEquals(30.0, p.distanceToRouteM, 1.0)
        assertEquals(0.0, p.distanceRemainingM, 0.0)
        assertEquals(0, p.nearestSegment)
        assertFalse(p.offRoute)
    }

    @Test
    fun emptyRouteIsHarmless() {
        val follower = RouteFollower(emptyList())
        assertEquals(0.0, follower.totalDistanceM, 0.0)
        assertEquals(0, follower.pointCount)
        val p = follower.update(offsetM(10.0, 10.0), 0L)
        assertEquals(0.0, p.distanceRemainingM, 0.0)
        assertFalse(p.offRoute)
    }
}
