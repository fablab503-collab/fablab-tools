package com.fablab503.velotrack

import android.app.Application
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.wear.WearPublisher
import com.fablab503.velotrack.ui.NightModeManager
import com.google.android.material.color.DynamicColors
import org.maplibre.android.MapLibre
import java.io.File

/**
 * Initialises MapLibre once per process, tells it we are permanently offline, applies the user's
 * theme (dark / light / automatic by sunset) before any activity exists, and opts every activity
 * into Material You dynamic colour (Android 12+; the baseline theme applies elsewhere).
 *
 * Runs in both the main process and the `:download` process (MapDownloadService). The download
 * service never touches MapLibre, so the extra initialisation there is harmless; the offline guard
 * below is what keeps the renderer from ever using the INTERNET permission the download needs.
 * CI greps this file for the `MapLibre.setConnected(false)` line.
 */
class VeloTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        MapLibre.setConnected(false)
        // Night mode first: DynamicColors picks its light/dark overlay per activity from the uiMode
        // this sets, and nothing is recreated because no activity exists yet.
        NightModeManager(Prefs(this)).apply()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Only the main process may mirror the ride. RideSession is a per-process object, so the
        // :download process holds its own permanently-IDLE copy; publishing that would race the
        // real one and blank the watch mid-ride.
        if (isMainProcess()) WearPublisher.start(this)
    }

    /**
     * Reads /proc/self/cmdline rather than Application.getProcessName(), which needs API 28 while
     * this app supports 26. The main process is named after the package; the other is
     * `<package>:download`.
     */
    private fun isMainProcess(): Boolean {
        val name = try {
            // NUL-terminated, not newline-terminated: cut at the first NUL.
            File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
        } catch (e: Exception) {
            return true // Unreadable only in odd sandboxes; the main process is the safe assumption.
        }
        return name == packageName
    }
}
