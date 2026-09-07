package com.fablab503.velotrack.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.fablab503.velotrack.model.CameraMode
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.model.ScreenMode
import com.fablab503.velotrack.model.Units

/** Typed access to the app's SharedPreferences. Keys are public so res/xml/preferences.xml can use them. */
class Prefs(context: Context) {

    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /** Fixes less accurate than this (metres) are ignored. */
    var accuracyCutoffM: Float
        get() = sp.getString(KEY_ACCURACY_CUTOFF, null)?.toFloatOrNull() ?: DEFAULT_ACCURACY_CUTOFF_M
        set(value) = sp.edit().putString(KEY_ACCURACY_CUTOFF, value.toString()).apply()

    var autoPauseEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_PAUSE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_PAUSE, value).apply()

    var screenMode: ScreenMode
        get() = enumOrDefault(sp.getString(KEY_SCREEN_MODE, null), ScreenMode.KEEP_ON)
        set(value) = sp.edit().putString(KEY_SCREEN_MODE, value.name).apply()

    /** Brightness 0.05..1.0 used when screenMode == DIM. */
    var dimLevel: Float
        get() = (sp.getInt(KEY_DIM_LEVEL, 20) / 100f).coerceIn(0.05f, 1f)
        set(value) = sp.edit().putInt(KEY_DIM_LEVEL, (value * 100).toInt().coerceIn(5, 100)).apply()

    var units: Units
        get() = enumOrDefault(sp.getString(KEY_UNITS, null), Units.METRIC)
        set(value) = sp.edit().putString(KEY_UNITS, value.name).apply()

    /** Camera tilt in degrees for Follow 3D; MapLibre caps at 60. */
    var pitchDeg: Double
        get() = sp.getInt(KEY_PITCH, DEFAULT_PITCH_DEG).coerceIn(0, 60).toDouble()
        set(value) = sp.edit().putInt(KEY_PITCH, value.toInt().coerceIn(0, 60)).apply()

    var offRouteM: Double
        get() = sp.getString(KEY_OFF_ROUTE, null)?.toDoubleOrNull() ?: DEFAULT_OFF_ROUTE_M
        set(value) = sp.edit().putString(KEY_OFF_ROUTE, value.toString()).apply()

    /** Absolute path of the active map file, or null. */
    var activeMapFile: String?
        get() = sp.getString(KEY_ACTIVE_MAP, null)
        set(value) = sp.edit().putString(KEY_ACTIVE_MAP, value).apply()

    var activeRouteId: Long?
        get() = sp.getLong(KEY_ACTIVE_ROUTE, -1L).takeIf { it >= 0 }
        set(value) = sp.edit().putLong(KEY_ACTIVE_ROUTE, value ?: -1L).apply()

    /** Last follow mode chosen by the user (never FREE). */
    var followMode: CameraMode
        get() = enumOrDefault(sp.getString(KEY_FOLLOW_MODE, null), CameraMode.FOLLOW_3D).let {
            if (it == CameraMode.FREE) CameraMode.FOLLOW_3D else it
        }
        set(value) = sp.edit().putString(KEY_FOLLOW_MODE, value.name).apply()

    /** Last known rider position, used to place the map before the first fix of a session. */
    var lastPosition: LatLon?
        get() {
            val lat = sp.getString(KEY_LAST_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString(KEY_LAST_LON, null)?.toDoubleOrNull() ?: return null
            return LatLon(lat, lon)
        }
        set(value) {
            if (value == null) {
                sp.edit().remove(KEY_LAST_LAT).remove(KEY_LAST_LON).apply()
            } else {
                sp.edit().putString(KEY_LAST_LAT, value.lat.toString()).putString(KEY_LAST_LON, value.lon.toString()).apply()
            }
        }

    var batterySaverWarningShown: Boolean
        get() = sp.getBoolean(KEY_SAVER_WARNED, false)
        set(value) = sp.edit().putBoolean(KEY_SAVER_WARNED, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    companion object {
        const val KEY_ACCURACY_CUTOFF = "accuracy_cutoff_m"
        const val KEY_AUTO_PAUSE = "auto_pause"
        const val KEY_SCREEN_MODE = "screen_mode"
        const val KEY_DIM_LEVEL = "dim_level"
        const val KEY_UNITS = "units"
        const val KEY_PITCH = "pitch_deg"
        const val KEY_OFF_ROUTE = "off_route_m"
        const val KEY_ACTIVE_MAP = "active_map_file"
        const val KEY_ACTIVE_ROUTE = "active_route_id"
        const val KEY_FOLLOW_MODE = "follow_mode"
        const val KEY_SAVER_WARNED = "battery_saver_warned"
        const val KEY_LAST_LAT = "last_lat"
        const val KEY_LAST_LON = "last_lon"

        const val DEFAULT_ACCURACY_CUTOFF_M = 50f
        const val DEFAULT_PITCH_DEG = 55
        const val DEFAULT_OFF_ROUTE_M = 50.0
    }
}
