package com.fablab503.velotrack.download

/**
 * The four detail bands. Each band is its own MBTiles file (`band<N>.mbtiles` in the maps
 * directory) declaring only its own zoom range, so MapLibre overzooms it wherever a finer band has
 * no data instead of drawing blank tiles.
 */
object Bands {

    data class Band(val index: Int, val minZoom: Int, val maxZoom: Int, val fileName: String) {
        /** Human readable zoom range, e.g. `z13–15`. */
        val zoomLabel: String get() = "z$minZoom–$maxZoom"
    }

    /** 0: z0–6 (world), 1: z7–9 (region), 2: z10–12 (roads), 3: z13–15 (streets). */
    val ALL: List<Band> = listOf(
        Band(0, 0, 6, "band0.mbtiles"),
        Band(1, 7, 9, "band1.mbtiles"),
        Band(2, 10, 12, "band2.mbtiles"),
        Band(3, 13, 15, "band3.mbtiles"),
    )

    /** Coarsest band (whole world). */
    val WORLD: Band get() = ALL[0]

    /** Finest band (streets, 10 km). */
    val STREETS: Band get() = ALL[3]

    /**
     * Band for a radius: 10 km → band 3, 100 km → band 2, 1000 km → band 1, 10 000 km → band 0.
     * Thresholds sit at the geometric midpoints so nearby values map to the intended band.
     */
    fun forRadiusKm(km: Double): Band = when {
        km <= 31.6 -> ALL[3]
        km <= 316.0 -> ALL[2]
        km <= 3162.0 -> ALL[1]
        else -> ALL[0]
    }

    /** Band whose zoom range contains [z], or null for z > 15. */
    fun forZoom(z: Int): Band? = ALL.firstOrNull { z >= it.minZoom && z <= it.maxZoom }

    /** Band by index, or null when out of range. */
    fun byIndex(index: Int): Band? = ALL.getOrNull(index)
}
