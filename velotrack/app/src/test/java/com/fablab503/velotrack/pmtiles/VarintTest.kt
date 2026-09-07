package com.fablab503.velotrack.pmtiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VarintTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun decodesSpecExamples() {
        val r = VarintReader(bytes(0x00, 0x01, 0x7F, 0xE5, 0x8E, 0x26))
        assertEquals(0L, r.next())
        assertEquals(1L, r.next())
        assertEquals(127L, r.next())
        assertEquals(624485L, r.next())
        assertEquals(6, r.pos)
        assertEquals(0, r.remaining)
    }

    @Test
    fun startOffsetIsHonoured() {
        val r = VarintReader(bytes(0xFF, 0x2A), start = 1)
        assertEquals(42L, r.next())
        assertEquals(2, r.pos)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedVarintThrows() {
        VarintReader(bytes(0x80)).next()
    }

    @Test(expected = IllegalArgumentException::class)
    fun readingPastEndThrows() {
        val r = VarintReader(bytes(0x01))
        r.next()
        r.next()
    }

    @Test(expected = IllegalArgumentException::class)
    fun moreThanTenBytesThrows() {
        // 11 continuation bytes never terminate within 64 bits.
        VarintReader(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01)).next()
    }

    @Test
    fun encodeMatchesKnownBytes() {
        assertArrayEquals(bytes(0x00), VarintReader.encode(0L))
        assertArrayEquals(bytes(0x7F), VarintReader.encode(127L))
        assertArrayEquals(bytes(0x80, 0x01), VarintReader.encode(128L))
        assertArrayEquals(bytes(0xE5, 0x8E, 0x26), VarintReader.encode(624485L))
        assertArrayEquals(bytes(0xAC, 0x02), VarintReader.encode(300L))
    }

    @Test
    fun encodeDecodeRoundTripIncludingFull64Bits() {
        val values = longArrayOf(0L, 1L, 127L, 128L, 300L, 16383L, 16384L, 19078479L, 1073741823L,
            Int.MAX_VALUE.toLong(), 1L shl 40, Long.MAX_VALUE, -1L)
        val encoded = values.map { VarintReader.encode(it) }
        assertEquals(10, encoded.last().size) // unsigned 2^64-1 needs ten bytes
        var all = ByteArray(0)
        for (e in encoded) all += e
        val r = VarintReader(all)
        for (v in values) assertEquals(v, r.next())
        assertEquals(0, r.remaining)
    }
}
