package com.fablab503.velotrack.download

import com.fablab503.velotrack.pmtiles.Mercator
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sinh
import kotlin.math.tan

/**
 * One piece of a download that is too large to plan in a single pass.
 *
 * [col] runs west to east and [row] runs **north to south**, so (0, 0) is the north-west corner and
 * reading the pieces in order reads them the way a map is read. The UI turns these into names; this
 * file deliberately produces no text, because a compass direction is a presentation decision and a
 * bounding box is not.
 */
data class AreaPart(
    val col: Int,
    val row: Int,
    val cols: Int,
    val rows: Int,
    val bbox: Mercator.BBox,
    val gridCells: Long,
)

/**
 * Splits [bbox] into the smallest grid of near-square pieces where every piece stays under
 * [budgetCells] grid cells, so each one can be planned by [PmTilesRemote.plan] without risking the
 * heap that [AreaTooLargeException] exists to protect.
 *
 * Two rules decide the grid, in this order:
 *
 * 1. **Every** piece must fit, not the average. A country is not evenly covered, and a split that
 *    fits on average still fails on its densest piece.
 * 2. Pieces should be roughly square. Nine horizontal strips across France also satisfies rule 1,
 *    and is useless: a rider picks the piece they ride in by pointing at it, and nobody thinks of
 *    where they live as "the fourth horizontal band of France".
 *
 * The latitude split is even in **Mercator Y**, not in degrees. Degrees would give a piece near the
 * pole far more tiles than one near the equator - the whole point of the exercise is equal work per
 * piece, and in a Mercator archive that means equal Y.
 *
 * Longitude is interpolated in the raw coordinate space, so a box written with `east > 180` for the
 * antimeridian (Russia, Fiji, Kiribati - see [Countries]) splits correctly and each piece keeps the
 * same convention.
 *
 * Returns a single whole-area part when the area already fits.
 */
fun splitForBudget(bbox: Mercator.BBox, zMin: Int, zMax: Int, budgetCells: Long): List<AreaPart> {
    val total = gridTileCount(bbox, zMin, zMax)
    if (total <= budgetCells || budgetCells <= 0L) {
        return listOf(AreaPart(0, 0, 1, 1, bbox, total))
    }

    // At least this many pieces are needed however they are arranged; no point trying fewer.
    val minPieces = max(2, ceil(total.toDouble() / budgetCells).toInt())
    var best: Triple<Int, Int, Double>? = null // cols, rows, aspect
    var cols = 1
    while (cols <= MAX_SIDE) {
        var rows = 1
        while (rows <= MAX_SIDE) {
            if (cols * rows >= minPieces && fits(bbox, zMin, zMax, cols, rows, budgetCells)) {
                val aspect = pieceAspect(bbox, cols, rows, zMax)
                val current = best
                val better = current == null ||
                    cols * rows < current.first * current.second ||
                    (cols * rows == current.first * current.second && aspect < current.third)
                if (better) best = Triple(cols, rows, aspect)
                break // more rows at this many columns only makes more pieces
            }
            rows++
        }
        cols++
    }

    val (c, r, _) = best ?: return listOf(AreaPart(0, 0, 1, 1, bbox, total))
    return build(bbox, zMin, zMax, c, r)
}

private fun fits(b: Mercator.BBox, zMin: Int, zMax: Int, cols: Int, rows: Int, budget: Long): Boolean {
    for (i in 0 until cols) {
        for (j in 0 until rows) {
            if (gridTileCount(piece(b, i, j, cols, rows), zMin, zMax) > budget) return false
        }
    }
    return true
}

private fun build(b: Mercator.BBox, zMin: Int, zMax: Int, cols: Int, rows: Int): List<AreaPart> {
    val parts = ArrayList<AreaPart>(cols * rows)
    for (j in 0 until rows) {
        for (i in 0 until cols) {
            val box = piece(b, i, j, cols, rows)
            parts.add(AreaPart(i, j, cols, rows, box, gridTileCount(box, zMin, zMax)))
        }
    }
    return parts
}

/**
 * Piece ([col], [row]) of a [cols] x [rows] grid; row 0 is the northernmost.
 *
 * Longitudes are shifted back under 180 when the whole piece lies beyond it. The `east = true + 360`
 * convention describes a *box that crosses* the antimeridian, and [Mercator.lonToTileX] clamps
 * anything past 180 to the last column of the world - so a piece cut entirely out of Russia's far
 * east would silently collapse onto x = 8191 and no grid would ever fit. A piece that genuinely
 * straddles 180 keeps the convention, because that is what it is for.
 */
private fun piece(b: Mercator.BBox, col: Int, row: Int, cols: Int, rows: Int): Mercator.BBox {
    var west = b.west + (b.east - b.west) * col / cols
    var east = b.west + (b.east - b.west) * (col + 1) / cols
    if (west >= 180.0) {
        west -= 360.0
        east -= 360.0
    }
    val yNorth = mercatorY(b.north)
    val ySouth = mercatorY(b.south)
    // Y grows southwards, so row 0 starts at the north edge.
    val yTop = yNorth + (ySouth - yNorth) * row / rows
    val yBottom = yNorth + (ySouth - yNorth) * (row + 1) / rows
    return Mercator.BBox(west, mercatorLat(yBottom), east, mercatorLat(yTop))
}

/** How far from square one piece is at [z], as a ratio >= 1. Used only to choose between grids. */
private fun pieceAspect(b: Mercator.BBox, cols: Int, rows: Int, z: Int): Double {
    val n = 1 shl z
    val width = abs(b.east - b.west) / 360.0 * n / cols
    val height = abs(mercatorY(b.south) - mercatorY(b.north)) * n / rows
    if (width <= 0.0 || height <= 0.0) return Double.MAX_VALUE
    return max(width / height, height / width)
}

/** Normalised Mercator Y in 0..1, 0 at the north pole. */
private fun mercatorY(lat: Double): Double {
    val clamped = lat.coerceIn(-MAX_LAT, MAX_LAT)
    val r = Math.toRadians(clamped)
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0
}

private fun mercatorLat(y: Double): Double =
    Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * y))))

/** The Web Mercator cut-off, the same latitude [Mercator] itself clamps to. */
private const val MAX_LAT = 85.05112878

/**
 * Russia at the finest band needs about 657 pieces, so the search has to reach a side of 26 or so.
 * Forty is comfortably past anything a real country needs and keeps the search bounded.
 */
private const val MAX_SIDE = 40
