package com.fablab503.velotrack.pmtiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PmDirectoryTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /**
     * Hand-encoded directory with four entries:
     *  1. tile 5,   offset 1000 (varint 1001), length 42,  run 1
     *  2. tile 6,   contiguous (varint 0) -> 1042, length 10, run 5   (covers 6..10)
     *  3. tile 20,  offset 2000 (varint 2001), length 7,   run 1
     *  4. tile 100, offset 0 (varint 1), length 300, run 0 -> leaf directory pointer
     */
    private val encoded = bytes(
        0x04,                               // 4 entries
        0x05, 0x01, 0x0E, 0x50,             // id deltas: 5, 1, 14, 80
        0x01, 0x05, 0x01, 0x00,             // run lengths: 1, 5, 1, 0
        0x2A, 0x0A, 0x07, 0xAC, 0x02,       // lengths: 42, 10, 7, 300
        0xE9, 0x07, 0x00, 0xD1, 0x0F, 0x01  // offsets: 1001 (=1000), 0 (contiguous), 2001 (=2000), 1 (=0)
    )

    private val entries = PmDirectory.decode(encoded)

    @Test
    fun decodesAllFields() {
        assertEquals(4, entries.size)
        assertEquals(DirEntry(5L, 1000L, 42, 1), entries[0])
        assertEquals(DirEntry(6L, 1042L, 10, 5), entries[1])
        assertEquals(DirEntry(20L, 2000L, 7, 1), entries[2])
        assertEquals(DirEntry(100L, 0L, 300, 0), entries[3])
        assertTrue(entries[3].isLeaf)
        assertTrue(!entries[0].isLeaf)
    }

    @Test
    fun contiguousOffsetFollowsPreviousEntry() {
        assertEquals(entries[0].offset + entries[0].length, entries[1].offset)
    }

    @Test
    fun matchesEncoderOutput() {
        val ids = longArrayOf(5L, 6L, 20L, 100L)
        val runs = longArrayOf(1L, 5L, 1L, 0L)
        val lens = longArrayOf(42L, 10L, 7L, 300L)
        val offs = longArrayOf(1001L, 0L, 2001L, 1L)
        var buf = VarintReader.encode(4L)
        var last = 0L
        for (id in ids) { buf += VarintReader.encode(id - last); last = id }
        for (v in runs) buf += VarintReader.encode(v)
        for (v in lens) buf += VarintReader.encode(v)
        for (v in offs) buf += VarintReader.encode(v)
        assertEquals(encoded.toList(), buf.toList())
        assertEquals(entries, PmDirectory.decode(buf))
    }

    @Test
    fun findExactAndInsideRun() {
        assertEquals(entries[0], PmDirectory.find(entries, 5L))
        assertEquals(entries[1], PmDirectory.find(entries, 6L))
        assertEquals(entries[1], PmDirectory.find(entries, 8L))
        assertEquals(entries[1], PmDirectory.find(entries, 10L))
        assertEquals(entries[2], PmDirectory.find(entries, 20L))
    }

    @Test
    fun findOutsideRunsIsNull() {
        assertNull(PmDirectory.find(entries, 0L))
        assertNull(PmDirectory.find(entries, 4L))
        assertNull(PmDirectory.find(entries, 11L))   // just past the run of 5
        assertNull(PmDirectory.find(entries, 19L))
        assertNull(PmDirectory.find(entries, 21L))   // run length 1 covers only 20
        assertNull(PmDirectory.find(entries, 99L))
    }

    @Test
    fun leafPointerCoversEverythingAfterIt() {
        val leaf = PmDirectory.find(entries, 100L)
        assertNotNull(leaf)
        assertEquals(0, leaf?.runLength)
        assertEquals(entries[3], PmDirectory.find(entries, 101L))
        assertEquals(entries[3], PmDirectory.find(entries, 5_000_000L))
    }

    @Test
    fun findOnEmptyDirectoryIsNull() {
        assertNull(PmDirectory.find(emptyList(), 5L))
    }

    @Test
    fun emptyDirectoryDecodesToNoEntries() {
        assertTrue(PmDirectory.decode(bytes(0x00)).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedDirectoryThrows() {
        PmDirectory.decode(encoded.copyOf(encoded.size - 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun absurdEntryCountThrows() {
        PmDirectory.decode(bytes(0xFF, 0xFF, 0x7F))
    }

    @Test(expected = IllegalArgumentException::class)
    fun firstEntryCannotBeContiguous() {
        // 1 entry: id 0, run 1, length 1, offset varint 0
        PmDirectory.decode(bytes(0x01, 0x00, 0x01, 0x01, 0x00))
    }
}
