package com.fablab503.velotrack.storage

import com.fablab503.velotrack.model.RideTotals
import com.fablab503.velotrack.model.TrackSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Read-only aggregates over the `tracks` table for the statistics screen.
 * Synchronous; call from a background dispatcher. Only finished tracks count (a ride still being
 * recorded is excluded until it is stopped).
 */
class StatsRepository(private val db: TrackDatabase) {

    /** Finished tracks (state = 'finished') with started_at >= [sinceMs] (null = all time). */
    fun totals(sinceMs: Long?): RideTotals {
        val sql = if (sinceMs == null) SQL_TOTALS else "$SQL_TOTALS AND started_at >= ?"
        val args = if (sinceMs == null) {
            arrayOf(TrackSummary.STATE_FINISHED)
        } else {
            arrayOf(TrackSummary.STATE_FINISHED, sinceMs.toString())
        }
        return db.readableDatabase.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) {
                RideTotals(
                    rides = c.getInt(0),
                    distanceM = c.getDouble(1),
                    movingMs = c.getLong(2),
                    elevationGainM = c.getDouble(3),
                    longestDistanceM = c.getDouble(4),
                    maxSpeedMps = c.getDouble(5),
                )
            } else {
                RideTotals.EMPTY
            }
        }
    }

    companion object {
        // Column order: 0 rides, 1 distance_m sum, 2 moving_ms sum, 3 elevation_gain_m sum,
        // 4 longest distance_m, 5 max max_speed_mps. The period filter is appended by totals().
        private const val SQL_TOTALS =
            "SELECT COUNT(*), COALESCE(SUM(distance_m),0), COALESCE(SUM(moving_ms),0), " +
                "COALESCE(SUM(elevation_gain_m),0), COALESCE(MAX(distance_m),0), COALESCE(MAX(max_speed_mps),0) " +
                "FROM ${TrackDatabase.TABLE_TRACKS} WHERE state = ?"

        /**
         * Start (local midnight) of the week containing [nowMs] in [zone]; the first day of the week
         * follows [locale] (Monday in France / ISO, Sunday in the US).
         */
        fun startOfWeekMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): Long {
            val firstDay = WeekFields.of(locale).firstDayOfWeek
            return startOfDayMs(localDate(nowMs, zone).with(TemporalAdjusters.previousOrSame(firstDay)), zone)
        }

        /** Start (local midnight) of the month containing [nowMs] in [zone]. */
        fun startOfMonthMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            startOfDayMs(localDate(nowMs, zone).with(TemporalAdjusters.firstDayOfMonth()), zone)

        /** Start (local midnight) of the year containing [nowMs] in [zone]. */
        fun startOfYearMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            startOfDayMs(localDate(nowMs, zone).with(TemporalAdjusters.firstDayOfYear()), zone)

        private fun localDate(nowMs: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

        /** DST-safe: atStartOfDay(zone) returns the first valid instant when midnight falls in a gap. */
        private fun startOfDayMs(date: LocalDate, zone: ZoneId): Long =
            date.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
