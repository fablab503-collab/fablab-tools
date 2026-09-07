package com.fablab503.velotrack.pmtiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileIdTest {

    @Test
    fun specVectors() {
        assertEquals(0L, TileId.fromZxy(0, 0, 0))
        assertEquals(1L, TileId.fromZxy(1, 0, 0))
        assertEquals(2L, TileId.fromZxy(1, 0, 1))
        assertEquals(3L, TileId.fromZxy(1, 1, 1))
        assertEquals(4L, TileId.fromZxy(1, 1, 0))
        assertEquals(5L, TileId.fromZxy(2, 0, 0))
        assertEquals(21L, TileId.fromZxy(3, 0, 0))
        assertEquals(85L, TileId.fromZxy(4, 0, 0))
        assertEquals(19078479L, TileId.fromZxy(12, 3423, 1763))
        assertEquals(357913941L, TileId.fromZxy(15, 0, 0))
        assertEquals(1073741823L, TileId.fromZxy(15, 32767, 32767))
    }

    @Test
    fun toZxyInvertsSpecVectors() {
        assertArrayEquals(intArrayOf(0, 0, 0), TileId.toZxy(0L))
        assertArrayEquals(intArrayOf(1, 0, 0), TileId.toZxy(1L))
        assertArrayEquals(intArrayOf(1, 0, 1), TileId.toZxy(2L))
        assertArrayEquals(intArrayOf(1, 1, 1), TileId.toZxy(3L))
        assertArrayEquals(intArrayOf(1, 1, 0), TileId.toZxy(4L))
        assertArrayEquals(intArrayOf(2, 0, 0), TileId.toZxy(5L))
        assertArrayEquals(intArrayOf(3, 0, 0), TileId.toZxy(21L))
        assertArrayEquals(intArrayOf(4, 0, 0), TileId.toZxy(85L))
        assertArrayEquals(intArrayOf(12, 3423, 1763), TileId.toZxy(19078479L))
        assertArrayEquals(intArrayOf(15, 0, 0), TileId.toZxy(357913941L))
        assertArrayEquals(intArrayOf(15, 32767, 32767), TileId.toZxy(1073741823L))
    }

    @Test
    fun zoomStartIsCumulativeTileCount() {
        assertEquals(0L, TileId.zoomStart(0))
        assertEquals(1L, TileId.zoomStart(1))
        assertEquals(5L, TileId.zoomStart(2))
        assertEquals(21L, TileId.zoomStart(3))
        assertEquals(357913941L, TileId.zoomStart(15))
        assertEquals(TileId.zoomStart(15) + (1L shl 30), TileId.zoomStart(16))
    }

    @Test
    fun exhaustiveRoundTripZ0ToZ9() {
        for (z in 0..9) {
            val n = 1 shl z
            for (x in 0 until n) {
                for (y in 0 until n) {
                    val id = TileId.fromZxy(z, x, y)
                    val back = TileId.toZxy(id)
                    if (back[0] != z || back[1] != x || back[2] != y) {
                        throw AssertionError("round trip failed for z$z/$x/$y: id=$id -> ${back.toList()}")
                    }
                }
            }
        }
    }

    @Test
    fun idsOfOneZoomAreAPermutationOfItsRange() {
        for (z in 0..6) {
            val n = 1 shl z
            val start = TileId.zoomStart(z)
            val seen = BooleanArray(n * n)
            for (x in 0 until n) {
                for (y in 0 until n) {
                    val idx = (TileId.fromZxy(z, x, y) - start).toInt()
                    assertTrue("id out of zoom range at z$z", idx in 0 until n * n)
                    assertTrue("duplicate id at z$z", !seen[idx])
                    seen[idx] = true
                }
            }
        }
    }

    @Test
    fun consecutiveIdsAreNeighboursOnTheCurve() {
        val z = 5
        val n = 1 shl z
        val start = TileId.zoomStart(z)
        var prev = TileId.toZxy(start)
        for (i in 1 until n * n) {
            val cur = TileId.toZxy(start + i)
            val manhattan = Math.abs(cur[1] - prev[1]) + Math.abs(cur[2] - prev[2])
            assertEquals("Hilbert curve step at index $i", 1, manhattan)
            prev = cur
        }
    }

    @Test
    fun highZoomRoundTrip() {
        val samples = listOf(intArrayOf(15, 5271, 12710), intArrayOf(20, 123456, 654321), intArrayOf(30, 1, (1 shl 30) - 1))
        for (s in samples) {
            assertArrayEquals(s, TileId.toZxy(TileId.fromZxy(s[0], s[1], s[2])))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsXOutOfRange() {
        TileId.fromZxy(2, 4, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeZoom() {
        TileId.fromZxy(-1, 0, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeId() {
        TileId.toZxy(-1L)
    }
}
