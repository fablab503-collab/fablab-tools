package com.fablab503.velotrack.ui

import com.fablab503.velotrack.model.Units
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTemperatureTest {

    @Test
    fun metricRoundsToWholeDegreesCelsius() {
        assertEquals("16°C", Format.temperature(16.4f, Units.METRIC))
        assertEquals("-3°C", Format.temperature(-3.2f, Units.METRIC))
    }

    @Test
    fun imperialConvertsToFahrenheit() {
        assertEquals("61°F", Format.temperature(16.0f, Units.IMPERIAL)) // 16C = 60.8F
        assertEquals("32°F", Format.temperature(0.0f, Units.IMPERIAL))
    }
}
