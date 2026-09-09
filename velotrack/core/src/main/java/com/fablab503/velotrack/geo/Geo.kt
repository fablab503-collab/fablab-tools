package com.fablab503.velotrack.geo

import com.fablab503.velotrack.model.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geographic helpers. No Android imports: this file is unit tested on the JVM. */
object Geo {
    const val EARTH_RADIUS_M = 6371008.8

    /** Metres per degree of latitude used by the local equirectangular projection. */
    private const val M_PER_DEG_LAT = 110540.0

    /** Metres per degree of longitude at the equator used by the local equirectangular projection. */
    private const val M_PER_DEG_LON_EQUATOR = 111320.0

    private const val DEG_TO_RAD = PI / 180.0

    /** Great-circle distance in metres between two coordinates given in degrees. */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * DEG_TO_RAD
        val phi2 = lat2 * DEG_TO_RAD
        val dPhi = (lat2 - lat1) * DEG_TO_RAD
        val dLambda = (lon2 - lon1) * DEG_TO_RAD
        val sinDPhi = sin(dPhi / 2.0)
        val sinDLambda = sin(dLambda / 2.0)
        val h = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
        // Clamp guards against rounding pushing h slightly above 1 for antipodal points.
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(min(1.0, h)))
    }

    fun distanceM(a: LatLon, b: LatLon): Double = haversineM(a.lat, a.lon, b.lat, b.lon)

    /** Initial bearing from a to b, degrees clockwise from north in [0, 360). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val phi1 = a.lat * DEG_TO_RAD
        val phi2 = b.lat * DEG_TO_RAD
        val dLambda = (b.lon - a.lon) * DEG_TO_RAD
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return normalizeDeg(atan2(y, x) / DEG_TO_RAD)
    }

    /** Normalises an angle to [0, 360). */
    fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0.0) d += 360.0
        // `% 360.0` of a tiny negative value can round back to exactly 360.0.
        if (d >= 360.0) d -= 360.0
        return d
    }

    /** Signed shortest difference target - current in (-180, 180]. */
    fun angleDiffDeg(current: Double, target: Double): Double {
        var d = (target - current) % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    /** Perpendicular distance from p to segment ab in metres (local equirectangular projection). */
    fun pointToSegmentM(p: LatLon, a: LatLon, b: LatLon): Double {
        val cosLat = cos(a.lat * DEG_TO_RAD)
        val px = (p.lon - a.lon) * cosLat * M_PER_DEG_LON_EQUATOR
        val py = (p.lat - a.lat) * M_PER_DEG_LAT
        val bx = (b.lon - a.lon) * cosLat * M_PER_DEG_LON_EQUATOR
        val by = (b.lat - a.lat) * M_PER_DEG_LAT
        val t = segmentFraction(px, py, bx, by)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /** Fraction t in [0,1] of the projection of p onto ab, and the projected point. */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, LatLon> {
        val cosLat = cos(a.lat * DEG_TO_RAD)
        val px = (p.lon - a.lon) * cosLat * M_PER_DEG_LON_EQUATOR
        val py = (p.lat - a.lat) * M_PER_DEG_LAT
        val bx = (b.lon - a.lon) * cosLat * M_PER_DEG_LON_EQUATOR
        val by = (b.lat - a.lat) * M_PER_DEG_LAT
        val t = segmentFraction(px, py, bx, by)
        val projected = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
        return Pair(t, projected)
    }

    /** 156543.03392 * cos(lat) / 2^zoom. */
    fun metersPerPixel(zoom: Double, latDeg: Double): Double =
        156543.03392 * cos(latDeg * DEG_TO_RAD) / 2.0.pow(zoom)

    /**
     * Fraction along the segment (0,0)->(bx,by) of the projection of (px,py), clamped to [0,1].
     * A degenerate (zero-length) segment yields 0.
     */
    private fun segmentFraction(px: Double, py: Double, bx: Double, by: Double): Double {
        val len2 = bx * bx + by * by
        if (len2 <= 0.0 || abs(len2) < 1e-12) return 0.0
        val t = (px * bx + py * by) / len2
        return when {
            t < 0.0 -> 0.0
            t > 1.0 -> 1.0
            else -> t
        }
    }
}
