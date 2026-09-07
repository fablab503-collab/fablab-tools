package com.fablab503.velotrack.ui

import com.fablab503.velotrack.model.Units
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Human-readable formatting for the HUD, lists and notifications. No Android dependencies. */
object Format {
    private const val MPS_TO_KMH = 3.6
    private const val MPS_TO_MPH = 2.2369362920544
    private const val M_TO_FT = 3.280839895
    private const val M_PER_MILE = 1609.344

    /** Speed with unit, e.g. "24.3 km/h". */
    fun speed(mps: Double, units: Units): String = speedValue(mps, units) + " " + speedUnit(units)

    /** Speed number only (one decimal), for the large HUD figure. */
    fun speedValue(mps: Double, units: Units): String {
        val factor = if (units == Units.METRIC) MPS_TO_KMH else MPS_TO_MPH
        val v = (safe(mps) * factor).coerceAtLeast(0.0)
        return String.format(Locale.getDefault(), "%.1f", v)
    }

    fun speedUnit(units: Units): String = if (units == Units.METRIC) "km/h" else "mph"

    fun distance(m: Double, units: Units): String {
        val d = safe(m).coerceAtLeast(0.0)
        return when (units) {
            Units.METRIC -> when {
                d < 1000.0 -> "${d.roundToInt()} m"
                d < 100_000.0 -> String.format(Locale.getDefault(), "%.2f km", d / 1000.0)
                else -> String.format(Locale.getDefault(), "%.1f km", d / 1000.0)
            }
            Units.IMPERIAL -> {
                val mi = d / M_PER_MILE
                when {
                    mi < 0.1 -> "${(d * M_TO_FT).roundToInt()} ft"
                    mi < 100.0 -> String.format(Locale.getDefault(), "%.2f mi", mi)
                    else -> String.format(Locale.getDefault(), "%.1f mi", mi)
                }
            }
        }
    }

    /** h:mm:ss, e.g. "1:05:09". */
    fun duration(ms: Long): String {
        val totalS = ms.coerceAtLeast(0L) / 1000L
        val h = totalS / 3600L
        val m = (totalS % 3600L) / 60L
        val s = totalS % 60L
        return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    }

    fun elevation(m: Double, units: Units): String {
        val v = safe(m)
        return if (units == Units.METRIC) "${v.roundToInt()} m" else "${(v * M_TO_FT).roundToInt()} ft"
    }

    /** Local date and time, e.g. "2026-09-07 10:15". */
    fun dateTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    private fun safe(v: Double): Double = if (v.isNaN() || v.isInfinite()) 0.0 else v
}
