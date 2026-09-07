package com.fablab503.velotrack.map

/**
 * Substitutes the tile-archive URL into the style template shipped in `assets/style.json`.
 *
 * String-based on purpose: `org.json` is not available in JVM unit tests. The template's single
 * vector source carries `"url": "{MAP_URL}"`; when no map file is active that entry is replaced by an
 * empty `"tiles": []` array, which keeps the document valid JSON.
 *
 * WARNING: do not hand the null-`mapUrl` output to MapLibre. Its style parser accepts an empty
 * `tiles` array but the native TileLoader indexes `tiles[0]` unguarded, so the first render of any
 * layer bound to that source aborts the process. [MapController.loadStyle] therefore uses its own
 * source-less base style when no map file is active and only calls [render] with a real URL.
 */
object StyleTemplate {

    const val PLACEHOLDER = "{MAP_URL}"

    /** Matches `"url": "{MAP_URL}"` with any whitespace around the colon (covers the compact form too). */
    private val URL_ENTRY = Regex("\"url\"\\s*:\\s*\"\\{MAP_URL\\}\"")

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
}
