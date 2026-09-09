package com.fablab503.velotrack.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure worth catching here is a reminder that fires repeatedly. A rider who has answered
 * "keep recording" and is then asked again every thirty seconds will turn the feature off, and
 * with it the one thing that would have saved the ride they forgot to finish.
 */
class InactivityReminderTest {

    private val tenMinutes = 10L * 60_000L

    @Test
    fun `nothing is asked before the timeout`() {
        val r = InactivityReminder(tenMinutes)
        r.onMoving(0L)
        assertFalse(r.onStill(60_000L))
        assertFalse(r.onStill(9L * 60_000L))
    }

    @Test
    fun `the question is asked once the timeout has passed`() {
        val r = InactivityReminder(tenMinutes)
        r.onMoving(0L)
        assertTrue(r.onStill(tenMinutes))
    }

    @Test
    fun `the question is asked exactly once per stretch of stillness`() {
        val r = InactivityReminder(tenMinutes)
        r.onMoving(0L)
        assertTrue(r.onStill(tenMinutes))
        assertFalse("a second ask would nag", r.onStill(tenMinutes + 30_000L))
        assertFalse(r.onStill(tenMinutes + 60L * 60_000L))
    }

    @Test
    fun `riding again arms it for a later stop`() {
        val r = InactivityReminder(tenMinutes)
        r.onMoving(0L)
        assertTrue(r.onStill(tenMinutes))
        r.onMoving(tenMinutes + 1000L)
        assertFalse(r.onStill(tenMinutes + 2000L))
        assertTrue(r.onStill(2 * tenMinutes + 1000L))
    }

    @Test
    fun `keep recording restarts the clock rather than silencing it forever`() {
        val r = InactivityReminder(tenMinutes)
        r.onMoving(0L)
        assertTrue(r.onStill(tenMinutes))
        r.snooze(tenMinutes)
        assertFalse(r.onStill(tenMinutes + 60_000L))
        assertTrue("a rider who never moves again should be asked again", r.onStill(2 * tenMinutes))
    }

    @Test
    fun `zero minutes means never ask`() {
        val r = InactivityReminder(0L)
        r.onMoving(0L)
        assertFalse(r.enabled)
        assertFalse(r.onStill(24L * 60L * 60_000L))
        assertNull(r.nextCheckDelayMs(0L))
    }

    @Test
    fun `a ride that has never moved is timed from its first still sample`() {
        // Waiting for a fix in a garage must not count as ten minutes of a ride nobody finished.
        val r = InactivityReminder(tenMinutes)
        assertFalse(r.onStill(5_000L))
        assertFalse(r.onStill(5_000L + tenMinutes - 1L))
        assertTrue(r.onStill(5_000L + tenMinutes))
    }

    @Test
    fun `the next check is the time actually remaining`() {
        val r = InactivityReminder(tenMinutes)
        r.onMoving(0L)
        assertEquals(tenMinutes, r.nextCheckDelayMs(0L))
        assertEquals(tenMinutes - 60_000L, r.nextCheckDelayMs(60_000L))
        assertEquals(0L, r.nextCheckDelayMs(tenMinutes + 5_000L))
        assertTrue(r.onStill(tenMinutes))
        assertNull("nothing is pending once it has been asked", r.nextCheckDelayMs(tenMinutes))
    }

    @Test
    fun `changing the timeout in settings re-arms it`() {
        val r = InactivityReminder(tenMinutes)
        r.onMoving(0L)
        assertTrue(r.onStill(tenMinutes))
        r.afterMs = 5L * 60_000L
        assertTrue("a shorter timeout applies to the stop already in progress", r.onStill(tenMinutes + 1L))
    }

    @Test
    fun `reset forgets the ride`() {
        val r = InactivityReminder(tenMinutes)
        r.onMoving(0L)
        r.reset()
        assertNull(r.nextCheckDelayMs(tenMinutes))
        assertFalse(r.onStill(tenMinutes))
        assertFalse(r.pending)
    }
}
