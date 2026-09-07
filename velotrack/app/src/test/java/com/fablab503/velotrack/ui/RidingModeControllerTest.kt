package com.fablab503.velotrack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RidingModeControllerTest {

    private val fast = 6f / 3.6f
    private val slow = 4f / 3.6f
    private val crawl = 3f / 3.6f

    /** Feeds [speed] once a second from [fromMs] to [toMs] inclusive; returns true if [hidden] ever changed. */
    private fun RidingModeController.feed(speed: Float?, fromMs: Long, toMs: Long): Boolean {
        var changed = false
        var t = fromMs
        while (t <= toMs) {
            if (onSpeed(speed, t)) changed = true
            t += 1_000
        }
        return changed
    }

    @Test
    fun hidesAfterThreeSecondsAtSixKmh() {
        val c = RidingModeController()
        assertFalse(c.onSpeed(fast, 0))
        assertFalse(c.onSpeed(fast, 1_000))
        assertFalse(c.onSpeed(fast, 2_999))
        assertFalse(c.hidden)
        assertTrue(c.onSpeed(fast, 3_000))
        assertTrue(c.hidden)
        // Staying fast changes nothing further.
        assertFalse(c.onSpeed(fast, 4_000))
        assertTrue(c.hidden)
    }

    @Test
    fun neverHidesAtFourKmh() {
        val c = RidingModeController()
        assertFalse(c.feed(slow, 0, 30_000))
        assertFalse(c.hidden)
    }

    @Test
    fun unknownSpeedCountsAsSlow() {
        val c = RidingModeController()
        assertFalse(c.feed(null, 0, 10_000))
        assertFalse(c.hidden)
        c.feed(fast, 11_000, 14_000)
        assertTrue(c.hidden)
        assertTrue(c.onSpeed(null, 15_000).not())
        assertTrue(c.onSpeed(null, 17_000))
        assertFalse(c.hidden)
    }

    @Test
    fun touchShowsAndRehidesAfterEightSecondsWhileFast() {
        val c = RidingModeController()
        c.feed(fast, 0, 3_000)
        assertTrue(c.hidden)

        assertTrue(c.onTouch(4_000))
        assertFalse(c.hidden)
        // Still fast, but the touch keeps the controls for 8 s.
        assertFalse(c.feed(fast, 5_000, 11_000))
        assertFalse(c.hidden)
        assertTrue(c.onSpeed(fast, 12_000))
        assertTrue(c.hidden)
    }

    @Test
    fun touchWhileVisibleReturnsFalseButDelaysHiding() {
        val c = RidingModeController()
        assertFalse(c.onTouch(0))
        // Fast from 1 s: the enter timer alone would hide at 4 s, the touch pushes it to 8 s.
        assertFalse(c.feed(fast, 1_000, 7_000))
        assertFalse(c.hidden)
        assertTrue(c.onSpeed(fast, 8_000))
        assertTrue(c.hidden)
    }

    @Test
    fun slowForTwoSecondsShows() {
        val c = RidingModeController()
        c.feed(fast, 0, 3_000)
        assertTrue(c.hidden)
        assertFalse(c.onSpeed(crawl, 10_000))
        assertFalse(c.onSpeed(crawl, 11_000))
        assertTrue(c.hidden)
        assertTrue(c.onSpeed(crawl, 12_000))
        assertFalse(c.hidden)
    }

    @Test
    fun briefSlowdownDoesNotShow() {
        val c = RidingModeController()
        c.feed(fast, 0, 3_000)
        assertFalse(c.onSpeed(crawl, 4_000))
        assertFalse(c.onSpeed(fast, 5_000))
        assertTrue(c.hidden)
        // The slow run restarted, so the exit timer restarts too.
        assertFalse(c.onSpeed(crawl, 6_000))
        assertFalse(c.onSpeed(crawl, 7_500))
        assertTrue(c.onSpeed(crawl, 8_000))
    }

    @Test
    fun resetShowsAndClearsTimers() {
        val c = RidingModeController()
        c.feed(fast, 0, 3_000)
        assertTrue(c.hidden)
        assertTrue(c.reset())
        assertFalse(c.hidden)
        assertFalse(c.reset())
        assertNull(c.nextCheckDelayMs(3_000))
        // After a reset the enter timer starts from scratch.
        assertFalse(c.onSpeed(fast, 4_000))
        assertFalse(c.onSpeed(fast, 6_999))
        assertTrue(c.onSpeed(fast, 7_000))
    }

    @Test
    fun nextCheckDelayFollowsTheTouchTimeout() {
        val c = RidingModeController()
        assertNull(c.nextCheckDelayMs(0))
        c.onSpeed(fast, 0)
        assertEquals(3_000L, c.nextCheckDelayMs(0))
        assertEquals(1_000L, c.nextCheckDelayMs(2_000))
        c.feed(fast, 1_000, 3_000)
        assertTrue(c.hidden)
        // Hidden and fast: nothing changes without new input.
        assertNull(c.nextCheckDelayMs(3_000))

        c.onTouch(4_000)
        c.onSpeed(fast, 4_000)
        assertEquals(8_000L, c.nextCheckDelayMs(4_000))
        assertEquals(0L, c.nextCheckDelayMs(13_000))
        // The re-check with the last known speed performs the hide.
        assertTrue(c.onSpeed(fast, 12_000))
        assertTrue(c.hidden)

        c.onSpeed(crawl, 20_000)
        assertEquals(2_000L, c.nextCheckDelayMs(20_000))
    }
}
