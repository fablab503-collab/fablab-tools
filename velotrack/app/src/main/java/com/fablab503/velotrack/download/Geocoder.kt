package com.fablab503.velotrack.download

import com.fablab503.velotrack.model.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException

/**
 * Turns typed text into a place, using OpenStreetMap's free Nominatim search -- the address
 * lookup [PlacePickerActivity] offers next to dragging the map, for a rider who knows the address
 * rather than the spot on a map. There is no bundled geocoder and never has been (see the class
 * doc on [PlacePickerActivity]); this is an explicit, occasional network call, not a background
 * one, made only when the rider types something and presses search.
 *
 * Nominatim's usage policy (operations.osmfoundation.org/policies/nominatim) sets three
 * conditions this class exists to satisfy: a real identifying User-Agent (not a bare "okhttp"),
 * a ceiling of one request per second, and no client-side autocomplete against the public
 * server -- which is why [PlacePickerActivity] fires this on a submit action, never on keystrokes.
 */
object Geocoder {

    data class Result(val name: String, val at: LatLon)

    /** Empty on no match; null on a network/parse failure so the caller can tell "no result" from
     *  "couldn't ask" and word the message differently. */
    suspend fun search(client: OkHttpClient, query: String): List<Result>? = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        try {
            val url = ENDPOINT.newBuilder()
                .addQueryParameter("q", trimmed)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", "5")
                .build()
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

    internal fun parse(body: String): List<Result> {
        val array = JSONArray(body)
        val out = ArrayList<Result>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            val name = o.optString("display_name").takeIf { it.isNotBlank() } ?: continue
            out.add(Result(name, LatLon(lat, lon)))
        }
        return out
    }

    // toHttpUrl() throws on a malformed constant; a plain string used only as a build-time literal
    // would be a stranger failure mode than the eager NPE/IAE this gives if the literal is ever
    // typo'd, so the URL is parsed once here rather than string-concatenated per request.
    private val ENDPOINT = "https://nominatim.openstreetmap.org/search".toHttpUrl()
}
