package com.fablab503.velotrack.pmtiles

/**
 * Merges byte ranges so a download needs fewer HTTP range requests, trading a bounded amount of
 * over-fetched bytes for fewer round trips (same idea as the `overfetch` parameter of `pmtiles extract`).
 *
 * Pure Kotlin (no android.*).
 */
object RangePlanner {

    /**
     * @param sorted absolute, inclusive byte ranges sorted ascending and non-overlapping.
     * @param overfetch fraction of the payload (sum of range sizes) that may be spent on gaps between merged ranges.
     * @param maxRange a merged range never exceeds this many bytes (an input range already larger is kept as is).
     * @return merged inclusive ranges, sorted ascending. Gaps are closed smallest first until the budget is spent.
     * @throws IllegalArgumentException when the input is not sorted/non-overlapping or a range is empty.
     */
    fun coalesce(sorted: List<LongRange>, overfetch: Double = 0.05, maxRange: Long = 8L shl 20): List<LongRange> {
        if (sorted.isEmpty()) return emptyList()
        require(overfetch >= 0.0) { "negative overfetch: $overfetch" }
        require(maxRange > 0L) { "maxRange must be positive: $maxRange" }

        val n = sorted.size
        val starts = LongArray(n)
        val ends = LongArray(n)
        var payload = 0L
        for (i in 0 until n) {
            val r = sorted[i]
            require(r.first <= r.last) { "empty range at $i: $r" }
            if (i > 0) require(r.first > ends[i - 1]) { "ranges not sorted/non-overlapping at $i: $r" }
            starts[i] = r.first
            ends[i] = r.last
            payload += r.last - r.first + 1L
        }
        if (n == 1) return listOf(starts[0]..ends[0])

        var budget = (payload.toDouble() * overfetch).toLong()

        // Union-find over consecutive groups; the representative is the leftmost index of its group.
        val parent = IntArray(n) { it }
        val groupEnd = LongArray(n) { ends[it] }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x]
            var y = i
            while (parent[y] != x) {
                val next = parent[y]
                parent[y] = x
                y = next
            }
            return x
        }

        // Gap i sits between range i and i+1; close the smallest gaps first.
        val gapOrder = (0 until n - 1).sortedBy { starts[it + 1] - ends[it] - 1L }
        for (g in gapOrder) {
            val gap = starts[g + 1] - ends[g] - 1L
            if (gap > budget) break
            val left = find(g)
            val right = find(g + 1)
            val mergedLength = groupEnd[right] - starts[left] + 1L
            if (mergedLength > maxRange) continue
            parent[right] = left
            groupEnd[left] = groupEnd[right]
            budget -= gap
        }

        val out = ArrayList<LongRange>()
        for (i in 0 until n) {
            if (parent[i] == i) out.add(starts[i]..groupEnd[i])
        }
        return out
    }
}
