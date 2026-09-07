package com.fablab503.velotrack.map

/**
 * Substitutes the tile-archive URLs into the style template shipped in `assets/style.json`.
 *
 * String-based on purpose: `org.json` is not available in JVM unit tests.
 *
 * The current template has four vector sources `band0`..`band3`, each carrying
 * `"url": "{MAP_URL_N}"` (N = band index, see `tools/gen-style.mjs`); [render] with a list of URLs
 * fills them in. The older single-source form `"url": "{MAP_URL}"` is still supported by the
 * one-URL overload. A `null` URL turns the whole entry into an empty `"tiles": []` array, which keeps
 * the document valid JSON.
 *
 * WARNING: avoid handing a `"tiles": []` source to MapLibre. Its style parser accepts an empty
 * `tiles` array but the native TileLoader indexes `tiles[0]` unguarded, so the first render of any
 * layer bound to that source may abort the process. [MapController.loadStyle] uses its own
 * source-less base style when no band file exists at all; a `null` for a single band only happens
 * when that band file could not be created, which is not expected in normal operation.
 */
object StyleTemplate {

    /** Legacy single-source placeholder. */
    const val PLACEHOLDER = "{MAP_URL}"

    /** Prefix of the per-band placeholders `{MAP_URL_0}`, `{MAP_URL_1}`, ... */
    const val PLACEHOLDER_PREFIX = "{MAP_URL_"

    /** Matches `"url": "{MAP_URL}"` with any whitespace around the colon (covers the compact form too). */
    private val URL_ENTRY = Regex("\"url\"\\s*:\\s*\"\\{MAP_URL\\}\"")

    /** The placeholder of band [index]: `{MAP_URL_<index>}`. */
    fun placeholder(index: Int): String = "$PLACEHOLDER_PREFIX$index}"

    /**
     * Multi-band form. For every index `N` in [mapUrls], `{MAP_URL_N}` is replaced by the URL, or the
     * whole `"url": "{MAP_URL_N}"` entry by `"tiles": []` when the URL is `null`. Placeholders whose
     * index is not covered by the list are left untouched.
     */
    fun render(template: String, mapUrls: List<String?>): String {
        var out = template
        for (index in mapUrls.indices) {
            val ph = placeholder(index)
            val url = mapUrls[index]
            if (url != null) {
                out = out.replace(ph, url)
            } else {
                out = replaceUrlEntry(out, ph)
            }
        }
        return out
    }

    /** Legacy single-source form: replaces `{MAP_URL}` (or its `"url"` entry when [mapUrl] is `null`). */
    fun render(template: String, mapUrl: String?): String {
        if (mapUrl != null) {
            return template.replace(PLACEHOLDER, mapUrl)
        }
        // Exact forms first (fast path), then a whitespace-tolerant fallback.
        var out = template
            .replace("\"url\": \"{MAP_URL}\"", "\"tiles\": []")
            .replace("\"url\":\"{MAP_URL}\"", "\"tiles\": []")
        if (out.contains(PLACEHOLDER)) {
            out = URL_ENTRY.replace(out, "\"tiles\": []")
        }
        return out
    }

    /** Replaces every `"url": "<ph>"` entry (any whitespace around the colon) by `"tiles": []`. */
    private fun replaceUrlEntry(template: String, ph: String): String {
        var out = template
            .replace("\"url\": \"$ph\"", "\"tiles\": []")
            .replace("\"url\":\"$ph\"", "\"tiles\": []")
        if (out.contains(ph)) {
            val entry = Regex("\"url\"\\s*:\\s*\"" + Regex.escape(ph) + "\"")
            out = entry.replace(out, "\"tiles\": []")
        }
        return out
    }
}
