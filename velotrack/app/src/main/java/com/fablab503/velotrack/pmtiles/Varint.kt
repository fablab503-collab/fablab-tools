package com.fablab503.velotrack.pmtiles

/**
 * Sequential reader of unsigned LEB128 varints (up to 64 bits) as used by PMTiles v3 directories.
 *
 * Pure Kotlin (no android.*).
 */
class VarintReader(private val buf: ByteArray, start: Int = 0) {

    /** Index of the next unread byte. */
    var pos: Int = start
        private set

    val remaining: Int get() = buf.size - pos

    /**
     * Reads one varint and advances [pos].
     * @throws IllegalArgumentException when the buffer ends mid-varint or the varint exceeds 64 bits.
     */
    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= buf.size) throw IllegalArgumentException("Truncated varint at byte $pos")
            val b = buf[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift >= 64) throw IllegalArgumentException("Varint longer than 64 bits at byte $pos")
        }
    }

    companion object {
        /** Encodes [value] (treated as unsigned) as LEB128; handy for tests and for building directories. */
        fun encode(value: Long): ByteArray {
            val out = ArrayList<Byte>(10)
            var v = value
            while (true) {
                val low = (v and 0x7FL).toInt()
                v = v ushr 7
                if (v == 0L) {
                    out.add(low.toByte())
                    break
                }
                out.add((low or 0x80).toByte())
            }
            return out.toByteArray()
        }
    }
}
