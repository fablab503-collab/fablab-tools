package com.fablab503.velotrack.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SolarTimesTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val paris = ZoneId.of("Europe/Paris")
    private val parisLat = 48.8566
    private val parisLon = 2.3522
    private val minuteMs = 60_000L

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun zonedMs(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun assertBetween(label: String, value: Long, fromInclusive: Long, toInclusive: Long) {
        assertTrue(
            "$label=${Instant.ofEpochMilli(value)} not in [${Instant.ofEpochMilli(fromInclusive)}, ${Instant.ofEpochMilli(toInclusive)}]",
            value in fromInclusive..toInclusive,
        )
    }

    @Test
    fun equatorEquinoxSunriseAndSunsetNearSixAndEighteenUtc() {
        val result = SolarTimes.sunriseSunset(0.0, 0.0, utcMs(2026, 3, 20, 12, 0), utc)
        assertNotNull(result)
        val (rise, set) = result!!
        assertBetween("sunrise", rise, utcMs(2026, 3, 20, 6, 0) - 15 * minuteMs, utcMs(2026, 3, 20, 6, 0) + 15 * minuteMs)
        assertBetween("sunset", set, utcMs(2026, 3, 20, 18, 0) - 15 * minuteMs, utcMs(2026, 3, 20, 18, 0) + 15 * minuteMs)
        assertTrue(rise < set)
    }

    @Test
    fun parisSummerSolsticeMatchesNoaa() {
        // NOAA: sunrise 03:47 UTC, sunset 19:58 UTC.
        val result = SolarTimes.sunriseSunset(parisLat, parisLon, utcMs(2026, 6, 21, 12, 0), paris)
        assertNotNull(result)
        val (rise, set) = result!!
        assertBetween("sunrise", rise, utcMs(2026, 6, 21, 3, 40), utcMs(2026, 6, 21, 4, 0))
        assertBetween("sunset", set, utcMs(2026, 6, 21, 19, 50), utcMs(2026, 6, 21, 20, 5))
    }

    @Test
    fun sameCivilDayRegardlessOfQueryTime() {
        val atMidnight = SolarTimes.sunriseSunset(parisLat, parisLon, zonedMs(paris, 2026, 6, 21, 0, 0), paris)
        val atNoon = SolarTimes.sunriseSunset(parisLat, parisLon, zonedMs(paris, 2026, 6, 21, 12, 0), paris)
        val beforeMidnight = SolarTimes.sunriseSunset(parisLat, parisLon, zonedMs(paris, 2026, 6, 21, 23, 59), paris)
        assertEquals(atNoon, atMidnight)
        assertEquals(atNoon, beforeMidnight)
    }

    @Test
    fun southernHemisphereWinterDayInLocalZone() {
        val sydney = ZoneId.of("Australia/Sydney")
        val result = SolarTimes.sunriseSunset(-33.8688, 151.2093, zonedMs(sydney, 2026, 6, 21, 12, 0), sydney)
        assertNotNull(result)
        val (rise, set) = result!!
        // Local sunrise about 07:00, sunset about 16:54, both on the same local date.
        assertBetween("sunrise", rise, zonedMs(sydney, 2026, 6, 21, 6, 30), zonedMs(sydney, 2026, 6, 21, 7, 30))
        assertBetween("sunset", set, zonedMs(sydney, 2026, 6, 21, 16, 30), zonedMs(sydney, 2026, 6, 21, 17, 30))
        assertEquals(LocalDate.of(2026, 6, 21), Instant.ofEpochMilli(rise).atZone(sydney).toLocalDate())
        assertEquals(LocalDate.of(2026, 6, 21), Instant.ofEpochMilli(set).atZone(sydney).toLocalDate())
    }

    @Test
    fun polarDayAndPolarNightReturnNull() {
        assertNull(SolarTimes.sunriseSunset(80.0, 0.0, utcMs(2026, 6, 21, 12, 0), utc))
        assertNull(SolarTimes.sunriseSunset(80.0, 0.0, utcMs(2026, 12, 21, 12, 0), utc))
        assertNull(SolarTimes.sunriseSunset(-80.0, 0.0, utcMs(2026, 12, 21, 12, 0), utc))
    }

    @Test
    fun isNightFallbackUsesSevenToNineteenLocal() {
        assertTrue(SolarTimes.isNight(null, null, zonedMs(paris, 2026, 9, 7, 3, 0), paris))
        assertFalse(SolarTimes.isNight(null, null, zonedMs(paris, 2026, 9, 7, 12, 0), paris))
        assertFalse(SolarTimes.isNight(null, null, zonedMs(paris, 2026, 9, 7, 7, 0), paris))
        assertTrue(SolarTimes.isNight(null, null, zonedMs(paris, 2026, 9, 7, 19, 0), paris))
        assertTrue(SolarTimes.isNight(null, null, zonedMs(paris, 2026, 9, 7, 23, 30), paris))
        // Only one coordinate known also falls back.
        assertTrue(SolarTimes.isNight(parisLat, null, zonedMs(paris, 2026, 9, 7, 3, 0), paris))
    }

    @Test
    fun isNightWithPositionFollowsSunriseAndSunset() {
        assertTrue(SolarTimes.isNight(parisLat, parisLon, utcMs(2026, 6, 21, 2, 0), paris))
        assertFalse(SolarTimes.isNight(parisLat, parisLon, utcMs(2026, 6, 21, 12, 0), paris))
        assertTrue(SolarTimes.isNight(parisLat, parisLon, utcMs(2026, 6, 21, 22, 0), paris))
        val (rise, set) = SolarTimes.sunriseSunset(parisLat, parisLon, utcMs(2026, 6, 21, 12, 0), paris)!!
        assertTrue(SolarTimes.isNight(parisLat, parisLon, rise - 1, paris))
        assertFalse(SolarTimes.isNight(parisLat, parisLon, rise, paris))
        assertFalse(SolarTimes.isNight(parisLat, parisLon, set - 1, paris))
        assertTrue(SolarTimes.isNight(parisLat, parisLon, set, paris))
    }

    @Test
    fun isNightDuringPolarDayIsFalseAndPolarNightIsTrue() {
        assertFalse(SolarTimes.isNight(80.0, 0.0, utcMs(2026, 6, 21, 0, 0), utc))
        assertTrue(SolarTimes.isNight(80.0, 0.0, utcMs(2026, 12, 21, 12, 0), utc))
    }

    @Test
    fun nextTransitionFallbackHitsSevenOrNineteen() {
        val now3 = zonedMs(paris, 2026, 9, 7, 3, 0)
        assertEquals(zonedMs(paris, 2026, 9, 7, 7, 0), SolarTimes.nextTransitionMs(null, null, now3, paris))
        val now12 = zonedMs(paris, 2026, 9, 7, 12, 0)
        assertEquals(zonedMs(paris, 2026, 9, 7, 19, 0), SolarTimes.nextTransitionMs(null, null, now12, paris))
        val now20 = zonedMs(paris, 2026, 9, 7, 20, 0)
        assertEquals(zonedMs(paris, 2026, 9, 8, 7, 0), SolarTimes.nextTransitionMs(null, null, now20, paris))
        // Exactly at the boundary the next flip is the following one.
        val now7 = zonedMs(paris, 2026, 9, 7, 7, 0)
        assertEquals(zonedMs(paris, 2026, 9, 7, 19, 0), SolarTimes.nextTransitionMs(null, null, now7, paris))
    }

    @Test
    fun nextTransitionWithPositionIsNextSunsetOrSunrise() {
        val (rise, set) = SolarTimes.sunriseSunset(parisLat, parisLon, utcMs(2026, 6, 21, 12, 0), paris)!!
        val (riseTomorrow, _) = SolarTimes.sunriseSunset(parisLat, parisLon, utcMs(2026, 6, 22, 12, 0), paris)!!
        assertEquals(rise, SolarTimes.nextTransitionMs(parisLat, parisLon, utcMs(2026, 6, 21, 2, 0), paris))
        assertEquals(set, SolarTimes.nextTransitionMs(parisLat, parisLon, utcMs(2026, 6, 21, 12, 0), paris))
        assertEquals(riseTomorrow, SolarTimes.nextTransitionMs(parisLat, parisLon, utcMs(2026, 6, 21, 22, 0), paris))
        assertEquals(riseTomorrow, SolarTimes.nextTransitionMs(parisLat, parisLon, set, paris))
    }

    @Test
    fun nextTransitionIsAlwaysAfterNow() {
        val samples = listOf(
            Triple(null, null, zonedMs(paris, 2026, 9, 7, 3, 0)),
            Triple(null, null, zonedMs(paris, 2026, 9, 7, 19, 0)),
            Triple(parisLat, parisLon, utcMs(2026, 6, 21, 12, 0)),
            Triple(80.0, 0.0, utcMs(2026, 6, 21, 12, 0)),
            Triple(80.0, 0.0, utcMs(2026, 12, 21, 12, 0)),
            Triple(-33.8688, 151.2093, utcMs(2026, 6, 21, 0, 0)),
        )
        for ((lat, lon, now) in samples) {
            val next = SolarTimes.nextTransitionMs(lat, lon, now, paris)
            assertTrue("next=$next now=$now for ($lat, $lon)", next > now)
        }
    }
}
