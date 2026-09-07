package com.fablab503.velotrack

import android.app.Application
import org.maplibre.android.MapLibre

/** Initialises MapLibre once for the process and tells it we are permanently offline. */
class VeloTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        MapLibre.setConnected(false)
    }
}
