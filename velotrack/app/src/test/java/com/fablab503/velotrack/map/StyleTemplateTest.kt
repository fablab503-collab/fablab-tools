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

    // ---------------------------------------------------------------- multi-band form

    private val bandTemplate = """
        {
          "version": 8,
          "sources": {
            "band0": {
              "type": "vector",
              "url": "{MAP_URL_0}",
              "attribution": "© OpenStreetMap contributors"
            },
            "band1": {
              "type": "vector",
              "url": "{MAP_URL_1}",
              "attribution": "© OpenStreetMap contributors"
            },
            "band2": {
              "type": "vector",
              "url": "{MAP_URL_2}",
              "attribution": "© OpenStreetMap contributors"
            },
            "band3": {
              "type": "vector",
              "url": "{MAP_URL_3}",
              "attribution": "© OpenStreetMap contributors"
            }
          },
          "layers": []
        }
    """.trimIndent()

    private val bandUrls: List<String?> = listOf(
        "mbtiles:///maps/band0.mbtiles",
        "mbtiles:///maps/band1.mbtiles",
        "mbtiles:///maps/band2.mbtiles",
        "mbtiles:///maps/band3.mbtiles",
    )

    @Test
    fun placeholderPrefixAndIndexedPlaceholder() {
        assertEquals("{MAP_URL_", StyleTemplate.PLACEHOLDER_PREFIX)
        assertEquals("{MAP_URL_0}", StyleTemplate.placeholder(0))
        assertEquals("{MAP_URL_3}", StyleTemplate.placeholder(3))
    }

    @Test
    fun substitutesEveryBandUrl() {
        val out = StyleTemplate.render(bandTemplate, bandUrls)
        for (i in 0 until 4) {
            assertTrue(out.contains("\"url\": \"mbtiles:///maps/band$i.mbtiles\""))
        }
        assertFalse(out.contains(StyleTemplate.PLACEHOLDER_PREFIX))
        assertFalse(out.contains("\"tiles\""))
        // The rest of the document is untouched.
        assertEquals(4, Regex("© OpenStreetMap contributors").findAll(out).count())
    }

    @Test
    fun nullBandUrlBecomesEmptyTilesOnlyForThatBand() {
        val urls: List<String?> = listOf(bandUrls[0], null, bandUrls[2], bandUrls[3])
        val out = StyleTemplate.render(bandTemplate, urls)
        assertTrue(out.contains("\"url\": \"mbtiles:///maps/band0.mbtiles\""))
        assertTrue(out.contains("\"url\": \"mbtiles:///maps/band2.mbtiles\""))
        assertTrue(out.contains("\"url\": \"mbtiles:///maps/band3.mbtiles\""))
        assertFalse(out.contains("{MAP_URL_1}"))
        assertFalse(out.contains("band1.mbtiles"))
        assertEquals(1, Regex("\"tiles\": \\[\\]").findAll(out).count())
        assertFalse(out.contains(StyleTemplate.PLACEHOLDER_PREFIX))
    }

    @Test
    fun indexedPlaceholdersDoNotCollide() {
        // {MAP_URL_1} must not touch {MAP_URL_10}; unknown indices stay untouched.
        val t = "\"url\": \"{MAP_URL_1}\", \"url\": \"{MAP_URL_10}\""
        val out = StyleTemplate.render(t, listOf<String?>(null, "mbtiles:///one.mbtiles"))
        assertEquals("\"url\": \"mbtiles:///one.mbtiles\", \"url\": \"{MAP_URL_10}\"", out)
    }

    @Test
    fun shorterListLeavesRemainingPlaceholders() {
        val out = StyleTemplate.render(bandTemplate, listOf<String?>("mbtiles:///maps/band0.mbtiles"))
        assertTrue(out.contains("\"url\": \"mbtiles:///maps/band0.mbtiles\""))
        assertTrue(out.contains("{MAP_URL_1}"))
        assertTrue(out.contains("{MAP_URL_2}"))
        assertTrue(out.contains("{MAP_URL_3}"))
    }

    @Test
    fun nullBandUrlHandlesCompactJsonAndOddWhitespace() {
        val compact = "{\"sources\":{\"band0\":{\"type\":\"vector\",\"url\":\"{MAP_URL_0}\"}}}"
        assertEquals(
            "{\"sources\":{\"band0\":{\"type\":\"vector\",\"tiles\": []}}}",
            StyleTemplate.render(compact, listOf<String?>(null)),
        )
        val odd = "\"url\" :  \"{MAP_URL_2}\""
        assertEquals("\"tiles\": []", StyleTemplate.render(odd, listOf<String?>(null, null, null)))
    }

    @Test
    fun emptyListLeavesTemplateUnchanged() {
        assertEquals(bandTemplate, StyleTemplate.render(bandTemplate, emptyList<String?>()))
    }

    @Test
    fun multiBandRenderIgnoresLegacyPlaceholder() {
        val out = StyleTemplate.render(template, bandUrls)
        assertEquals(template, out)
    }
}
