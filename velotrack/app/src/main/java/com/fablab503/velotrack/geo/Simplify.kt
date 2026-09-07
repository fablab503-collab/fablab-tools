package com.fablab503.velotrack.geo

import com.fablab503.velotrack.model.LatLon

/** Polyline simplification. Pure Kotlin, no recursion (tracks can hold 150k points). */
object Simplify {
    /** Douglas–Peucker, iterative (no recursion), keeps first and last. */
    fun rdp(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        val n = points.size
        if (n <= 2) return points.toList()

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        // Explicit stack of (start, end) index ranges to process; each entry uses two ints.
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        stack.addLast(n - 1)

        while (stack.isNotEmpty()) {
            val end = stack.removeLast()
            val start = stack.removeLast()
            if (end - start < 2) continue

            val a = points[start]
            val b = points[end]
            var maxDist = -1.0
            var maxIndex = -1
            for (i in start + 1 until end) {
                val d = Geo.pointToSegmentM(points[i], a, b)
                if (d > maxDist) {
                    maxDist = d
                    maxIndex = i
                }
            }

            if (maxIndex >= 0 && maxDist > toleranceM) {
                keep[maxIndex] = true
                stack.addLast(start)
                stack.addLast(maxIndex)
                stack.addLast(maxIndex)
                stack.addLast(end)
            }
        }

        val result = ArrayList<LatLon>()
        for (i in 0 until n) {
            if (keep[i]) result.add(points[i])
        }
        return result
    }

    /** Keeps a point only if at least minDistM from the last kept point; keeps first and last. */
    fun radial(points: List<LatLon>, minDistM: Double): List<LatLon> {
        val n = points.size
        if (n <= 2) return points.toList()

        val result = ArrayList<LatLon>()
        var last = points[0]
        result.add(last)
        for (i in 1 until n - 1) {
            val p = points[i]
            if (Geo.distanceM(last, p) >= minDistM) {
                result.add(p)
                last = p
            }
        }
        result.add(points[n - 1])
        return result
    }
}
