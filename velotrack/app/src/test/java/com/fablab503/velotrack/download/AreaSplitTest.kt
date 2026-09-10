package com.fablab503.velotrack.download

import com.fablab503.velotrack.pmtiles.Mercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure that matters is a split whose pieces still do not fit: the rider would pick a piece,
 * wait, and get the same "too large" refusal one level deeper. Every test here therefore checks
 * every piece, not the total or the average.
 */
class AreaSplitTest {

    private val budget = 250_000L
    private val france = Mercator.BBox(-5.0, 42.5, 9.56, 51.15)
    private val russia = Mercator.BBox(19.64, 41.19, 190.1, 81.86) // east > 180: the antimeridian convention

    @Test
    fun `an area that already fits is not split`() {
        val small = Mercator.BBox(3.5, 43.3, 4.3, 43.9) // around Montpellier
        val parts = splitForBudget(small, 13, 15, budget)
        assertEquals(1, parts.size)
        assertEquals(1, parts[0].cols)
        assertEquals(1, parts[0].rows)
        assertEquals(small, parts[0].bbox)
    }

    @Test
    fun `every piece of a split fits the budget`() {
        for (box in listOf(france, russia)) {
            val parts = splitForBudget(box, 13, 15, budget)
            for (p in parts) {
                assertTrue(
                    "piece ${p.col},${p.row} of ${p.cols}x${p.rows} has ${p.gridCells} cells",
                    p.gridCells <= budget,
                )
            }
        }
    }

    @Test
    fun `France at the finest band splits into a small square grid`() {
        val parts = splitForBudget(france, 13, 15, budget)
        assertEquals(9, parts.size)
        assertEquals(3, parts[0].cols)
        assertEquals(3, parts[0].rows)
    }

    @Test
    fun `the pieces cover the whole area with no gap`() {
        val parts = splitForBudget(france, 13, 15, budget)
        assertEquals(france.west, parts.minOf { it.bbox.west }, 1e-9)
        assertEquals(france.east, parts.maxOf { it.bbox.east }, 1e-9)
        assertEquals(france.south, parts.minOf { it.bbox.south }, 1e-6)
        assertEquals(france.north, parts.maxOf { it.bbox.north }, 1e-6)
    }

    @Test
    fun `row zero is the northernmost`() {
        val parts = splitForBudget(france, 13, 15, budget)
        val top = parts.filter { it.row == 0 }
        val bottom = parts.filter { it.row == it.rows - 1 }
        assertTrue(top.minOf { it.bbox.south } > bottom.maxOf { it.bbox.north } - 1e-6)
    }

    @Test
    fun `pieces are split evenly in Mercator Y, not in degrees`() {
        // Norway spans a lot of latitude. An even split in degrees would give the northern piece far
        // more tiles than the southern one; an even split in Y gives them the same amount of work.
        val norway = Mercator.BBox(4.65, 57.98, 31.29, 71.18)
        val parts = splitForBudget(norway, 13, 15, budget)
        val byRow = parts.filter { it.col == 0 }.sortedBy { it.row }
        if (byRow.size >= 2) {
            val spread = byRow.maxOf { it.gridCells }.toDouble() / byRow.minOf { it.gridCells }
            assertTrue("rows differ by ${"%.2f".format(spread)}x", spread < 1.35)
        }
    }

    @Test
    fun `pieces beyond the antimeridian are shifted back into the world`() {
        // The `east = true + 360` convention describes a box that *crosses* 180. A piece cut
        // entirely out of the far east is not such a box, and Mercator.lonToTileX clamps anything
        // past 180 to the last column of the world - so leaving it at 185..190 silently collapsed
        // the piece onto x = 8191 and no grid ever fitted. Russia used to fall back to one
        // unsplittable piece because of it.
        val parts = splitForBudget(russia, 13, 15, budget)
        assertTrue("Russia should need hundreds of pieces, got ${parts.size}", parts.size > 600)
        for (p in parts) {
            assertTrue("piece ${p.col},${p.row} west ${p.bbox.west} is outside the world", p.bbox.west >= -180.0)
            assertTrue("piece ${p.col},${p.row} west ${p.bbox.west} is past the antimeridian", p.bbox.west < 180.0)
            assertTrue("piece ${p.col},${p.row} has east <= west", p.bbox.east > p.bbox.west)
        }
    }

    @Test
    fun `strips are rejected in favour of square pieces`() {
        // Nine horizontal strips also fits the budget; it is a useless thing to ask a rider to pick.
        val parts = splitForBudget(france, 13, 15, budget)
        assertTrue("got ${parts[0].cols}x${parts[0].rows}", parts[0].cols > 1 && parts[0].rows > 1)
    }

    @Test
    fun `a zero budget is treated as no split rather than dividing forever`() {
        val parts = splitForBudget(france, 13, 15, 0L)
        assertEquals(1, parts.size)
    }
}
