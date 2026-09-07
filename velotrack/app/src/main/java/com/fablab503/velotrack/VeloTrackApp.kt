package com.fablab503.velotrack

import android.app.Application
import com.google.android.material.color.DynamicColors
import org.maplibre.android.MapLibre

/**
 * Initialises MapLibre once per process, tells it we are permanently offline, and opts every
 * activity into Material You dynamic colour (Android 12+; the baseline theme applies elsewhere).
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
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
