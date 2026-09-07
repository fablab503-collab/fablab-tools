package com.fablab503.velotrack.pmtiles

/**
 * One PMTiles directory entry.
 *
 * @property tileId Hilbert tile id of the first tile covered by this entry.
 * @property offset byte offset relative to the tile data section (tile entries) or to the leaf directories
 *   section (leaf entries, [runLength] == 0).
 * @property length byte length of the tile blob or of the (compressed) leaf directory.
 * @property runLength number of consecutive tile ids sharing this blob; 0 marks a leaf directory pointer.
 */
data class DirEntry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int) {
    val isLeaf: Boolean get() = runLength == 0
}

/**
 * PMTiles v3 directory decoding and lookup (spec chapter 4).
 *
 * Pure Kotlin (no android.*).
 */
object PmDirectory {

    /**
     * Decodes an ALREADY DECOMPRESSED directory: varint count, then tile id deltas, run lengths, lengths and
     * offsets (an offset varint of 0 means "contiguous with the previous entry", otherwise the value is offset + 1).
     *
     * @throws IllegalArgumentException on truncated or inconsistent data.
     */
    fun decode(decompressed: ByteArray): List<DirEntry> {
        val r = VarintReader(decompressed)
        val count = r.next()
        if (count < 0L || count > Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException("Directory entry count out of range: $count")
        }
        val n = count.toInt()
        // Each entry needs at least one byte in each of the four sections.
        if (n.toLong() * 4L > decompressed.size.toLong()) {
            throw IllegalArgumentException("Directory claims $n entries but has only ${decompressed.size} bytes")
        }
        if (n == 0) return emptyList()

        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) {
            last += r.next()
            ids[i] = last
        }
        val runs = IntArray(n)
        for (i in 0 until n) {
            runs[i] = toIntChecked(r.next(), "run length")
        }
        val lengths = IntArray(n)
        for (i in 0 until n) {
            lengths[i] = toIntChecked(r.next(), "length")
        }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = r.next()
            offsets[i] = if (v == 0L) {
                if (i == 0) throw IllegalArgumentException("First directory entry cannot be contiguous")
                offsets[i - 1] + lengths[i - 1].toLong()
            } else {
                v - 1
            }
        }
        val out = ArrayList<DirEntry>(n)
        for (i in 0 until n) {
            out.add(DirEntry(ids[i], offsets[i], lengths[i], runs[i]))
        }
        return out
    }

    /**
     * Finds the entry covering [tileId]: exact match, or the nearest preceding entry when it is a leaf pointer
     * or when [tileId] falls inside its run. Returns null when no entry covers the id.
     * [entries] must be sorted by tile id (directories always are).
     */
    fun find(entries: List<DirEntry>, tileId: Long): DirEntry? {
        var m = 0
        var n = entries.size - 1
        while (m <= n) {
            val k = (m + n) ushr 1
            val id = entries[k].tileId
            when {
                tileId > id -> m = k + 1
                tileId < id -> n = k - 1
                else -> return entries[k]
            }
        }
        // m > n here; entries[n] is the last entry with tileId < requested id.
        if (n >= 0) {
            val e = entries[n]
            if (e.runLength == 0) return e
            if (tileId - e.tileId < e.runLength.toLong()) return e
        }
        return null
    }

    private fun toIntChecked(v: Long, what: String): Int {
        if (v < 0L || v > Int.MAX_VALUE.toLong()) throw IllegalArgumentException("Directory $what out of range: $v")
        return v.toInt()
    }
}
