package com.fablab503.velotrack.map

import java.util.Locale

/**
 * Builds the MapLibre source `url` for a local tile archive.
 *
 * - `.mbtiles` -> `mbtiles:///abs/path.mbtiles` (MBTilesFileSource percent-decodes and needs an absolute path)
 * - `.pmtiles` -> `pmtiles://file:///abs/path.pmtiles`
 *
 * Pure Kotlin (no android.*) so it is unit tested on the JVM.
 */
object MapUrl {

    private const val HEX = "0123456789ABCDEF"

    /**
     * @param absolutePath absolute file path starting with `/`.
     * @throws IllegalArgumentException when the extension is not `.mbtiles`/`.pmtiles` or the path is not absolute.
     */
    fun forFile(absolutePath: String): String {
        if (!absolutePath.startsWith("/")) {
            throw IllegalArgumentException("Map file path must be absolute: $absolutePath")
        }
        val lower = absolutePath.lowercase(Locale.ROOT)
        val encoded = encodePath(absolutePath)
        return when {
            lower.endsWith(".mbtiles") -> "mbtiles://$encoded"
            lower.endsWith(".pmtiles") -> "pmtiles://file://$encoded"
            else -> throw IllegalArgumentException("Unsupported map file (expected .mbtiles or .pmtiles): $absolutePath")
        }
    }

    /**
     * Percent-encodes every byte of the UTF-8 form of [path] that is outside `[A-Za-z0-9._~/-]`.
     * Spaces become `%20` (never `+`), `/` separators are kept.
     */
    fun encodePath(path: String): String {
        val bytes = path.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size + 16)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (isSafe(c)) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append(HEX[c shr 4])
                sb.append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun isSafe(c: Int): Boolean =
        (c >= 'A'.code && c <= 'Z'.code) ||
            (c >= 'a'.code && c <= 'z'.code) ||
            (c >= '0'.code && c <= '9'.code) ||
            c == '.'.code || c == '_'.code || c == '~'.code || c == '/'.code || c == '-'.code
}
