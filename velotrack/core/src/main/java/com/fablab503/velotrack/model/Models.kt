package com.fablab503.velotrack.model

/** A geographic coordinate in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * A GPS fix reduced to plain values so that pure (non-Android) code can be unit tested.
 * Nullable fields are absent when the receiver did not report them.
 */
data class GpsFix(
    val timeMs: Long,
    val elapsedNs: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val verticalAccuracyM: Float?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val bearingAccuracyDeg: Float?,
    val accuracyM: Float?,
) {
    val latLon: LatLon get() = LatLon(lat, lon)
}

/** A stored track point. `segment` increments after a gap so exports can split track segments. */
data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val speedMps: Float?,
    val accuracyM: Float,
    val segment: Int,
) {
    val latLon: LatLon get() = LatLon(lat, lon)
}

data class GpsStatus(
    val satellitesUsed: Int = 0,
    val satellitesTotal: Int = 0,
    val hasFix: Boolean = false,
    val providerEnabled: Boolean = true,
)

enum class RecordingStatus { IDLE, RECORDING, AUTO_PAUSED, PAUSED }

data class RideStatsSnapshot(
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val elapsedMs: Long = 0,
    val avgMovingSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val pointCount: Int = 0,
)

/** Everything the UI needs, published by the recording service (or the idle GPS feed). */
data class RideState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val trackId: Long? = null,
    val stats: RideStatsSnapshot = RideStatsSnapshot(),
    val lastFix: GpsFix? = null,
    val headingDeg: Float? = null,
    val gps: GpsStatus = GpsStatus(),
    val error: String? = null,
    /** Effective speed after filtering (receiver speed, or derived from displacement when the receiver reports 0). */
    val speedMps: Float? = null,
)

data class TrackSummary(
    val id: Long,
    val name: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val distanceM: Double,
    val movingMs: Long,
    val elevationGainM: Double,
    val state: String,
    val pointCount: Int,
) {
    companion object {
        const val STATE_RECORDING = "recording"
        const val STATE_FINISHED = "finished"
    }
}

data class RouteSummary(
    val id: Long,
    val name: String,
    val distanceM: Double,
    val pointCount: Int,
    val file: String,
)

data class RouteProgress(
    val distanceToRouteM: Double,
    val distanceRemainingM: Double,
    val offRoute: Boolean,
    val nearestSegment: Int,
)

enum class CameraMode { FOLLOW_3D, FOLLOW_2D, FREE }

enum class ScreenMode { KEEP_ON, DIM, SYSTEM }

enum class Units { METRIC, IMPERIAL }
