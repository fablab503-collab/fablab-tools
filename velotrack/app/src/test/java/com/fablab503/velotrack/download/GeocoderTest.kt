package com.fablab503.velotrack.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocoderTest {

    @Test
    fun parsesLatLonAndDisplayNameFromNominatimJson() {
        val body = """
            [
                {"lat":"48.8566140","lon":"2.3522219","display_name":"Paris, Île-de-France, France"},
                {"lat":"48.85","lon":"-2.5","display_name":"Some other Paris"}
            ]
        """.trimIndent()
        val results = Geocoder.parse(body)
        assertEquals(2, results.size)
        assertEquals("Paris, Île-de-France, France", results[0].name)
        assertEquals(48.856614, results[0].at.lat, 1e-6)
        assertEquals(2.3522219, results[0].at.lon, 1e-6)
    }

    @Test
    fun skipsEntriesMissingCoordinatesOrAName() {
        val body = """
            [
                {"lat":"1.0","display_name":"missing lon"},
                {"lon":"1.0","display_name":"missing lat"},
                {"lat":"1.0","lon":"1.0"},
                {"lat":"1.0","lon":"1.0","display_name":"the only good one"}
            ]
        """.trimIndent()
        val results = Geocoder.parse(body)
        assertEquals(1, results.size)
        assertEquals("the only good one", results[0].name)
    }

    @Test
    fun emptyArrayGivesEmptyResults() {
        assertTrue(Geocoder.parse("[]").isEmpty())
    }
}
