package com.fablab503.velotrack.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherClientTest {

    @Test
    fun readsTemperatureAndSymbolFromNextOneHours() {
        val body = """
            {"properties":{"timeseries":[
                {"time":"2026-09-09T08:00:00Z","data":{
                    "instant":{"details":{"air_temperature":16.4}},
                    "next_1_hours":{"summary":{"symbol_code":"partlycloudy_day"}}
                }}
            ]}}
        """.trimIndent()
        val now = WeatherClient.parse(body)
        assertEquals(16.4f, now!!.temperatureC, 0.01f)
        assertEquals("partlycloudy_day", now.symbolCode)
    }

    @Test
    fun fallsBackToNextSixHoursWhenNextOneHoursIsMissing() {
        // The far-future end of the compact product's timeseries only carries next_6_hours.
        val body = """
            {"properties":{"timeseries":[
                {"time":"2026-09-10T08:00:00Z","data":{
                    "instant":{"details":{"air_temperature":9.0}},
                    "next_6_hours":{"summary":{"symbol_code":"cloudy"}}
                }}
            ]}}
        """.trimIndent()
        val now = WeatherClient.parse(body)
        assertEquals("cloudy", now!!.symbolCode)
    }

    @Test
    fun nullWhenTemperatureIsMissing() {
        val body = """{"properties":{"timeseries":[{"data":{"next_1_hours":{"summary":{"symbol_code":"fair_day"}}}}]}}"""
        assertNull(WeatherClient.parse(body))
    }

    @Test
    fun nullWhenNoSummaryWindowHasASymbol() {
        val body = """{"properties":{"timeseries":[{"data":{"instant":{"details":{"air_temperature":5.0}}}}]}}"""
        assertNull(WeatherClient.parse(body))
    }

    @Test
    fun nullOnEmptyTimeseries() {
        assertNull(WeatherClient.parse("""{"properties":{"timeseries":[]}}"""))
    }

    @Test
    fun officialForecastUrlIsAYrDailyTablePage() {
        val url = WeatherClient.officialForecastUrl(48.8566, 2.3522)
        assertEquals("https://www.yr.no/en/forecast/daily-table/48.8566,2.3522", url)
    }
}
