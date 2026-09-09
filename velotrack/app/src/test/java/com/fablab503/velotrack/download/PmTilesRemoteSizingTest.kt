package com.fablab503.velotrack.download

import com.fablab503.velotrack.pmtiles.Mercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the crash a rider would have hit downloading a country the size of Germany
 * at "Fully detailed" (BAND_STREETS, z13-15): [PmTilesRemote.plan] used to build one [PlannedTile]
 * and one `HashMap` entry per grid cell with no upper bound, and that many objects exhausted the
 * download process's heap (`OutOfMemoryError` in `PmDirectory.decode`, seen live on the emulator).
 * These tests exercise the pure, network-free arithmetic ([gridTileCount], [formatCount]) that now
 * catches an area this large before [PmTilesRemote.plan] allocates anything.
 */
class PmTilesRemoteSizingTest {

    private val germany = Countries.ALL.first { it.code == "DE" }
    private val streets = Bands.STREETS // z13-15, the band that actually crashed

    @Test
    fun wholeCountryAtStreetLevelExceedsTheLimit() {
        val bbox = Mercator.BBox(germany.west, germany.south, germany.east, germany.north)
        val cells = gridTileCount(bbox, streets.minZoom, streets.maxZoom)
        assertTrue(
            "expected Germany at z${streets.minZoom}-${streets.maxZoom} to exceed the plannable " +
                "limit (this is the exact case that used to OutOfMemoryError); got $cells cells",
            cells > PmTilesRemote.MAX_PLANNABLE_TILES,
        )
    }

    @Test
    fun tenKilometresAroundARiderStaysWellUnderTheLimit() {
        // The app's own smallest/finest preset: "Nearby - 10 km - every street". This must keep
        // working exactly as before; the guard exists for country-sized requests, not this one.
        val bbox = Mercator.circleBBox(48.8566, 2.3522, 10.0) // Paris
        val cells = gridTileCount(bbox, streets.minZoom, streets.maxZoom)
        assertTrue(
            "a routine 10 km download should stay far under the limit; got $cells cells",
            cells < PmTilesRemote.MAX_PLANNABLE_TILES / 10,
        )
    }

    @Test
    fun aSmallCountryAtStreetLevelStaysUnderTheLimit() {
        // Confirms the guard blocks the huge case without blocking every country outright.
        val monaco = Countries.ALL.first { it.code == "MC" }
        val bbox = Mercator.BBox(monaco.west, monaco.south, monaco.east, monaco.north)
        val cells = gridTileCount(bbox, streets.minZoom, streets.maxZoom)
        assertTrue("Monaco is under 2 km across; expected well under the limit, got $cells", cells < PmTilesRemote.MAX_PLANNABLE_TILES)
    }

    @Test
    fun formatCountGroupsThousands() {
        assertEquals("0", formatCount(0))
        assertEquals("42", formatCount(42))
        assertEquals("999", formatCount(999))
        assertEquals("1,000", formatCount(1_000))
        assertEquals("941,832", formatCount(941_832))
        assertEquals("1,234,567", formatCount(1_234_567))
    }

    @Test
    fun exceptionMessageNamesTheCountAndAnAlternative() {
        val message = AreaTooLargeException(1_234_567L).message.orEmpty()
        assertTrue("expected the formatted count in the message: $message", message.contains("1,234,567"))
        assertTrue("expected a suggested alternative in the message: $message", message.contains("Simple map"))
    }
}
