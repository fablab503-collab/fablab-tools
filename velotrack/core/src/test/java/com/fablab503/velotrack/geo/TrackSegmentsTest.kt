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

    // ---- gapsBetween -------------------------------------------------------------------------

    private fun ll(lat: Double, lon: Double = 0.0) = com.fablab503.velotrack.model.LatLon(lat, lon)

    @Test
    fun `a ride with no gaps has nothing to draw across`() {
        assertTrue(gapsBetween(emptyList(), 100.0).isEmpty())
        assertTrue(gapsBetween(listOf(listOf(ll(1.0), ll(1.001))), 100.0).isEmpty())
    }

    @Test
    fun `a gap is the end of one run to the start of the next`() {
        val a = listOf(ll(43.60), ll(43.61))
        val b = listOf(ll(43.70), ll(43.71))
        val gaps = gapsBetween(listOf(a, b), 100.0)
        assertEquals(1, gaps.size)
        assertEquals(ll(43.61), gaps[0].first)
        assertEquals(ll(43.70), gaps[0].second)
    }

    @Test
    fun `runs that all but touch are not a gap`() {
        // ~11 m apart at this latitude: the recorder split them for time, not distance, so there
        // is nothing to draw and a dash would be noise.
        val a = listOf(ll(43.6000), ll(43.6001))
        val b = listOf(ll(43.60020), ll(43.6003))
        assertTrue(gapsBetween(listOf(a, b), 100.0).isEmpty())
    }

    @Test
    fun `two gaps in one ride produce two connectors`() {
        val runs = listOf(listOf(ll(43.60)), listOf(ll(43.70)), listOf(ll(43.80)))
        val gaps = gapsBetween(runs, 100.0)
        assertEquals(2, gaps.size)
        assertEquals(ll(43.60) to ll(43.70), gaps[0])
        assertEquals(ll(43.70) to ll(43.80), gaps[1])
    }

    @Test
    fun `an empty run in the middle is skipped rather than crashing`() {
        val runs = listOf(listOf(ll(43.60)), emptyList(), listOf(ll(43.80)))
        // The empty run contributes no endpoint, so neither of its two sides yields a connector.
        assertTrue(gapsBetween(runs, 100.0).isEmpty())
    }
}
