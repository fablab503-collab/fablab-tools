package com.fablab503.velotrack.pmtiles

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Gzip helpers for PMTiles directories and tiles (internal/tile compression code 2).
 *
 * Pure Kotlin (no android.*).
 */
object Gzip {

    /** Decompresses a complete gzip member (or concatenation of members). */
    fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { input -> input.readBytes() }
    }

    /** True when [data] starts with the gzip magic bytes 1F 8B. */
    fun isGzip(data: ByteArray): Boolean {
        return data.size >= 2 && (data[0].toInt() and 0xFF) == 0x1F && (data[1].toInt() and 0xFF) == 0x8B
    }
}
