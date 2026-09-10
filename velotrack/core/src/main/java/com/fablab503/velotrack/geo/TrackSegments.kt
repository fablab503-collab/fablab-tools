package com.fablab503.velotrack.geo

import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.model.TrackPoint

/**
 * Splits stored points into the runs the recorder recorded them as.
 *
 * [PointFilter] raises the segment index whenever a gap opens - more than 200 m or six minutes
 * between one stored point and the next, or a ride continued from the ride list - because a gap is
 * not distance ridden. Anything drawing the ride has to make the same distinction, or the map
 * shows a straight line across a train journey, a lunch, or a night in a hotel and quietly claims
 * the rider went that way.
 *
 * The points are assumed to arrive in `seq` order, which is what every query in `TrackRepository`
 * returns. A segment index that repeats after another one has intervened therefore starts a new
 * run rather than joining the earlier one.
 */
fun segmentsOf(points: List<TrackPoint>): List<List<LatLon>> {
    if (points.isEmpty()) return emptyList()
    val result = ArrayList<List<LatLon>>()
    var current = ArrayList<LatLon>()
    var segment = points[0].segment
    for (p in points) {
        if (p.segment != segment) {
            if (current.isNotEmpty()) result.add(current)
            current = ArrayList()
            segment = p.segment
        }
        current.add(p.latLon)
    }
    if (current.isNotEmpty()) result.add(current)
    return result
}

/**
 * The straight connectors between consecutive runs of a ride: the end of one, the start of the
 * next. These are the stretches the app did not measure - signal lost in an underpass, six minutes
 * standing still, a ride picked up again days later.
 *
 * They are worth drawing rather than leaving blank, but never as ridden distance: a line that stops
 * and reappears somewhere else looks like a broken app, while a dashed connector says what actually
 * happened. Runs whose ends are within [joinM] of each other produce nothing, so an ordinary ride
 * with no gaps yields an empty list.
 */
fun gapsBetween(runs: List<List<LatLon>>, joinM: Double): List<Pair<LatLon, LatLon>> {
    if (runs.size < 2) return emptyList()
    val gaps = ArrayList<Pair<LatLon, LatLon>>()
    for (i in 0 until runs.size - 1) {
        val from = runs[i].lastOrNull() ?: continue
        val to = runs[i + 1].firstOrNull() ?: continue
        if (Geo.haversineM(from.lat, from.lon, to.lat, to.lon) <= joinM) continue
        gaps.add(from to to)
    }
    return gaps
}
