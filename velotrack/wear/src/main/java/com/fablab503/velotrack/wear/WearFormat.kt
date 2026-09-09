package com.fablab503.velotrack.wear

import java.util.Locale
import kotlin.math.roundToLong

/**
 * The watch's own number formatting.
 *
 * Deliberately a separate, much smaller thing than the phone's `ui/Format`: a watch shows four
 * values at a glance and never needs the phone's pace, bearing or elevation formats. Keeping it
 * here also keeps the shared `:sync` module free of anything but the wire contract.
 */
object WearFormat {

    private const val M_PER_KM = 1000.0
    private const val M_PER_MILE = 1609.344
    private const val MPS_TO_KMH = 3.6
    private const val MPS_TO_MPH = 2.236936

    fun speed(mps: Float, imperial: Boolean): String {
        val v = if (imperial) mps * MPS_TO_MPH else mps * MPS_TO_KMH
        val safe = if (v.isNaN() || v < 0f) 0.0 else v.toDouble()
        return String.format(Locale.US, "%.1f", safe)
    }

    fun speedUnit(imperial: Boolean): String = if (imperial) "mph" else "km/h"

    fun distance(meters: Double, imperial: Boolean): String {
        val v = if (imperial) meters / M_PER_MILE else meters / M_PER_KM
        val safe = if (v.isNaN() || v < 0.0) 0.0 else v
        return String.format(Locale.US, "%.2f", safe)
    }

    fun distanceUnit(imperial: Boolean): String = if (imperial) "mi" else "km"

    /** h:mm:ss once past an hour, m:ss below it - the same shape a stopwatch uses. */
    fun duration(millis: Long): String {
        val total = (millis.coerceAtLeast(0L) / 1000.0).roundToLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
