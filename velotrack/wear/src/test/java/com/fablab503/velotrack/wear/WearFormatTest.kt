package com.fablab503.velotrack.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The watch formats its own numbers, so these guard the one thing a rider would notice instantly:
 * a speed or distance in the wrong unit. The phone sends metres per second and metres always; the
 * conversion happens here.
 */
class WearFormatTest {

    @Test
    fun `speed converts to km per hour`() {
        assertEquals("36.0", WearFormat.speed(10f, imperial = false))
        assertEquals("0.0", WearFormat.speed(0f, imperial = false))
    }

    @Test
    fun `speed converts to miles per hour`() {
        assertEquals("22.4", WearFormat.speed(10f, imperial = true))
    }

    @Test
    fun `speed treats NaN and negatives as zero rather than showing nonsense`() {
        assertEquals("0.0", WearFormat.speed(Float.NaN, imperial = false))
        assertEquals("0.0", WearFormat.speed(-5f, imperial = false))
    }

    @Test
    fun `distance converts metres to kilometres and miles`() {
        assertEquals("1.00", WearFormat.distance(1000.0, imperial = false))
        assertEquals("1.00", WearFormat.distance(1609.344, imperial = true))
    }

    @Test
    fun `duration is minutes and seconds below an hour`() {
        assertEquals("0:00", WearFormat.duration(0L))
        assertEquals("1:05", WearFormat.duration(65_000L))
        assertEquals("59:59", WearFormat.duration(59 * 60_000L + 59_000L))
    }

    @Test
    fun `duration gains an hours field past an hour`() {
        assertEquals("1:00:00", WearFormat.duration(3_600_000L))
        assertEquals("2:03:04", WearFormat.duration(2 * 3_600_000L + 3 * 60_000L + 4_000L))
    }

    @Test
    fun `duration never shows a negative clock`() {
        assertEquals("0:00", WearFormat.duration(-1_000L))
    }

    @Test
    fun `units are labelled to match the conversion`() {
        assertEquals("km/h", WearFormat.speedUnit(imperial = false))
        assertEquals("mph", WearFormat.speedUnit(imperial = true))
        assertEquals("km", WearFormat.distanceUnit(imperial = false))
        assertEquals("mi", WearFormat.distanceUnit(imperial = true))
    }
}
