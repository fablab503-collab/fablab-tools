package com.fablab503.velotrack.pmtiles

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The fixed 127-byte PMTiles v3 header (spec chapter 3). All offsets are absolute byte positions in the archive.
 *
 * Pure Kotlin (no android.*).
 */
data class PmHeader(
    val rootDirOffset: Long,
    val rootDirLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirsOffset: Long,
    val leafDirsLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val clustered: Boolean,
    val internalCompression: Int,
    val tileCompression: Int,
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int
) {
    companion object {
        /** Length of the binary header in bytes. */
        const val LENGTH = 127

        /** Magic bytes "PMTiles" (UTF-8). */
        val MAGIC: ByteArray = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73)

        /** Spec version this parser understands. */
        const val VERSION = 3

        // Compression codes (header bytes IC / TC).
        const val COMPRESSION_UNKNOWN = 0
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        const val COMPRESSION_BROTLI = 3
        const val COMPRESSION_ZSTD = 4

        // Tile type codes (header byte TT).
        const val TILE_TYPE_UNKNOWN = 0
        const val TILE_TYPE_MVT = 1
        const val TILE_TYPE_PNG = 2
        const val TILE_TYPE_JPEG = 3
        const val TILE_TYPE_WEBP = 4
        const val TILE_TYPE_AVIF = 5

        /**
         * Parses the first 127 bytes of [bytes] (extra trailing bytes are ignored).
         * @throws IllegalArgumentException when the buffer is too short, the magic is wrong or the version is not 3.
         */
        fun parse(bytes: ByteArray): PmHeader {
            if (bytes.size < LENGTH) {
                throw IllegalArgumentException("PMTiles header needs $LENGTH bytes, got ${bytes.size}")
            }
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i]) throw IllegalArgumentException("Not a PMTiles archive (bad magic)")
            }
            val version = bytes[7].toInt() and 0xFF
            if (version != VERSION) {
                throw IllegalArgumentException("Unsupported PMTiles version $version (expected $VERSION)")
            }
            val bb = ByteBuffer.wrap(bytes, 0, LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            return PmHeader(
                rootDirOffset = bb.getLong(8),
                rootDirLength = bb.getLong(16),
                metadataOffset = bb.getLong(24),
                metadataLength = bb.getLong(32),
                leafDirsOffset = bb.getLong(40),
                leafDirsLength = bb.getLong(48),
                tileDataOffset = bb.getLong(56),
                tileDataLength = bb.getLong(64),
                // 72: addressed tiles, 80: tile entries, 88: tile contents (not needed for extraction)
                clustered = (bytes[96].toInt() and 0xFF) == 1,
                internalCompression = bytes[97].toInt() and 0xFF,
                tileCompression = bytes[98].toInt() and 0xFF,
                tileType = bytes[99].toInt() and 0xFF,
                minZoom = bytes[100].toInt() and 0xFF,
                maxZoom = bytes[101].toInt() and 0xFF
                // 102..117: min/max lon/lat E7, 118: centre zoom, 119..126: centre lon/lat E7
            )
        }
    }
}
