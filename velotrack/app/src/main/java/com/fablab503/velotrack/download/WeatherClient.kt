package com.fablab503.velotrack.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Current temperature and condition from the Norwegian Meteorological Institute's free,
 * keyless Locationforecast API -- the same data [MET.no / Yr.no](https://www.yr.no) itself is
 * built on, which is the "official website" [MainActivity]'s weather chip opens on tap.
 *
 * This is the one place in the app that calls a weather API, and it is called only when the HUD
 * chip is due to refresh (see `MainActivity.maybeRefreshWeather`) -- never in the background,
 * never on a timer while the rider isn't looking at the screen.
 */
object WeatherClient {

    data class Now(val temperatureC: Float, val symbolCode: String)

    /** Null on any failure (offline, malformed response, no matching forecast entry) -- weather is
     *  decoration, not something worth surfacing an error dialog over. */
    suspend fun current(client: OkHttpClient, lat: Double, lon: Double): Now? = withContext(Dispatchers.IO) {
        try {
            val url = String.format(Locale.ROOT, "%s?lat=%.4f&lon=%.4f", ENDPOINT, lat, lon)
            val request = Request.Builder().url(url).header("User-Agent", APP_USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parse(body)
            }
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
    }

    /**
     * Reads the first timeseries entry (always "now" in this API): [air_temperature] straight from
     * `instant.details`, and the symbol from whichever summary window is present, since a distant
     * forecast entry can lack `next_1_hours` -- see the compact product's General Forecast Format.
     */
    internal fun parse(body: String): Now? {
        val timeseries = JSONObject(body).optJSONObject("properties")?.optJSONArray("timeseries") ?: return null
        val first = timeseries.optJSONObject(0) ?: return null
        val data = first.optJSONObject("data") ?: return null
        val temp = data.optJSONObject("instant")?.optJSONObject("details")?.optDouble("air_temperature")
            ?: return null
        val symbol = SUMMARY_WINDOWS.firstNotNullOfOrNull { window ->
            data.optJSONObject(window)?.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotBlank() }
        } ?: return null
        return Now(temp.toFloat(), symbol)
    }

    /** The public per-location forecast page this data comes from; opened when the rider taps the chip. */
    fun officialForecastUrl(lat: Double, lon: Double): String =
        String.format(Locale.ROOT, "https://www.yr.no/en/forecast/daily-table/%.4f,%.4f", lat, lon)

    private const val ENDPOINT = "https://api.met.no/weatherapi/locationforecast/2.0/compact"
    private val SUMMARY_WINDOWS = listOf("next_1_hours", "next_6_hours", "next_12_hours")
}
