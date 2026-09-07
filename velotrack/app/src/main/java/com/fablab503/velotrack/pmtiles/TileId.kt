package com.fablab503.velotrack.pmtiles

/**
 * PMTiles Hilbert tile ids: the cumulative position on the series of Hilbert curves starting at zoom 0
 * (spec section 4.1). Ported from the reference `tile_id.go`; validated against the spec test vectors.
 *
 * Pure Kotlin (no android.*).
 */
object TileId {

    /** Highest zoom the 64-bit id space supports without overflow in [toZxy]. */
    const val MAX_ZOOM = 30

    /** Number of ids on all zoom levels below [z]: (4^z - 1) / 3. */
    fun zoomStart(z: Int): Long {
        require(z in 0..MAX_ZOOM + 1) { "zoom out of range: $z" }
        return ((1L shl (2 * z)) - 1L) / 3L
    }

    /**
     * Hilbert id of tile (z, x, y).
     * @throws IllegalArgumentException when z is outside 0..[MAX_ZOOM] or x/y are outside 0 until 2^z.
     */
    fun fromZxy(z: Int, x: Int, y: Int): Long {
        require(z in 0..MAX_ZOOM) { "zoom out of range: $z" }
        val n = 1L shl z
        require(x >= 0 && x.toLong() < n) { "x out of range for z$z: $x" }
        require(y >= 0 && y.toLong() < n) { "y out of range for z$z: $y" }
        // z == 0: the single tile has id 0 (the loop below does not run because s == 0).
        val acc = zoomStart(z)
        var d = 0L
        var tx = x.toLong()
        var ty = y.toLong()
        var s = n shr 1
        while (s > 0L) {
            val rx = if ((tx and s) != 0L) 1L else 0L
            val ry = if ((ty and s) != 0L) 1L else 0L
            d += s * s * ((3L * rx) xor ry)
            // rotate(n, tx, ty, rx, ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = n - 1L - tx
                    ty = n - 1L - ty
                }
                val t = tx
                tx = ty
                ty = t
            }
            s = s shr 1
        }
        return acc + d
    }

    /**
     * Inverse of [fromZxy]. Returns an IntArray `[z, x, y]`.
     * @throws IllegalArgumentException for negative ids or ids beyond zoom [MAX_ZOOM].
     */
    fun toZxy(id: Long): IntArray {
        require(id >= 0L) { "negative tile id: $id" }
        // zoom = floor((bitLength(3*id + 1) - 1) / 2), as in IDToZxy.
        val z = (63 - (3L * id + 1L).countLeadingZeroBits()) / 2
        require(z <= MAX_ZOOM) { "tile id beyond z$MAX_ZOOM: $id" }
        val acc = zoomStart(z)
        var t = id - acc
        var tx = 0L
        var ty = 0L
        for (a in 0 until z) {
            val s = 1L shl a
            val rx = 1L and (t shr 1)
            val ry = 1L and (t xor rx)
            // rotate(s, tx, ty, rx, ry)
            if (ry == 0L) {
                if (rx != 0L) {
                    tx = s - 1L - tx
                    ty = s - 1L - ty
                }
                val tmp = tx
                tx = ty
                ty = tmp
            }
            tx += rx shl a
            ty += ry shl a
            t = t shr 2
        }
        return intArrayOf(z, tx.toInt(), ty.toInt())
    }
}
