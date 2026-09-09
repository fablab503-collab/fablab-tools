package com.fablab503.velotrack.recording

import com.fablab503.velotrack.geo.Simplify
import com.fablab503.velotrack.gpx.GpxWriter
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.model.TrackPoint
import java.io.OutputStream
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the promise the store listing makes: rides of up to 1000 km.
 *
 * At one fix per second and 20 km/h that is 180 000 stored points and roughly fifty hours of
 * riding. These tests replay a track of exactly that size through the three places that touch every
 * point of a ride, and fail if any of them stops scaling.
 *
 * The time limits are deliberately loose. They are not benchmarks: they exist so that replacing an
 * O(n log n) algorithm with a quadratic one is caught here rather than by a rider fifty hours in.
 * A quadratic simplification of this track would need about 3 x 10^10 distance computations, which
 * is minutes, so a twenty second ceiling separates the two cases with a wide margin while staying
 * far above the roughly 0.2 s the current code needs on a warm JVM.
 */
class LongRideScaleTest {

    /**
     * A plausible fifty hour ride: one fix per second at 20 km/h, a heading that wanders, and three
     * metres of GPS noise on every fix. The noise matters, because a perfectly smooth line is the
     * easy case for simplification and would hide a regression.
     */
    private fun ridePoints(count: Int, seed: Long = 20260908L): Sequence<TrackPoint> = sequence {
        val random = Random(seed)
        var lat = 48.8566
        var lon = 2.3522
        var heading = 0.0
        var elevation = 35.0
        var timeMs = 1_757_000_000_000L
        repeat(count) {
            heading += random.nextGaussian() * 0.05
            lat += (METRES_PER_SECOND * cos(heading) + random.nextGaussian() * 3.0) / METRES_PER_DEGREE_LAT
            lon += (METRES_PER_SECOND * sin(heading) + random.nextGaussian() * 3.0) / metresPerDegreeLon(lat)
            elevation += random.nextGaussian() * 0.3
            timeMs += 1000L
            yield(
                TrackPoint(
                    timeMs = timeMs,
                    lat = lat,
                    lon = lon,
                    ele = elevation,
                    speedMps = METRES_PER_SECOND.toFloat(),
                    accuracyM = 5f,
                    segment = 0,
                ),
            )
        }
    }

    private fun metresPerDegreeLon(lat: Double): Double =
        METRES_PER_DEGREE_LAT * cos(Math.toRadians(lat))

    @Test
    fun simplifyingAThousandKilometreTrackStaysSubQuadratic() {
        val points: List<LatLon> = ridePoints(POINTS).map { it.latLon }.toList()
        assertEquals(POINTS.toLong(), points.size.toLong())

        val startedAt = System.nanoTime()
        val simplified = Simplify.rdp(points, HISTORY_TOLERANCE_M)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            "Simplifying $POINTS points took ${elapsedMs} ms, which suggests the algorithm is no " +
                "longer O(n log n)",
            elapsedMs < TIME_LIMIT_MS,
        )
        // The whole point of simplifying is that the map draws far fewer points than were recorded.
        assertTrue("Simplification kept ${simplified.size} of $POINTS points", simplified.size < POINTS / 2)
        assertTrue("Simplification discarded almost everything", simplified.size > 1000)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun rebuildingStatisticsStreamsAThousandKilometreTrack() {
        val startedAt = System.nanoTime()
        // A sequence, not a list: recovering an unfinished ride must never hold every point at once.
        val stats = RideStats.rebuild(ridePoints(POINTS), startedAtMs = 1_757_000_000_000L, movingMs = 50L * 3600 * 1000)
        val snapshot = stats.snapshot(1_757_000_000_000L + 50L * 3600 * 1000)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("Rebuilding statistics took ${elapsedMs} ms", elapsedMs < TIME_LIMIT_MS)
        assertEquals(POINTS.toLong(), snapshot.pointCount.toLong())
        // Noise and a wandering heading make the exact figure meaningless, but it must land in the
        // right order of magnitude rather than at zero or at something absurd.
        val km = snapshot.distanceM / 1000.0
        assertTrue("A fifty hour ride measured $km km", km > 500.0 && km < 2000.0)
    }

    @Test
    fun exportingGpxStreamsAThousandKilometreTrack() {
        val sink = TrkptCountingStream()
        val startedAt = System.nanoTime()
        GpxWriter.write(sink, "Thousand kilometre ride", ridePoints(POINTS))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("Writing GPX took ${elapsedMs} ms", elapsedMs < TIME_LIMIT_MS)
        assertEquals("Every recorded point must reach the file", POINTS.toLong(), sink.trkptCount.toLong())
        assertTrue("A $POINTS point GPX file was only ${sink.byteCount} bytes", sink.byteCount > 5_000_000)
    }

    /** Counts bytes and `<trkpt` openings without ever holding the file in memory. */
    private class TrkptCountingStream : OutputStream() {
        var byteCount: Long = 0
            private set
        var trkptCount: Int = 0
            private set

        private val needle = "<trkpt".toByteArray(Charsets.US_ASCII)
        private var matched = 0

        override fun write(b: Int) {
            byteCount++
            val c = b.toByte()
            // "<trkpt" has no repeated prefix, so a mismatch can only restart at its first byte.
            matched = when {
                c == needle[matched] -> matched + 1
                c == needle[0] -> 1
                else -> 0
            }
            if (matched == needle.size) {
                trkptCount++
                matched = 0
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(b[i].toInt())
        }
    }

    private companion object {
        /** 1000 km at 20 km/h, one stored fix per second. */
        const val POINTS = 180_000
        const val METRES_PER_SECOND = 5.556
        const val METRES_PER_DEGREE_LAT = 110_540.0

        /** MapController.HISTORY_TOLERANCE_M. */
        const val HISTORY_TOLERANCE_M = 2.0

        /** Wide enough to be immune to a slow shared runner, tight enough to catch O(n^2). */
        const val TIME_LIMIT_MS = 20_000L
    }
}
