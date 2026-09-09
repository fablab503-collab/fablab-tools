package com.fablab503.velotrack.wear

import com.fablab503.velotrack.sync.WearSync
import com.google.android.gms.wearable.DataMap

/**
 * Reads the phone's ride payload.
 *
 * Every field has a default, so a payload written by an older or newer phone still renders instead
 * of throwing: an added key is ignored, a missing one falls back. The Data Layer gives no version
 * negotiation, and a watch that crashes on an unexpected payload would be much worse than one
 * showing a zero.
 */
fun DataMap.toRide(): WearSync.Ride = WearSync.Ride(
    status = getString(WearSync.KEY_STATUS) ?: WearSync.STATUS_IDLE,
    speedMps = getFloat(WearSync.KEY_SPEED_MPS, 0f),
    distanceM = getDouble(WearSync.KEY_DISTANCE_M, 0.0),
    elapsedMs = getLong(WearSync.KEY_ELAPSED_MS, 0L),
    movingMs = getLong(WearSync.KEY_MOVING_MS, 0L),
    hasFix = getBoolean(WearSync.KEY_HAS_FIX, false),
    satellites = getInt(WearSync.KEY_SATELLITES, 0),
    imperial = getBoolean(WearSync.KEY_IMPERIAL, false),
)
