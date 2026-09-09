package com.fablab503.velotrack.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.fablab503.velotrack.model.GpsFix
import com.fablab503.velotrack.model.GpsStatus

/**
 * GPS-only location feed. Uses `LocationManager.GPS_PROVIDER` exclusively via
 * [LocationManagerCompat] (1 Hz, high accuracy, no batching) so no Wi-Fi or cell scanning is ever
 * triggered, and a [GnssStatus.Callback] for satellite counts. All callbacks arrive on the main
 * thread. [start] is safe to call twice: the second call only replaces the listener.
 */
class GpsSource(context: Context) {

    interface Listener {
        fun onFix(fix: GpsFix)
        fun onStatus(status: GpsStatus)
    }

    private val appContext: Context = context.applicationContext
    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var listener: Listener? = null
    private var started = false
    private var gnssRegistered = false
    private var current = GpsStatus()

    /** True when location is switched on and the GPS provider is enabled. */
    val isGpsEnabled: Boolean
        get() {
            val lm = locationManager ?: return false
            val providerEnabled = try {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (e: IllegalArgumentException) {
                false
            }
            return LocationManagerCompat.isLocationEnabled(lm) && providerEnabled
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val locationListener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            val fix = location.toGpsFix()
            if (!current.hasFix || !current.providerEnabled) {
                publish(current.copy(hasFix = true, providerEnabled = true))
            }
            listener?.onFix(fix)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) publish(current.copy(providerEnabled = true))
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                publish(current.copy(providerEnabled = false, hasFix = false, satellitesUsed = 0))
            }
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val total = status.satelliteCount
            var used = 0
            for (i in 0 until total) {
                if (status.usedInFix(i)) used++
            }
            publish(current.copy(satellitesUsed = used, satellitesTotal = total))
        }

        override fun onFirstFix(ttffMillis: Int) {
            publish(current.copy(hasFix = true))
        }

        override fun onStopped() {
            publish(current.copy(hasFix = false, satellitesUsed = 0, satellitesTotal = 0))
        }
    }

    /**
     * Starts GPS updates and GNSS status reporting. Does nothing (beyond reporting the current
     * status) when the fine-location permission is missing or the device has no location service.
     */
    @SuppressLint("MissingPermission") // hasPermission() is checked right before registering
    fun start(listener: Listener) {
        this.listener = listener
        val lm = locationManager
        if (lm == null) {
            publish(current.copy(providerEnabled = false))
            return
        }
        if (started) {
            listener.onStatus(current)
            return
        }
        current = GpsStatus(providerEnabled = isGpsEnabled)
        if (!hasPermission()) {
            listener.onStatus(current)
            return
        }

        val request = LocationRequestCompat.Builder(1000L)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(0L)
            .build()
        val executor = ContextCompat.getMainExecutor(appContext)
        try {
            LocationManagerCompat.requestLocationUpdates(
                lm,
                LocationManager.GPS_PROVIDER,
                request,
                executor,
                locationListener,
            )
            started = true
        } catch (e: Exception) {
            // Provider missing or security state changed underneath us: report and give up.
            publish(current.copy(providerEnabled = false))
            return
        }

        gnssRegistered = try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.registerGnssStatusCallback(executor, gnssCallback)
            } else {
                registerGnssLegacy(lm)
            }
        } catch (e: Exception) {
            false
        }

        listener.onStatus(current)
    }

    /** API 24–29 overload (deprecated in 30, still the only option below it). */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun registerGnssLegacy(lm: LocationManager): Boolean =
        lm.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))

    fun stop() {
        val lm = locationManager
        if (lm != null) {
            if (started) {
                try {
                    LocationManagerCompat.removeUpdates(lm, locationListener)
                } catch (e: Exception) {
                    // Nothing to do: the request is gone either way.
                }
            }
            if (gnssRegistered) {
                try {
                    lm.unregisterGnssStatusCallback(gnssCallback)
                } catch (e: Exception) {
                    // Ignore: callback already gone.
                }
            }
        }
        started = false
        gnssRegistered = false
        listener = null
        current = GpsStatus(providerEnabled = current.providerEnabled)
    }

    private fun publish(status: GpsStatus) {
        current = status
        listener?.onStatus(status)
    }
}
