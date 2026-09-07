package com.fablab503.velotrack.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUrlTest {

    @Test
    fun mbtilesGetsThreeSlashes() {
        val url = MapUrl.forFile("/storage/emulated/0/Android/data/x/files/maps/berlin.mbtiles")
        assertEquals("mbtiles:///storage/emulated/0/Android/data/x/files/maps/berlin.mbtiles", url)
    }

    @Test
    fun pmtilesUsesFileScheme() {
        val url = MapUrl.forFile("/data/user/0/com.fablab503.velotrack/files/maps/alps.pmtiles")
        assertEquals("pmtiles://file:///data/user/0/com.fablab503.velotrack/files/maps/alps.pmtiles", url)
        assertTrue(url.startsWith("pmtiles://file:///"))
    }

    @Test
    fun spaceIsPercentEncoded() {
        val url = MapUrl.forFile("/storage/emulated/0/maps/my region.mbtiles")
        assertEquals("mbtiles:///storage/emulated/0/maps/my%20region.mbtiles", url)
    }

    @Test
    fun nonAsciiIsUtf8PercentEncoded() {
        val url = MapUrl.forFile("/maps/Zürich.mbtiles")
        assertEquals("mbtiles:///maps/Z%C3%BCrich.mbtiles", url)
    }

    @Test
    fun extensionIsCaseInsensitive() {
        assertEquals("mbtiles:///maps/A.MBTILES", MapUrl.forFile("/maps/A.MBTILES"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownExtensionThrows() {
        MapUrl.forFile("/maps/notes.txt")
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativePathThrows() {
        MapUrl.forFile("maps/berlin.mbtiles")
    }

    @Test
    fun encodePathKeepsUnreservedCharacters() {
        assertEquals("/a-b_c.d~e/f", MapUrl.encodePath("/a-b_c.d~e/f"))
        assertEquals("%23%3F%25%2B", MapUrl.encodePath("#?%+"))
    }
}
