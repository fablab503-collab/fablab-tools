package com.fablab503.velotrack.model

/**
 * Aggregate of finished rides over a period (see `storage.StatsRepository.totals`).
 * All sums are zero and [rides] is 0 when the period holds no finished track.
 */
data class RideTotals(
    val rides: Int,
    val distanceM: Double,
    val movingMs: Long,
    val elevationGainM: Double,
    val longestDistanceM: Double,
    val maxSpeedMps: Double,
) {
    companion object {
        val EMPTY = RideTotals(0, 0.0, 0L, 0.0, 0.0, 0.0)
    }
}
