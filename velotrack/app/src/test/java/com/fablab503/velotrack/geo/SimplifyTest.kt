package com.fablab503.velotrack.geo

import com.fablab503.velotrack.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplifyTest {
    private fun line(n: Int): List<LatLon> = List(n) { i -> LatLon(48.0 + i * 0.0001, 17.0) }

    @Test
    fun rdpCollapsesCollinearPointsToTwo() {
        val pts = line(50)
        val out = Simplify.rdp(pts, 2.0)
        assertEquals(2, out.size)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test
    fun rdpKeepsCornerOfAnLShape() {
        // 11 points going north, then 10 points going east from the last one: only the corner survives.
        val north = line(11)
        val corner = north.last()
        val east = List(10) { i -> LatLon(corner.lat, corner.lon + (i + 1) * 0.0001) }
        val pts = north + east
        val out = Simplify.rdp(pts, 2.0)
        assertEquals(3, out.size)
        assertEquals(pts.first(), out[0])
        assertEquals(corner, out[1])
        assertEquals(pts.last(), out[2])
    }

    @Test
    fun rdpDropsDeviationBelowTolerance() {
        val pts = ArrayList(line(21))
        // ~0.74 m offset: below a 2 m tolerance.
        pts[10] = LatLon(pts[10].lat, pts[10].lon + 0.00001)
        val out = Simplify.rdp(pts, 2.0)
        assertEquals(2, out.size)
    }

    @Test
    fun rdpHandlesEmptySingleAndPairs() {
        assertEquals(0, Simplify.rdp(emptyList(), 2.0).size)
        val one = listOf(LatLon(1.0, 2.0))
        assertEquals(one, Simplify.rdp(one, 2.0))
        val two = listOf(LatLon(1.0, 2.0), LatLon(1.1, 2.1))
        assertEquals(two, Simplify.rdp(two, 2.0))
    }

    @Test
    fun rdpHandlesLargeInputWithoutStackOverflow() {
        // 150k points (a long recorded track) on a deterministic pseudo-random walk: most points
        // deviate by more than 2 m from any chord, so a recursive implementation would go deep.
        val n = 150_000
        var seed = 12345L
        var lon = 17.0
        val pts = ArrayList<LatLon>(n)
        for (i in 0 until n) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val u = (seed ushr 11).toDouble() / (1L shl 53).toDouble()
            lon += (u * 2.0 - 1.0) * 0.0003
            pts.add(LatLon(48.0 + i * 0.0001, lon))
        }
        val out = Simplify.rdp(pts, 2.0)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        assertTrue("kept ${out.size}", out.size > n / 2 && out.size <= n)
    }

    @Test
    fun rdpPreservesOrder() {
        val pts = List(200) { i ->
            LatLon(48.0 + i * 0.0001, 17.0 + kotlin.math.sin(i / 10.0) * 0.001)
        }
        val out = Simplify.rdp(pts, 5.0)
        assertTrue(out.size in 3 until pts.size)
        var lastIndex = -1
        for (p in out) {
            val idx = pts.indexOf(p)
            assertTrue(idx > lastIndex)
            lastIndex = idx
        }
    }

    @Test
    fun radialKeepsFirstAndLast() {
        val pts = line(10) // consecutive points ~11 m apart
        val out = Simplify.radial(pts, 1_000.0)
        assertEquals(2, out.size)
        assertEquals(pts.first(), out[0])
        assertEquals(pts.last(), out[1])
    }

    @Test
    fun radialKeepsPointsAtLeastMinDistApart() {
        val pts = line(11) // ~11.05 m spacing
        val out = Simplify.radial(pts, 20.0)
        // Every second point survives (22 m), plus the last one.
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        for (i in 1 until out.size - 1) {
            assertTrue(Geo.distanceM(out[i - 1], out[i]) >= 20.0)
        }
        assertTrue(out.size < pts.size)
    }

    @Test
    fun radialWithZeroMinDistKeepsEverything() {
        val pts = line(10)
        assertEquals(pts, Simplify.radial(pts, 0.0))
    }

    @Test
    fun radialHandlesShortLists() {
        assertEquals(0, Simplify.radial(emptyList(), 5.0).size)
        val one = listOf(LatLon(1.0, 2.0))
        assertEquals(one, Simplify.radial(one, 5.0))
        val two = listOf(LatLon(1.0, 2.0), LatLon(1.0, 2.0))
        assertEquals(two, Simplify.radial(two, 5.0))
    }
}
