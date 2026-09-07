package com.fablab503.velotrack.download

import com.fablab503.velotrack.pmtiles.Mercator

/**
 * One row of the `map_regions` table (schema v2): an area that was filled into one detail band,
 * either by a download or by importing an MBTiles file.
 *
 * @property id row id, 0 before insertion
 * @property band index into [Bands.ALL]
 * @property west,south,east,north requested bounding box in degrees (west may be < −180 or east
 *   > 180 when the area crosses the antimeridian)
 * @property minZoom,maxZoom zoom levels actually written for this band
 * @property tiles number of tiles written
 * @property bytes tile bytes written
 * @property build Protomaps build key (e.g. `20260907.pmtiles`) or [BUILD_IMPORT]
 * @property createdAt epoch milliseconds
 */
data class MapRegion(
    val id: Long,
    val name: String,
    val band: Int,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val tiles: Long,
    val bytes: Long,
    val build: String,
    val createdAt: Long,
) {
    val bbox: Mercator.BBox get() = Mercator.BBox(west, south, east, north)

    /** True when this region's area contains [b] (a region spanning ≥ 360° covers every longitude). */
    fun covers(b: Mercator.BBox): Boolean {
        val lonCovered = (east - west >= 360.0) || (west <= b.west && east >= b.east)
        return lonCovered && south <= b.south && north >= b.north
    }

    companion object {
        const val BUILD_IMPORT = "import"
    }
}
