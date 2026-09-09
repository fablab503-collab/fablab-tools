package com.fablab503.velotrack.route

import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.model.RouteProgress
import kotlin.math.max
import kotlin.math.min

/**
 * Follows a pre-planned polyline route: projects the rider onto the nearest segment, reports the
 * perpendicular distance to the route, the distance remaining along it and an off-route flag that
 * only trips after the rider has been beyond [offRouteThresholdM] continuously for [offRouteDelayMs].
 *
 * Pure Kotlin: no Android imports so it can be unit tested on the JVM.
 */
class RouteFollower(
    points: List<LatLon>,
    private val offRouteThresholdM: Double = 50.0,
    private val offRouteDelayMs: Long = 10_000,
    private val windowSegments: Int = 50,
) {
    private val pts: List<LatLon> = points.toList()

    /** Number of segments (vertices - 1, or 0 for degenerate routes). */
    private val segmentCount: Int = max(0, pts.size - 1)

    /** Length in metres of segment i (from vertex i to vertex i + 1). */
    private val segmentLengthM: DoubleArray = DoubleArray(segmentCount)

    /** Cumulative route distance in metres from the start to vertex i. Size = pts.size (0 if empty). */
    private val cumulativeM: DoubleArray = DoubleArray(pts.size)

    val totalDistanceM: Double
    val pointCount: Int = pts.size

    private var lastSegment: Int = 0
    private var firstExceededMs: Long? = null

    init {
        for (i in 0 until segmentCount) {
            val len = Geo.distanceM(pts[i], pts[i + 1])
            segmentLengthM[i] = len
            cumulativeM[i + 1] = cumulativeM[i] + len
        }
        totalDistanceM = if (pts.isEmpty()) 0.0 else cumulativeM[pts.size - 1]
    }

    /**
     * Nearest point on the polyline searched around the last match; full rescan if the local best
     * exceeds the threshold.
     */
    fun update(pos: LatLon, nowMs: Long): RouteProgress {
        if (pts.isEmpty()) {
            firstExceededMs = null
            return RouteProgress(
                distanceToRouteM = 0.0,
                distanceRemainingM = 0.0,
                offRoute = false,
                nearestSegment = 0,
            )
        }
        if (segmentCount == 0) {
            val d = Geo.distanceM(pos, pts[0])
            return RouteProgress(
                distanceToRouteM = d,
                distanceRemainingM = 0.0,
                offRoute = evaluateOffRoute(d, nowMs),
                nearestSegment = 0,
            )
        }

        val lo = max(0, lastSegment - windowSegments)
        val hi = min(segmentCount - 1, lastSegment + windowSegments)
        var best = bestSegmentIn(pos, lo, hi)
        val windowCoversAll = lo == 0 && hi == segmentCount - 1
        if (best.distanceM > offRouteThresholdM && !windowCoversAll) {
            best = bestSegmentIn(pos, 0, segmentCount - 1)
        }
        lastSegment = best.index

        val travelledM = cumulativeM[best.index] + best.t * segmentLengthM[best.index]
        val remainingM = max(0.0, totalDistanceM - travelledM)

        return RouteProgress(
            distanceToRouteM = best.distanceM,
            distanceRemainingM = remainingM,
            offRoute = evaluateOffRoute(best.distanceM, nowMs),
            nearestSegment = best.index,
        )
    }

    /** Forgets the last match and any pending off-route timer. */
    fun reset() {
        lastSegment = 0
        firstExceededMs = null
    }

    private class Match(val index: Int, val distanceM: Double, val t: Double)

    /** Scans segments [from, to] (inclusive, already clamped) and returns the closest one. */
    private fun bestSegmentIn(pos: LatLon, from: Int, to: Int): Match {
        var bestIndex = from
        var bestDistance = Double.POSITIVE_INFINITY
        var bestT = 0.0
        for (i in from..to) {
            val a = pts[i]
            val b = pts[i + 1]
            val d: Double
            val t: Double
            if (segmentLengthM[i] <= 0.0) {
                d = Geo.distanceM(pos, a)
                t = 0.0
            } else {
                d = Geo.pointToSegmentM(pos, a, b)
                t = Geo.projectOnSegment(pos, a, b).first.coerceIn(0.0, 1.0)
            }
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
                bestT = t
            }
        }
        return Match(bestIndex, bestDistance, bestT)
    }

    /**
     * Off-route only after the distance has exceeded the threshold continuously for the delay;
     * clears immediately when back within the threshold.
     */
    private fun evaluateOffRoute(distanceM: Double, nowMs: Long): Boolean {
        if (distanceM <= offRouteThresholdM) {
            firstExceededMs = null
            return false
        }
        val since = firstExceededMs ?: nowMs.also { firstExceededMs = it }
        return nowMs - since >= offRouteDelayMs
    }
}
