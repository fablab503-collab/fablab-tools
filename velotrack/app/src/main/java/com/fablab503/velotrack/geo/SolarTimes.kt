package com.fablab503.velotrack.geo

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sunrise and sunset from a NOAA/Meeus-style approximation: mean solar anomaly, equation of
 * centre, ecliptic longitude, declination and the hour angle for the official zenith of
 * 90.833 degrees (refraction plus the solar radius). Accurate to a minute or two, which is all
 * the automatic theme needs. Self-contained, no Android imports: unit tested on the JVM.
 */
object SolarTimes {
    /** Local hour at which the fallback day starts when no position is known. */
    const val FALLBACK_DAY_START_HOUR = 7

    /** Local hour at which the fallback day ends (night begins) when no position is known. */
    const val FALLBACK_DAY_END_HOUR = 19

    /** Julian day of 2000-01-01 12:00 UTC (J2000 epoch). */
    private const val J2000 = 2451545.0

    /** Julian day of the Unix epoch 1970-01-01 00:00 UTC. */
    private const val JD_UNIX_EPOCH = 2440587.5

    private const val MS_PER_DAY = 86_400_000.0
    private const val DEG_TO_RAD = PI / 180.0

    /** Obliquity of the ecliptic used by the approximation. */
    private const val OBLIQUITY_DEG = 23.4397

    /** Sun centre altitude at official sunrise/sunset: zenith 90.833 degrees. */
    private const val SUN_ALTITUDE_DEG = -0.833

    /**
     * One civil day's result; [cosHourAngle] outside [-1, 1] means the sun never rises or never
     * sets (NaN, from a NaN coordinate, is treated as never rising so callers stay finite).
     */
    private class Day(val sunriseMs: Long, val sunsetMs: Long, val cosHourAngle: Double) {
        val sunNeverSets: Boolean get() = cosHourAngle < -1.0
        val sunNeverRises: Boolean get() = cosHourAngle > 1.0 || cosHourAngle.isNaN()
        val hasEvents: Boolean get() = !sunNeverSets && !sunNeverRises
    }

    /**
     * Sunrise and sunset as epoch millis for the civil day containing [dayEpochMs] in [zone];
     * null above the polar circle when the sun does not rise/set that day.
     */
    fun sunriseSunset(lat: Double, lon: Double, dayEpochMs: Long, zone: ZoneId): Pair<Long, Long>? {
        val day = compute(lat, lon, localDate(dayEpochMs, zone), zone)
        return if (day.hasEvents) Pair(day.sunriseMs, day.sunsetMs) else null
    }

    /**
     * True when [nowMs] is before sunrise or after sunset at (lat, lon). Without a position (lat
     * or lon null) the day runs from [FALLBACK_DAY_START_HOUR] to [FALLBACK_DAY_END_HOUR] local
     * time. Polar day counts as day, polar night as night.
     */
    fun isNight(lat: Double?, lon: Double?, nowMs: Long, zone: ZoneId): Boolean {
        if (lat == null || lon == null) {
            val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).hour
            return hour < FALLBACK_DAY_START_HOUR || hour >= FALLBACK_DAY_END_HOUR
        }
        val day = compute(lat, lon, localDate(nowMs, zone), zone)
        return when {
            day.sunNeverSets -> false
            day.sunNeverRises -> true
            else -> nowMs < day.sunriseMs || nowMs >= day.sunsetMs
        }
    }

    /**
     * Next epoch millis at which [isNight] flips (sunrise or sunset, or the fallback hours), used
     * to schedule a re-check. Always greater than [nowMs]; during polar day/night, when nothing
     * flips within the next days, returns [nowMs] + 24 h so the caller re-checks tomorrow.
     */
    fun nextTransitionMs(lat: Double?, lon: Double?, nowMs: Long, zone: ZoneId): Long {
        val today = localDate(nowMs, zone)
        if (lat == null || lon == null) {
            for (offset in 0L..1L) {
                val date = today.plusDays(offset)
                val dayStart = atHour(date, FALLBACK_DAY_START_HOUR, zone)
                if (dayStart > nowMs) return dayStart
                val dayEnd = atHour(date, FALLBACK_DAY_END_HOUR, zone)
                if (dayEnd > nowMs) return dayEnd
            }
            return nowMs + MS_PER_DAY.toLong()
        }
        for (offset in 0L..2L) {
            val day = compute(lat, lon, today.plusDays(offset), zone)
            if (!day.hasEvents) continue
            if (day.sunriseMs > nowMs) return day.sunriseMs
            if (day.sunsetMs > nowMs) return day.sunsetMs
        }
        return nowMs + MS_PER_DAY.toLong()
    }

    /**
     * Sunrise equation for the solar transit closest to the civil noon of [date] in [zone].
     * Longitude east is positive, so mean solar noon at [lon] is `lon / 360` days before UTC noon.
     */
    private fun compute(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): Day {
        val civilNoonJd = toJulianDay(ZonedDateTime.of(date, LocalTime.NOON, zone).toInstant().toEpochMilli())
        // Whole days since J2000 of the mean solar noon nearest to the civil noon.
        val n = round(civilNoonJd - J2000 + lon / 360.0)
        val meanSolarNoon = n - lon / 360.0
        val meanAnomalyDeg = normalizeDeg(357.5291 + 0.98560028 * meanSolarNoon)
        val m = meanAnomalyDeg * DEG_TO_RAD
        val centreDeg = 1.9148 * sin(m) + 0.0200 * sin(2.0 * m) + 0.0003 * sin(3.0 * m)
        val eclipticLonDeg = normalizeDeg(meanAnomalyDeg + centreDeg + 180.0 + 102.9372)
        val l = eclipticLonDeg * DEG_TO_RAD
        val transitJd = J2000 + meanSolarNoon + 0.0053 * sin(m) - 0.0069 * sin(2.0 * l)
        val sinDeclination = sin(l) * sin(OBLIQUITY_DEG * DEG_TO_RAD)
        val declination = asin(sinDeclination)
        val phi = lat * DEG_TO_RAD
        val cosHourAngle = (sin(SUN_ALTITUDE_DEG * DEG_TO_RAD) - sin(phi) * sinDeclination) / (cos(phi) * cos(declination))
        // Hour angle as a fraction of a day (radians / 2 pi); clamped so polar days still yield finite times.
        val clampedCos = if (cosHourAngle.isNaN()) 1.0 else cosHourAngle.coerceIn(-1.0, 1.0)
        val hourAngleDays = acos(clampedCos) / (2.0 * PI)
        return Day(
            sunriseMs = toEpochMs(transitJd - hourAngleDays),
            sunsetMs = toEpochMs(transitJd + hourAngleDays),
            cosHourAngle = cosHourAngle,
        )
    }

    private fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** Epoch millis of [hour]:00 local on [date]; a DST gap at that hour moves the time forward. */
    private fun atHour(date: LocalDate, hour: Int, zone: ZoneId): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, 0), zone).toInstant().toEpochMilli()

    private fun toJulianDay(epochMs: Long): Double = epochMs / MS_PER_DAY + JD_UNIX_EPOCH

    private fun toEpochMs(julianDay: Double): Long = ((julianDay - JD_UNIX_EPOCH) * MS_PER_DAY).roundToLong()

    private fun normalizeDeg(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0.0) d + 360.0 else d
    }
}
