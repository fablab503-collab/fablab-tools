package com.fablab503.velotrack.recording

import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.model.GpsFix
import com.fablab503.velotrack.model.TrackPoint

/**
 * Tunable thresholds for [PointFilter]. Defaults follow OpenTracks / OsmAnd / Organic Maps.
 */
data class FilterConfig(
    val maxAccuracyM: Float = 50f,
    val maxSpeedMps: Float = 30f,
    val minStoreDistanceM: Float = 5f,
    val segmentGapM: Float = 200f,
    val segmentGapMs: Long = 6 * 60_000,
    val pauseSpeedMps: Float = 1.0f,
    val resumeSpeedMps: Float = 1.5f,
    val pauseAfterMs: Long = 10_000,
    val autoPauseEnabled: Boolean = true,
)

/**
 * Outcome of offering one fix to [PointFilter].
 *
 * @property accepted the fix passed the accuracy and jump checks (it may still not be stored)
 * @property store the fix should be persisted; [point] is non-null exactly when this is true
 * @property newSegment the stored point starts a new track segment (a gap preceded it)
 * @property autoPaused the filter's auto-pause state after this fix
 * @property speedMps speed of this fix (reported by the receiver or derived from the previous fix)
 * @property point the point to persist, or null when nothing is stored
 */
data class FilterDecision(
    val accepted: Boolean,
    val store: Boolean,
    val newSegment: Boolean,
    val autoPaused: Boolean,
    val speedMps: Float,
    val point: TrackPoint?,
)

/**
 * Pure fix filter: accuracy cut-off, jump rejection, stationary-drift suppression, distance
 * thinning, auto-pause and segment splitting. No Android imports; unit tested on the JVM.
 *
 * Rules:
 * - reject when `accuracyM == null || accuracyM > maxAccuracyM`;
 * - reject jumps where the distance to the previous accepted fix exceeds
 *   `maxSpeedMps * dt + acc_prev + acc_new`;
 * - speed is `fix.speedMps` or derived from the previous accepted fix;
 * - auto-pause when speed < pauseSpeed for at least pauseAfterMs, resume at >= resumeSpeed;
 * - nothing is stored while auto-paused or manually paused;
 * - stationary drift (moved < 2 * accuracy while slower than pauseSpeed) is not stored;
 * - store when moved >= minStoreDistance from the last stored point; the first accepted fix is
 *   always stored (unless paused);
 * - a new segment starts when the gap from the last stored point exceeds segmentGapM or
 *   segmentGapMs.
 */
class PointFilter(private val config: FilterConfig) {

    /** Current segment index; incremented whenever a stored point starts a new segment. */
    var segment: Int = 0

    constructor(config: FilterConfig, startSegment: Int) : this(config) {
        segment = startSegment
    }

    private var lastAccepted: GpsFix? = null
    private var lastStored: GpsFix? = null
    private var slowSinceNs: Long = -1L
    private var autoPaused: Boolean = false
    private var forcePaused: Boolean = false

    fun offer(fix: GpsFix): FilterDecision {
        val accuracy = fix.accuracyM
        if (accuracy == null || accuracy.isNaN() || accuracy > config.maxAccuracyM) {
            return rejected(fix)
        }

        val prev = lastAccepted
        var distFromPrevM = 0.0
        var dtS = 0.0
        if (prev != null) {
            dtS = (fix.elapsedNs - prev.elapsedNs) / 1e9
            if (dtS <= 0.0) return rejected(fix) // duplicate or out-of-order fix
            distFromPrevM = Geo.haversineM(prev.lat, prev.lon, fix.lat, fix.lon)
            val prevAccuracy = prev.accuracyM ?: 0f
            val maxPlausibleM = config.maxSpeedMps.toDouble() * dtS + prevAccuracy + accuracy
            if (distFromPrevM > maxPlausibleM) return rejected(fix)
        }

        // Speed: trust the receiver when it reports real movement. Some receivers (and the Android
        // emulator) report exactly 0 while the position clearly moves, so fall back to the speed
        // derived from the previous fix whenever the displacement exceeds the accuracy radius.
        val reported = fix.speedMps?.takeIf { !it.isNaN() && it >= 0f }
        val derived: Float? = if (prev != null && dtS > 0.0) (distFromPrevM / dtS).toFloat() else null
        val movedBeyondNoise = distFromPrevM > maxOf(accuracy, 2f).toDouble()
        val speed: Float = when {
            reported != null && reported > MIN_TRUSTED_REPORTED_SPEED_MPS -> reported
            derived != null && movedBeyondNoise -> derived
            reported != null -> reported
            derived != null -> derived
            else -> 0f
        }

        lastAccepted = fix
        updateAutoPause(fix, speed)

        val paused = forcePaused || autoPaused
        val stored = lastStored
        var store = false
        var newSegment = false
        if (!paused) {
            if (stored == null) {
                store = true
            } else {
                val movedM = Geo.haversineM(stored.lat, stored.lon, fix.lat, fix.lon)
                val farEnough = movedM >= config.minStoreDistanceM
                val stationaryDrift = movedM < 2.0 * accuracy && speed < config.pauseSpeedMps
                if (farEnough && !stationaryDrift) {
                    store = true
                    val gapMs = (fix.elapsedNs - stored.elapsedNs) / 1_000_000L
                    if (movedM > config.segmentGapM || gapMs > config.segmentGapMs) {
                        newSegment = true
                        segment++
                    }
                }
            }
        }

        var point: TrackPoint? = null
        if (store) {
            point = TrackPoint(
                timeMs = fix.timeMs,
                lat = fix.lat,
                lon = fix.lon,
                ele = fix.ele,
                speedMps = speed,
                accuracyM = accuracy,
                segment = segment,
            )
            lastStored = fix
        }

        return FilterDecision(
            accepted = true,
            store = store,
            newSegment = newSegment,
            autoPaused = autoPaused,
            speedMps = speed,
            point = point,
        )
    }

    /** Manual pause: while true nothing is stored, but fixes are still accepted for continuity. */
    fun forcePause(paused: Boolean) {
        forcePaused = paused
    }

    /** Clears all fix history and pause state. The segment index is kept. */
    fun reset() {
        lastAccepted = null
        lastStored = null
        slowSinceNs = -1L
        autoPaused = false
        forcePaused = false
    }

    private fun updateAutoPause(fix: GpsFix, speed: Float) {
        if (!config.autoPauseEnabled) {
            autoPaused = false
            slowSinceNs = -1L
            return
        }
        if (speed < config.pauseSpeedMps) {
            if (slowSinceNs < 0L) {
                slowSinceNs = fix.elapsedNs
            } else if (!autoPaused) {
                val slowForMs = (fix.elapsedNs - slowSinceNs) / 1_000_000L
                if (slowForMs >= config.pauseAfterMs) autoPaused = true
            }
        } else {
            slowSinceNs = -1L
            if (autoPaused && speed >= config.resumeSpeedMps) autoPaused = false
        }
    }

    private companion object {
        /** Reported speeds at or below this are treated as "unknown" and cross-checked with displacement. */
        const val MIN_TRUSTED_REPORTED_SPEED_MPS = 0.3f
    }

    private fun rejected(fix: GpsFix): FilterDecision {
        val reported = fix.speedMps
        val speed = if (reported != null && !reported.isNaN() && reported >= 0f) reported else 0f
        return FilterDecision(
            accepted = false,
            store = false,
            newSegment = false,
            autoPaused = autoPaused,
            speedMps = speed,
            point = null,
        )
    }
}
