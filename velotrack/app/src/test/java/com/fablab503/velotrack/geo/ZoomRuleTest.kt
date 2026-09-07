package com.fablab503.velotrack.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomRuleTest {
    @Test
    fun targetZoomAtTablePoints() {
        assertEquals(17.5, ZoomRule.targetZoom(0.0), 1e-9)
        assertEquals(17.5, ZoomRule.targetZoom(10.0), 1e-9)
        assertEquals(17.0, ZoomRule.targetZoom(20.0), 1e-9)
        assertEquals(16.5, ZoomRule.targetZoom(30.0), 1e-9)
        assertEquals(16.0, ZoomRule.targetZoom(45.0), 1e-9)
        assertEquals(15.5, ZoomRule.targetZoom(70.0), 1e-9)
    }

    @Test
    fun targetZoomInterpolatesBetweenPoints() {
        assertEquals(16.75, ZoomRule.targetZoom(25.0), 1e-9)
        assertEquals(17.25, ZoomRule.targetZoom(15.0), 1e-9)
        assertEquals(17.5, ZoomRule.targetZoom(5.0), 1e-9)
    }

    @Test
    fun targetZoomClampsBeyondTable() {
        assertEquals(15.5, ZoomRule.targetZoom(100.0), 1e-9)
        assertEquals(15.5, ZoomRule.targetZoom(1000.0), 1e-9)
        assertEquals(17.5, ZoomRule.targetZoom(-5.0), 1e-9)
    }

    @Test
    fun targetZoomIsMonotonicNonIncreasing() {
        var prev = ZoomRule.targetZoom(0.0)
        var s = 0.0
        while (s <= 120.0) {
            val z = ZoomRule.targetZoom(s)
            assertTrue("zoom rose at $s km/h", z <= prev + 1e-12)
            prev = z
            s += 0.5
        }
    }

    @Test
    fun targetZoomHandlesNaN() {
        assertEquals(17.5, ZoomRule.targetZoom(Double.NaN), 1e-9)
    }

    @Test
    fun controllerFirstCallReturnsTarget() {
        val c = ZoomController()
        val z = c.next(0.0, 15.0, 1_000L)
        assertNotNull(z)
        assertEquals(17.5, z ?: Double.NaN, 1e-9)
    }

    @Test
    fun controllerSecondCallWithinHysteresisReturnsNull() {
        val c = ZoomController()
        val first = c.next(0.0, 15.0, 1_000L)
        assertEquals(17.5, first ?: Double.NaN, 1e-9)
        // Camera now sits at 17.5; slightly different speed changes the target by < 0.25.
        assertNull(c.next(12.0, 17.5, 1_000L))
        assertNull(c.next(0.0, 17.4, 5_000L))
    }

    @Test
    fun controllerGestureBlocksAutoZoomForTenSeconds() {
        val c = ZoomController()
        assertNotNull(c.next(0.0, 15.0, 0L))
        c.onUserGesture(10_000L)
        assertNull(c.next(100.0, 17.5, 10_001L))
        assertNull(c.next(100.0, 17.5, 15_000L))
        assertNull(c.next(100.0, 17.5, 19_999L))
        // Exactly the block duration later auto-zoom resumes.
        assertNotNull(c.next(100.0, 17.5, 20_000L))
    }

    @Test
    fun controllerRateLimitsChange() {
        val c = ZoomController(hysteresis = 0.25, maxRatePerSecond = 0.1, gestureBlockMs = 10_000)
        assertEquals(17.5, c.next(0.0, 15.0, 0L) ?: Double.NaN, 1e-9)
        // 5 s later the target is 15.5 (delta -2.0), but only 0.5 zoom may change.
        val z = c.next(100.0, 17.5, 5_000L)
        assertEquals(17.0, z ?: Double.NaN, 1e-9)
        // Another 2 s: at most 0.2 more.
        val z2 = c.next(100.0, 17.0, 7_000L)
        assertEquals(16.8, z2 ?: Double.NaN, 1e-9)
    }

    @Test
    fun controllerRateLimitDoesNotOvershootTarget() {
        val c = ZoomController()
        assertEquals(17.5, c.next(0.0, 15.0, 0L) ?: Double.NaN, 1e-9)
        // Target 17.0 (delta -0.5); 60 s of budget would allow 6.0, but the step is capped at the delta.
        val z = c.next(20.0, 17.5, 60_000L)
        assertEquals(17.0, z ?: Double.NaN, 1e-9)
    }

    @Test
    fun controllerZeroElapsedAfterAppliedChangeReturnsNull() {
        val c = ZoomController()
        assertNotNull(c.next(0.0, 15.0, 0L))
        // Same timestamp, far from target: no time budget, so no change.
        assertNull(c.next(100.0, 17.5, 0L))
    }

    @Test
    fun controllerAfterGestureEasesBackInsteadOfSnapping() {
        val c = ZoomController()
        assertNotNull(c.next(0.0, 15.0, 0L))
        c.onUserGesture(1_000L)
        // 10 s after the gesture: rate budget is 1.0 zoom; user zoomed out to 12.0, target 17.5.
        val z = c.next(0.0, 12.0, 11_000L)
        assertEquals(13.0, z ?: Double.NaN, 1e-9)
    }
}
