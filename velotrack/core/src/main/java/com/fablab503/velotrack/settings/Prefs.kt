package com.fablab503.velotrack.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.fablab503.velotrack.model.CameraMode
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.model.ScreenMode
import com.fablab503.velotrack.model.ThemeMode
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

    /** Start a recording by itself when riding is detected while idle. */
    var autoRecord: Boolean
        get() = sp.getBoolean(KEY_AUTO_RECORD, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_RECORD, value).apply()

    /**
     * Energy saver. One switch for the three things that actually cost a phone a long ride: the
     * screen is dimmed, the map draws at half the frame rate and flat, and GPS is asked for a fix
     * every few seconds instead of every second. It never changes what is recorded, only how often
     * and how brightly, and it is deliberately not sticky across installs - a rider turns it on
     * when the battery is low, not once and forever.
     */
    var energySaver: Boolean
        get() = sp.getBoolean(KEY_ENERGY_SAVER, false)
        set(value) = sp.edit().putBoolean(KEY_ENERGY_SAVER, value).apply()

    /**
     * Minutes of no movement before the app asks whether the ride is over; 0 means never ask.
     * Stored as a string because ListPreference writes strings.
     */
    var inactivityReminderMin: Int
        get() = sp.getString(KEY_INACTIVITY_REMINDER, null)?.toIntOrNull()
            ?: DEFAULT_INACTIVITY_REMINDER_MIN
        set(value) = sp.edit().putString(KEY_INACTIVITY_REMINDER, value.toString()).apply()

    var screenMode: ScreenMode
        get() = enumOrDefault(sp.getString(KEY_SCREEN_MODE, null), ScreenMode.KEEP_ON)
        set(value) = sp.edit().putString(KEY_SCREEN_MODE, value.name).apply()

    /** Dark, light, or automatic (dark between sunset and sunrise); stored as the enum name. */
    var themeMode: ThemeMode
        get() = enumOrDefault(sp.getString(KEY_THEME_MODE, null), ThemeMode.AUTO)
        set(value) = sp.edit().putString(KEY_THEME_MODE, value.name).apply()

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

    /** True while the rider has folded the statistics card away; survives restarts on purpose. */
    var hudHidden: Boolean
        get() = sp.getBoolean(KEY_HUD_HIDDEN, false)
        set(value) = sp.edit().putBoolean(KEY_HUD_HIDDEN, value).apply()

    var batterySaverWarningShown: Boolean
        get() = sp.getBoolean(KEY_SAVER_WARNED, false)
        set(value) = sp.edit().putBoolean(KEY_SAVER_WARNED, value).apply()

    /** URL of a PMTiles planet file to download from instead of the official Protomaps build; null when unset or blank. */
    var planetUrlOverride: String?
        get() = sp.getString(KEY_PLANET_URL, null)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            val v = value?.trim()
            if (v.isNullOrEmpty()) sp.edit().remove(KEY_PLANET_URL).apply() else sp.edit().putString(KEY_PLANET_URL, v).apply()
        }

    /** Refuse map downloads on metered networks unless the user confirms. */
    var wifiOnlyDownloads: Boolean
        get() = sp.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, true)
        set(value) = sp.edit().putBoolean(KEY_WIFI_ONLY_DOWNLOADS, value).apply()

    /** Last weather reading fetched for the HUD chip, so it has something to show immediately on
     *  launch (and if the network call fails) instead of a blank space. Null fields mean "never
     *  fetched" or "cleared"; [weatherAgeMs] tells the caller whether it is worth refetching. */
    var weather: WeatherReading?
        get() {
            val temp = sp.getFloat(KEY_WEATHER_TEMP_C, Float.NaN).takeIf { !it.isNaN() } ?: return null
            val symbol = sp.getString(KEY_WEATHER_SYMBOL, null) ?: return null
            val lat = sp.getString(KEY_WEATHER_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString(KEY_WEATHER_LON, null)?.toDoubleOrNull() ?: return null
            val fetchedAt = sp.getLong(KEY_WEATHER_FETCHED_AT, 0L).takeIf { it > 0L } ?: return null
            return WeatherReading(temp, symbol, LatLon(lat, lon), fetchedAt)
        }
        set(value) {
            if (value == null) {
                sp.edit()
                    .remove(KEY_WEATHER_TEMP_C).remove(KEY_WEATHER_SYMBOL)
                    .remove(KEY_WEATHER_LAT).remove(KEY_WEATHER_LON).remove(KEY_WEATHER_FETCHED_AT)
                    .apply()
            } else {
                sp.edit()
                    .putFloat(KEY_WEATHER_TEMP_C, value.temperatureC)
                    .putString(KEY_WEATHER_SYMBOL, value.symbolCode)
                    .putString(KEY_WEATHER_LAT, value.at.lat.toString())
                    .putString(KEY_WEATHER_LON, value.at.lon.toString())
                    .putLong(KEY_WEATHER_FETCHED_AT, value.fetchedAtMs)
                    .apply()
            }
        }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    companion object {
        const val KEY_ACCURACY_CUTOFF = "accuracy_cutoff_m"
        const val KEY_AUTO_PAUSE = "auto_pause"
        const val KEY_AUTO_RECORD = "auto_record"
        const val KEY_ENERGY_SAVER = "energy_saver"
        const val KEY_INACTIVITY_REMINDER = "inactivity_reminder_min"
        const val KEY_SCREEN_MODE = "screen_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DIM_LEVEL = "dim_level"
        const val KEY_UNITS = "units"
        const val KEY_PITCH = "pitch_deg"
        const val KEY_OFF_ROUTE = "off_route_m"
        const val KEY_ACTIVE_MAP = "active_map_file"
        const val KEY_ACTIVE_ROUTE = "active_route_id"
        const val KEY_FOLLOW_MODE = "follow_mode"
        const val KEY_SAVER_WARNED = "battery_saver_warned"
        const val KEY_HUD_HIDDEN = "hud_hidden"
        const val KEY_LAST_LAT = "last_lat"
        const val KEY_LAST_LON = "last_lon"
        const val KEY_PLANET_URL = "planet_url"
        const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        const val KEY_WEATHER_TEMP_C = "weather_temp_c"
        const val KEY_WEATHER_SYMBOL = "weather_symbol"
        const val KEY_WEATHER_LAT = "weather_lat"
        const val KEY_WEATHER_LON = "weather_lon"
        const val KEY_WEATHER_FETCHED_AT = "weather_fetched_at_ms"

        const val DEFAULT_ACCURACY_CUTOFF_M = 50f
        const val DEFAULT_INACTIVITY_REMINDER_MIN = 10
        const val DEFAULT_PITCH_DEG = 55
        const val DEFAULT_OFF_ROUTE_M = 50.0
    }
}

/**
 * One weather reading, cached so the HUD chip has something to show immediately on launch. [at] is
 * the position it was fetched for -- once the rider is far enough from it, [MainActivity] fetches
 * again rather than showing yesterday's weather for a different town.
 */
data class WeatherReading(val temperatureC: Float, val symbolCode: String, val at: LatLon, val fetchedAtMs: Long)
