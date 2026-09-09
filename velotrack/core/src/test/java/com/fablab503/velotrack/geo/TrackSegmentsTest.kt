package com.fablab503.velotrack.geo

import com.fablab503.velotrack.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure worth catching is a segment boundary being lost, because the symptom is not a crash:
 * it is a map that draws a straight line across a gap the rider never rode.
 */
class TrackSegmentsTest {

    private fun p(segment: Int, lat: Double) = TrackPoint(
        timeMs = 0L,
        lat = lat,
        lon = 0.0,
        ele = null,
        speedMps = null,
        accuracyM = 5f,
        segment = segment,
    )

    @Test
    fun `no points, no segments`() {
        assertTrue(segmentsOf(emptyList()).isEmpty())
    }

    @Test
    fun `a ride with no gaps is one run`() {
        val out = segmentsOf(listOf(p(0, 1.0), p(0, 2.0), p(0, 3.0)))
        assertEquals(1, out.size)
        assertEquals(3, out[0].size)
    }

    @Test
    fun `a ride continued later is two runs, not one line`() {
        val out = segmentsOf(listOf(p(0, 1.0), p(0, 2.0), p(1, 40.0), p(1, 41.0)))
        assertEquals(2, out.size)
        assertEquals(listOf(1.0, 2.0), out[0].map { it.lat })
        assertEquals(listOf(40.0, 41.0), out[1].map { it.lat })
    }

    @Test
    fun `several gaps produce several runs`() {
        val out = segmentsOf(listOf(p(0, 1.0), p(1, 2.0), p(2, 3.0), p(2, 4.0)))
        assertEquals(3, out.size)
        assertEquals(1, out[0].size)
        assertEquals(1, out[1].size)
        assertEquals(2, out[2].size)
    }

    @Test
    fun `a segment index that comes back never re-joins the earlier run`() {
        // Not something the recorder produces, but if it ever did, joining them would draw a line
        // between two places the rider was hours apart.
        val out = segmentsOf(listOf(p(0, 1.0), p(1, 50.0), p(0, 2.0)))
        assertEquals(3, out.size)
        assertEquals(listOf(2.0), out[2].map { it.lat })
    }

    @Test
    fun `a track that does not start at segment zero still works`() {
        // Exactly what a continued ride looks like when only its new leg is loaded.
        val out = segmentsOf(listOf(p(4, 1.0), p(4, 2.0)))
        assertEquals(1, out.size)
        assertEquals(2, out[0].size)
    }
}
