package com.fablab503.velotrack.location

import android.location.Location
import android.os.Build
import com.fablab503.velotrack.model.GpsFix

/**
 * Converts a platform [Location] to the plain [GpsFix] used by the pure modules.
 * Elevation prefers the mean-sea-level altitude on API 34+ when the receiver reports it, else the
 * WGS84 ellipsoid altitude. Accuracies and derived values are only taken when `has…()` is true.
 */
fun Location.toGpsFix(): GpsFix {
    val ele: Double? = if (Build.VERSION.SDK_INT >= 34 && hasMslAltitude()) {
        mslAltitudeMeters
    } else if (hasAltitude()) {
        altitude
    } else {
        null
    }
    return GpsFix(
        timeMs = time,
        elapsedNs = elapsedRealtimeNanos,
        lat = latitude,
        lon = longitude,
        ele = ele,
        verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
    )
}
