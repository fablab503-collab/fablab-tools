package com.fablab503.velotrack.recording

import com.fablab503.velotrack.geo.ElevationAccumulator
import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.model.RideStatsSnapshot
import com.fablab503.velotrack.model.TrackPoint

/**
 * Incremental ride statistics computed from stored points. Pure Kotlin, unit tested on the JVM.
 *
 * Distance is the haversine sum between consecutive stored points; the increment is skipped when
 * the segment index changes (a gap is not ridden distance). Max speed comes from the stored
 * points' speeds. Elevation gain and loss go through [ElevationAccumulator]. Moving time is added
 * by the caller via [addMovingTime].
 */
class RideStats(private val elevation: ElevationAccumulator = ElevationAccumulator()) {

    private var startedAtMs: Long = 0L
    private var started: Boolean = false
    private var distanceM: Double = 0.0
    private var movingMs: Long = 0L
    private var maxSpeedMps: Double = 0.0
    private var pointCount: Int = 0
    private var last: TrackPoint? = null

    /** Resets everything and records the ride start time. */
    fun start(startedAtMs: Long) {
        this.startedAtMs = startedAtMs
        started = true
        distanceM = 0.0
        movingMs = 0L
        maxSpeedMps = 0.0
        pointCount = 0
        last = null
        elevation.reset()
    }

    /** Adds a stored point: distance to the previous point (same segment only), max speed, elevation. */
    fun addStored(point: TrackPoint, verticalAccuracyM: Float?) {
        val prev = last
        if (prev != null && prev.segment == point.segment) {
            distanceM += Geo.haversineM(prev.lat, prev.lon, point.lat, point.lon)
        }
        val speed = point.speedMps
        if (speed != null && !speed.isNaN() && speed > maxSpeedMps) {
            maxSpeedMps = speed.toDouble()
        }
        val ele = point.ele
        if (ele != null) elevation.add(ele, verticalAccuracyM)
        pointCount++
        last = point
    }

    fun addMovingTime(deltaMs: Long) {
        if (deltaMs > 0L) movingMs += deltaMs
    }

    fun snapshot(nowMs: Long): RideStatsSnapshot {
        val elapsed = if (started) (nowMs - startedAtMs).coerceAtLeast(0L) else 0L
        val avg = if (movingMs > 0L) distanceM / (movingMs / 1000.0) else 0.0
        return RideStatsSnapshot(
            distanceM = distanceM,
            movingMs = movingMs,
            elapsedMs = elapsed,
            avgMovingSpeedMps = avg,
            maxSpeedMps = maxSpeedMps,
            elevationGainM = elevation.gainM,
            elevationLossM = elevation.lossM,
            pointCount = pointCount,
        )
    }

    companion object {
        /** Replays stored points (in seq order) to rebuild the statistics of an unfinished track. */
        fun rebuild(points: Sequence<TrackPoint>, startedAtMs: Long, movingMs: Long): RideStats {
            val stats = RideStats()
            stats.start(startedAtMs)
            for (p in points) stats.addStored(p, null)
            stats.addMovingTime(movingMs)
            return stats
        }
    }
}
