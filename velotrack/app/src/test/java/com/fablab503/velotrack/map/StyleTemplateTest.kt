package com.fablab503.velotrack.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleTemplateTest {

    private val template = """
        {
          "version": 8,
          "sources": {
            "protomaps": {
              "type": "vector",
              "url": "{MAP_URL}",
              "attribution": "© OpenStreetMap contributors"
            }
          },
          "layers": []
        }
    """.trimIndent()

    @Test
    fun placeholderConstant() {
        assertEquals("{MAP_URL}", StyleTemplate.PLACEHOLDER)
    }

    @Test
    fun substitutesMapUrl() {
        val out = StyleTemplate.render(template, "mbtiles:///storage/maps/berlin.mbtiles")
        assertTrue(out.contains("\"url\": \"mbtiles:///storage/maps/berlin.mbtiles\""))
        assertFalse(out.contains(StyleTemplate.PLACEHOLDER))
        assertFalse(out.contains("\"tiles\""))
    }

    @Test
    fun substitutesEveryOccurrence() {
        val twice = "\"url\": \"{MAP_URL}\", \"other\": \"{MAP_URL}\""
        val out = StyleTemplate.render(twice, "pmtiles://file:///m.pmtiles")
        assertEquals("\"url\": \"pmtiles://file:///m.pmtiles\", \"other\": \"pmtiles://file:///m.pmtiles\"", out)
    }

    @Test
    fun nullMapUrlReplacesUrlWithEmptyTiles() {
        val out = StyleTemplate.render(template, null)
        assertTrue(out.contains("\"tiles\": []"))
        assertFalse(out.contains("\"url\""))
        assertFalse(out.contains(StyleTemplate.PLACEHOLDER))
        // The rest of the document is untouched.
        assertTrue(out.contains("\"attribution\": \"© OpenStreetMap contributors\""))
    }

    @Test
    fun nullMapUrlHandlesCompactJson() {
        val compact = "{\"sources\":{\"p\":{\"type\":\"vector\",\"url\":\"{MAP_URL}\"}}}"
        val out = StyleTemplate.render(compact, null)
        assertEquals("{\"sources\":{\"p\":{\"type\":\"vector\",\"tiles\": []}}}", out)
    }

    @Test
    fun nullMapUrlHandlesOddWhitespace() {
        val odd = "\"url\" :  \"{MAP_URL}\""
        assertEquals("\"tiles\": []", StyleTemplate.render(odd, null))
    }

    @Test
    fun templateWithoutPlaceholderIsUnchanged() {
        val plain = "{\"version\": 8}"
        assertEquals(plain, StyleTemplate.render(plain, null))
        assertEquals(plain, StyleTemplate.render(plain, "mbtiles:///x.mbtiles"))
    }
}
