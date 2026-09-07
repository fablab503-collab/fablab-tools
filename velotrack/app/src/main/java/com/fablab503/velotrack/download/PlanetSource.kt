package com.fablab503.velotrack.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

/**
 * Resolves the Protomaps planet archive to download from. Daily builds are only retained for about
 * a week, so the key is looked up in `builds.json` at download time (last element = newest) with a
 * pinned fallback when the lookup fails.
 */
object PlanetSource {
    const val FALLBACK_KEY = "20260907.pmtiles"
    const val BUILDS_URL = "https://build-metadata.protomaps.dev/builds.json"
    const val BUILD_BASE_URL = "https://build.protomaps.com/"

    /**
     * Returns `(url, buildKey)`. A non-blank [override] is used verbatim (its last path segment
     * becomes the key); otherwise the newest key from [BUILDS_URL], or [FALLBACK_KEY] when the
     * metadata cannot be fetched or parsed.
     */
    suspend fun resolveUrl(client: OkHttpClient, override: String?): Pair<String, String> {
        val custom = override?.trim()
        if (!custom.isNullOrEmpty()) {
            val key = custom.substringAfterLast('/').substringBefore('?').ifEmpty { "custom" }
            return Pair(custom, key)
        }
        val key = try {
            withContext(Dispatchers.IO) { fetchLatestKey(client) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: FALLBACK_KEY
        return Pair(BUILD_BASE_URL + key, key)
    }

    /** Parses `builds.json` (array of `{key, size, uploaded, version, …}` in upload order). */
    fun parseLatestKey(json: String): String? {
        return try {
            val array = JSONArray(json)
            for (i in array.length() - 1 downTo 0) {
                val key = array.optJSONObject(i)?.optString("key").orEmpty()
                if (key.endsWith(".pmtiles")) return key
            }
            null
        } catch (e: JSONException) {
            null
        }
    }

    private fun fetchLatestKey(client: OkHttpClient): String? {
        val request = Request.Builder().url(BUILDS_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            return parseLatestKey(text)
        }
    }
}
