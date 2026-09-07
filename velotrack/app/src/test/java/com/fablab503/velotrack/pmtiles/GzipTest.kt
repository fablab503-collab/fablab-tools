package com.fablab503.velotrack.pmtiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class GzipTest {

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    @Test
    fun roundTripsText() {
        val original = "PMTiles directory bytes ".repeat(500).toByteArray(Charsets.UTF_8)
        val compressed = gzip(original)
        assertTrue(compressed.size < original.size)
        assertTrue(Gzip.isGzip(compressed))
        assertArrayEquals(original, Gzip.gunzip(compressed))
    }

    @Test
    fun roundTripsEmptyAndBinary() {
        assertArrayEquals(ByteArray(0), Gzip.gunzip(gzip(ByteArray(0))))
        val binary = ByteArray(70_000) { (it * 31 + 7).toByte() }
        assertArrayEquals(binary, Gzip.gunzip(gzip(binary)))
    }

    @Test
    fun isGzipDetectsMagic() {
        assertFalse(Gzip.isGzip(ByteArray(0)))
        assertFalse(Gzip.isGzip(byteArrayOf(0x1F)))
        assertFalse(Gzip.isGzip("{\"vector_layers\":[]}".toByteArray(Charsets.UTF_8)))
        assertTrue(Gzip.isGzip(byteArrayOf(0x1F, 0x8B.toByte(), 0x08)))
    }

    @Test
    fun garbageThrowsIOException() {
        assertThrows(IOException::class.java) { Gzip.gunzip(byteArrayOf(1, 2, 3, 4, 5)) }
    }
}
