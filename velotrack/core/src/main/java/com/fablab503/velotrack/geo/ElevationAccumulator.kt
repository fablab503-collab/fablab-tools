package com.fablab503.velotrack.geo

/**
 * Elevation gain/loss with exponential smoothing and hysteresis.
 *
 * Each accepted altitude sample is smoothed (`smoothed = alpha * ele + (1 - alpha) * smoothed`).
 * An anchor altitude is kept; only when the smoothed altitude moves at least [thresholdM] away
 * from the anchor is the difference added to gain or loss and the anchor moved. Samples whose
 * vertical accuracy is worse than [maxVerticalAccuracyM] are ignored; a null accuracy is accepted.
 */
class ElevationAccumulator(
    private val thresholdM: Double = 5.0,
    private val alpha: Double = 0.3,
    private val maxVerticalAccuracyM: Float = 20f,
) {
    var gainM: Double = 0.0
        private set
    var lossM: Double = 0.0
        private set

    private var smoothed: Double = 0.0
    private var anchor: Double = 0.0
    private var hasSample: Boolean = false

    fun add(ele: Double, verticalAccuracyM: Float?) {
        if (ele.isNaN() || ele.isInfinite()) return
        if (verticalAccuracyM != null && verticalAccuracyM > maxVerticalAccuracyM) return

        if (!hasSample) {
            smoothed = ele
            anchor = ele
            hasSample = true
            return
        }

        smoothed = alpha * ele + (1.0 - alpha) * smoothed
        val delta = smoothed - anchor
        if (delta >= thresholdM) {
            gainM += delta
            anchor = smoothed
        } else if (delta <= -thresholdM) {
            lossM += -delta
            anchor = smoothed
        }
    }

    fun reset() {
        gainM = 0.0
        lossM = 0.0
        smoothed = 0.0
        anchor = 0.0
        hasSample = false
    }
}
