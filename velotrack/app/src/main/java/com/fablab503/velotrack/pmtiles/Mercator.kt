package com.fablab503.velotrack.pmtiles

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Web Mercator tile arithmetic (EPSG:3857 / XYZ scheme, y grows southwards).
 *
 * Pure Kotlin (no android.*).
 */
object Mercator {

    /** Latitude limit of the Web Mercator square. */
    const val MAX_LAT = 85.05112878

    /** Mean Earth radius in kilometres (IUGG). */
    const val EARTH_RADIUS_KM = 6371.0088

    /** Geographic bounding box in degrees. West may be < -180 or east > 180 when the box crosses the antimeridian. */
    data class BBox(val west: Double, val south: Double, val east: Double, val north: Double)

    /** Tile column of [lon] at zoom [z], clamped to 0..2^z-1 (lon 180 maps to the last column). */
    fun lonToTileX(lon: Double, z: Int): Int {
        require(z in 0..30) { "zoom out of range: $z" }
        val n = 1L shl z
        val x = floor((lon + 180.0) / 360.0 * n.toDouble()).toLong()
        return clamp(x, n).toInt()
    }

    /** Tile row of [lat] at zoom [z]; latitude is clamped to +-[MAX_LAT], the row to 0..2^z-1. */
    fun latToTileY(lat: Double, z: Int): Int {
        require(z in 0..30) { "zoom out of range: $z" }
        val n = 1L shl z
        val clampedLat = max(-MAX_LAT, min(MAX_LAT, lat))
        val rad = Math.toRadians(clampedLat)
        val y = floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n.toDouble()).toLong()
        return clamp(y, n).toInt()
    }

    /**
     * Bounding box of a circle of [radiusKm] around (lat, lon). Latitudes are clamped to +-[MAX_LAT];
     * longitudes are NOT wrapped, so west < -180 or east > 180 signals an antimeridian crossing.
     * The longitude half-width is computed at the latitude of the box edge nearest the pole so the whole circle fits.
     * A circle reaching a pole (or wider than the globe) yields west = -180, east = 180.
     */
    fun circleBBox(lat: Double, lon: Double, radiusKm: Double): BBox {
        require(radiusKm >= 0.0) { "negative radius: $radiusKm" }
        val lat0 = max(-90.0, min(90.0, lat))
        val lon0 = normalizeLon(lon)
        val dLat = Math.toDegrees(radiusKm / EARTH_RADIUS_KM)
        val south = max(-MAX_LAT, lat0 - dLat)
        val north = min(MAX_LAT, lat0 + dLat)
        val polewardLat = abs(lat0) + dLat
        if (polewardLat >= 90.0) {
            return BBox(-180.0, south, 180.0, north)
        }
        val dLon = Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * cos(Math.toRadians(polewardLat))))
        if (dLon >= 180.0) {
            return BBox(-180.0, south, 180.0, north)
        }
        return BBox(lon0 - dLon, south, lon0 + dLon, north)
    }

    /**
     * Tile ranges covering [b] at zoom [z]: one x range, or two when the box crosses the antimeridian
     * (the eastern part first, `[x(west) .. 2^z-1]`, then `[0 .. x(east)]`), plus the y range (north to south).
     * A box spanning 360 degrees or more yields the single full x range.
     */
    fun tileRanges(b: BBox, z: Int): Pair<List<IntRange>, IntRange> {
        require(z in 0..30) { "zoom out of range: $z" }
        require(b.west <= b.east && b.south <= b.north) { "inverted bbox: $b" }
        val last = ((1L shl z) - 1L).toInt()
        val yRange = latToTileY(b.north, z)..latToTileY(b.south, z)
        val full = listOf(0..last)
        if (b.east - b.west >= 360.0) return Pair(full, yRange)

        val xRanges: List<IntRange> = when {
            b.west < -180.0 -> wrapped(lonToTileX(b.west + 360.0, z), lonToTileX(b.east, z), last)
            b.east > 180.0 -> wrapped(lonToTileX(b.west, z), lonToTileX(b.east - 360.0, z), last)
            else -> listOf(lonToTileX(b.west, z)..lonToTileX(b.east, z))
        }
        return Pair(xRanges, yRange)
    }

    /** Two ranges `[xw..last]` and `[0..xe]`, collapsed to the full range when they meet or overlap. */
    private fun wrapped(xw: Int, xe: Int, last: Int): List<IntRange> {
        return if (xw <= xe + 1) listOf(0..last) else listOf(xw..last, 0..xe)
    }

    /** Wraps a longitude into [-180, 180]. */
    fun normalizeLon(lon: Double): Double {
        if (lon >= -180.0 && lon <= 180.0) return lon
        var l = (lon + 180.0) % 360.0
        if (l < 0.0) l += 360.0
        return l - 180.0
    }

    private fun clamp(v: Long, n: Long): Long = max(0L, min(n - 1L, v))
}
