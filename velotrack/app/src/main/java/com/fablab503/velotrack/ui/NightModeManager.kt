package com.fablab503.velotrack.ui

import androidx.appcompat.app.AppCompatDelegate
import com.fablab503.velotrack.geo.SolarTimes
import com.fablab503.velotrack.model.ThemeMode
import com.fablab503.velotrack.settings.Prefs
import java.time.ZoneId

/**
 * Turns the [ThemeMode] preference into an AppCompat night mode. [ThemeMode.AUTO] is dark
 * between sunset and sunrise at the last known position ([Prefs.lastPosition]) in the device
 * time zone, falling back to 07:00-19:00 when no position has been stored yet.
 *
 * The mode is process-global (`AppCompatDelegate.setDefaultNightMode`), so several instances
 * (application and activity) agree on what was last applied.
 */
class NightModeManager(private val prefs: Prefs) {

    /**
     * Applies AppCompatDelegate.setDefaultNightMode for the current ThemeMode and time. Returns
     * the mode applied (MODE_NIGHT_YES/NO). Safe to call repeatedly; no-op when unchanged.
     */
    fun apply(nowMs: Long = System.currentTimeMillis()): Int {
        val mode = resolveMode(nowMs)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
        return mode
    }

    /** Millis until the next re-evaluation is needed, or null for DARK/LIGHT. */
    fun millisUntilNextCheck(nowMs: Long = System.currentTimeMillis()): Long? {
        if (prefs.themeMode != ThemeMode.AUTO) return null
        val position = prefs.lastPosition
        val next = SolarTimes.nextTransitionMs(position?.lat, position?.lon, nowMs, ZoneId.systemDefault())
        return (next - nowMs).coerceAtLeast(0L)
    }

    /** True if the resolved mode for [nowMs] differs from what apply() last set. */
    fun needsChange(nowMs: Long = System.currentTimeMillis()): Boolean =
        resolveMode(nowMs) != AppCompatDelegate.getDefaultNightMode()

    private fun resolveMode(nowMs: Long): Int = when (prefs.themeMode) {
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.AUTO -> {
            val position = prefs.lastPosition
            val night = SolarTimes.isNight(position?.lat, position?.lon, nowMs, ZoneId.systemDefault())
            if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        }
    }
}
