package com.fablab503.velotrack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WeatherIconsTest {

    @Test
    fun clearSkyDiffersByDayAndNight() {
        assertEquals("☀️", weatherEmoji("clearsky_day"))
        assertEquals("🌙", weatherEmoji("clearsky_night"))
    }

    @Test
    fun thunderBeatsRainInPriority() {
        // "rainandthunder" contains both "rain" and "thunder"; thunder must win the icon choice
        // since it is the more severe, more surprising condition to miss.
        assertEquals("⛈️", weatherEmoji("rainandthunder"))
        assertEquals("⛈️", weatherEmoji("heavysnowandthunder"))
    }

    @Test
    fun lightRainDiffersFromHeavyRain() {
        assertNotEquals(weatherEmoji("lightrainshowers_day"), weatherEmoji("heavyrain"))
    }

    @Test
    fun unrecognisedCodeStillReturnsSomething() {
        assertEquals("🌡️", weatherEmoji("not_a_real_code"))
    }

    @Test
    fun daySuffixDoesNotAffectNonClearConditions() {
        // Only clearsky/fair/partlycloudy branch on day vs night; everything else ignores it.
        assertEquals(weatherEmoji("cloudy"), weatherEmoji("cloudy_day"))
    }
}
