package com.fablab503.velotrack.pmtiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MercatorTest {

    @Test
    fun knownTileCoordinates() {
        assertEquals(0, Mercator.lonToTileX(0.0, 0))
        assertEquals(0, Mercator.latToTileY(0.0, 0))
        assertEquals(1, Mercator.lonToTileX(0.0, 1))
        assertEquals(1, Mercator.latToTileY(0.0, 1))
        assertEquals(0, Mercator.lonToTileX(-180.0, 3))
        assertEquals(7, Mercator.lonToTileX(179.99, 3))
        assertEquals(7, Mercator.lonToTileX(180.0, 3))        // clamped to the last column
        assertEquals(0, Mercator.latToTileY(85.0511, 2))
        assertEquals(0, Mercator.latToTileY(90.0, 2))         // clamped latitude
        assertEquals(3, Mercator.latToTileY(-90.0, 2))
        // Paris at z10 is tile 518/352; Mountain View at z15 is 5271/12710.
        assertEquals(518, Mercator.lonToTileX(2.3522, 10))
        assertEquals(352, Mercator.latToTileY(48.8566, 10))
        assertEquals(5271, Mercator.lonToTileX(-122.0839, 15))
        assertEquals(12710, Mercator.latToTileY(37.3861, 15))
    }

    @Test
    fun circleBBoxAtEquatorIsNearlySquare() {
        val b = Mercator.circleBBox(0.0, 0.0, 100.0)
        val height = b.north - b.south
        val width = b.east - b.west
        assertEquals(2 * 0.8993, height, 0.01)
        assertEquals(height, width, 0.01)
        assertEquals(-b.west, b.east, 1e-9)
        assertEquals(-b.south, b.north, 1e-9)
    }

    @Test
    fun circleBBoxAt60NorthIsTwiceAsWideInDegrees() {
        val b = Mercator.circleBBox(60.0, 0.0, 100.0)
        val height = b.north - b.south
        val width = b.east - b.west
        assertEquals(2 * 0.8993, height, 0.01)
        assertEquals(60.8993, b.north, 0.01)
        assertEquals(59.1007, b.south, 0.01)
        // cos(60) = 0.5 -> about twice as wide; slightly more because the width is taken at the northern edge.
        assertTrue("width $width", width > 3.5 && width < 3.8)
        assertEquals(2.0, width / height, 0.1)
    }

    @Test
    fun circleBBoxCrossingAntimeridianKeepsEastBeyond180() {
        val b = Mercator.circleBBox(0.0, 179.5, 100.0)
        assertTrue(b.east > 180.0)
        assertTrue(b.west < 180.0 && b.west > 178.0)
        val w = Mercator.circleBBox(0.0, -179.5, 100.0)
        assertTrue(w.west < -180.0)
    }

    @Test
    fun circleBBoxClampsLatitudeAndCoversAllLongitudesNearThePole() {
        val b = Mercator.circleBBox(89.0, 10.0, 500.0)
        assertEquals(-180.0, b.west, 0.0)
        assertEquals(180.0, b.east, 0.0)
        assertEquals(Mercator.MAX_LAT, b.north, 0.0)
        val world = Mercator.circleBBox(0.0, 0.0, 20000.0)
        assertEquals(Mercator.BBox(-180.0, -Mercator.MAX_LAT, 180.0, Mercator.MAX_LAT), world)
    }

    @Test
    fun tileRangesSimpleBox() {
        // France preset bbox at z7.
        val (xs, ys) = Mercator.tileRanges(Mercator.BBox(-5.5, 41.3, 9.7, 51.2), 7)
        assertEquals(listOf(62..67), xs)
        assertEquals(42..47, ys)
    }

    @Test
    fun tileRangesCrossingAntimeridianGiveTwoXRanges() {
        val (xs, ys) = Mercator.tileRanges(Mercator.BBox(179.0, -1.0, 181.0, 1.0), 3)
        assertEquals(listOf(7..7, 0..0), xs)
        assertEquals(3..4, ys)
        val (xs2, ys2) = Mercator.tileRanges(Mercator.BBox(-181.0, -1.0, -179.0, 1.0), 3)
        assertEquals(listOf(7..7, 0..0), xs2)
        assertEquals(3..4, ys2)
    }

    @Test
    fun tileRangesCrossingAntimeridianCollapseAtLowZoom() {
        val (xs, ys) = Mercator.tileRanges(Mercator.BBox(179.0, -1.0, 181.0, 1.0), 0)
        assertEquals(listOf(0..0), xs)
        assertEquals(0..0, ys)
    }

    @Test
    fun tileRangesWholeWorld() {
        val (xs, ys) = Mercator.tileRanges(Mercator.BBox(-180.0, -Mercator.MAX_LAT, 180.0, Mercator.MAX_LAT), 2)
        assertEquals(listOf(0..3), xs)
        assertEquals(0..3, ys)
        val (xs2, _) = Mercator.tileRanges(Mercator.BBox(-200.0, -10.0, 200.0, 10.0), 4)
        assertEquals(listOf(0..15), xs2)
    }

    @Test
    fun normalizeLon() {
        assertEquals(-179.0, Mercator.normalizeLon(181.0), 1e-9)
        assertEquals(179.0, Mercator.normalizeLon(-181.0), 1e-9)
        assertEquals(180.0, Mercator.normalizeLon(180.0), 1e-9)
        assertEquals(10.0, Mercator.normalizeLon(370.0), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invertedBBoxIsRejected() {
        Mercator.tileRanges(Mercator.BBox(10.0, 0.0, 5.0, 1.0), 3)
    }
}
