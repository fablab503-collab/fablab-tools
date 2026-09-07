package com.fablab503.velotrack.model

/**
 * A saved place. [FavoriteKind.HOME] and [FavoriteKind.WORK] exist at most once each (upsert by
 * kind); every other kind may have any number of rows.
 */
data class Favorite(
    val id: Long,
    val name: String,
    val description: String,
    val kind: FavoriteKind,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
) {
    val latLon: LatLon get() = LatLon(lat, lon)
}

/** Kind of a favourite; [key] is the value stored in the `favorites.kind` column. */
enum class FavoriteKind(val key: String) {
    HOME("home"),
    WORK("work"),
    PERSON("person"),
    RESTAURANT("restaurant"),
    THEATRE("theatre"),
    PLACE("place");

    companion object {
        /** Maps a stored key back to its kind; unknown keys fall back to [PLACE]. */
        fun fromKey(key: String): FavoriteKind = entries.firstOrNull { it.key == key } ?: PLACE
    }
}
