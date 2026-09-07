package com.fablab503.velotrack.pmtiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PmHeaderTest {

    private fun buildHeader(
        magic: String = "PMTiles",
        version: Int = 3,
        clustered: Int = 1,
        internalCompression: Int = 2,
        tileCompression: Int = 2,
        tileType: Int = 1,
        minZoom: Int = 0,
        maxZoom: Int = 15
    ): ByteArray {
        val bb = ByteBuffer.allocate(PmHeader.LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(magic.toByteArray(Charsets.US_ASCII))
        bb.put(version.toByte())
        bb.putLong(127L)                       // root dir offset
        bb.putLong(16257L)                     // root dir length
        bb.putLong(16384L)                     // metadata offset
        bb.putLong(5000L)                      // metadata length
        bb.putLong(21384L)                     // leaf dirs offset
        bb.putLong(40_000_000L)                // leaf dirs length
        bb.putLong(40_021_384L)                // tile data offset
        bb.putLong(120_000_000_000L)           // tile data length (> 4 GiB to exercise 64-bit)
        bb.putLong(1_000_000L)                 // addressed tiles
        bb.putLong(900_000L)                   // tile entries
        bb.putLong(800_000L)                   // tile contents
        bb.put(clustered.toByte())
        bb.put(internalCompression.toByte())
        bb.put(tileCompression.toByte())
        bb.put(tileType.toByte())
        bb.put(minZoom.toByte())
        bb.put(maxZoom.toByte())
        bb.putInt(-1_800_000_000)              // min lon E7
        bb.putInt(-850_511_288)                // min lat E7
        bb.putInt(1_800_000_000)               // max lon E7
        bb.putInt(850_511_288)                 // max lat E7
        bb.put(0.toByte())                     // centre zoom
        bb.putInt(0)                           // centre lon E7
        bb.putInt(0)                           // centre lat E7
        assertEquals(PmHeader.LENGTH, bb.position())
        return bb.array()
    }

    @Test
    fun parsesEveryField() {
        val h = PmHeader.parse(buildHeader())
        assertEquals(127L, h.rootDirOffset)
        assertEquals(16257L, h.rootDirLength)
        assertEquals(16384L, h.metadataOffset)
        assertEquals(5000L, h.metadataLength)
        assertEquals(21384L, h.leafDirsOffset)
        assertEquals(40_000_000L, h.leafDirsLength)
        assertEquals(40_021_384L, h.tileDataOffset)
        assertEquals(120_000_000_000L, h.tileDataLength)
        assertTrue(h.clustered)
        assertEquals(PmHeader.COMPRESSION_GZIP, h.internalCompression)
        assertEquals(PmHeader.COMPRESSION_GZIP, h.tileCompression)
        assertEquals(PmHeader.TILE_TYPE_MVT, h.tileType)
        assertEquals(0, h.minZoom)
        assertEquals(15, h.maxZoom)
    }

    @Test
    fun parsesUnsignedBytesAndFlags() {
        val h = PmHeader.parse(buildHeader(clustered = 0, internalCompression = 1, tileCompression = 4, tileType = 2, minZoom = 200, maxZoom = 255))
        assertFalse(h.clustered)
        assertEquals(PmHeader.COMPRESSION_NONE, h.internalCompression)
        assertEquals(PmHeader.COMPRESSION_ZSTD, h.tileCompression)
        assertEquals(PmHeader.TILE_TYPE_PNG, h.tileType)
        assertEquals(200, h.minZoom)
        assertEquals(255, h.maxZoom)
    }

    @Test
    fun ignoresTrailingBytes() {
        val withRoot = buildHeader() + ByteArray(16384 - PmHeader.LENGTH) { 0x42 }
        assertEquals(PmHeader.parse(buildHeader()), PmHeader.parse(withRoot))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadMagic() {
        PmHeader.parse(buildHeader(magic = "PMTilez"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongVersion() {
        PmHeader.parse(buildHeader(version = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortBuffer() {
        PmHeader.parse(buildHeader().copyOf(126))
    }
}
