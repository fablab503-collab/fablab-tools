package com.fablab503.velotrack

import android.app.Application
import com.google.android.material.color.DynamicColors
import org.maplibre.android.MapLibre

/**
 * Initialises MapLibre once for the process, tells it we are permanently offline, and opts every
 * activity into Material You dynamic colour (Android 12+; the baseline theme applies elsewhere).
 */
class VeloTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        MapLibre.setConnected(false)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
