package com.fablab503.velotrack.storage

import android.content.ContentValues
import android.database.Cursor
import com.fablab503.velotrack.model.Favorite
import com.fablab503.velotrack.model.FavoriteKind

/**
 * Data access for the `favorites` table.
 * All methods are synchronous and may be called from any thread (call them on Dispatchers.IO).
 */
class FavoritesRepository(private val db: TrackDatabase) {

    /** Home first, then Work, then every other favourite by name (case-insensitive), then id. */
    fun list(): List<Favorite> {
        val result = ArrayList<Favorite>()
        db.readableDatabase.rawQuery(
            "$SQL_SELECT ORDER BY CASE kind WHEN ? THEN 0 WHEN ? THEN 1 ELSE 2 END, " +
                "name COLLATE NOCASE ASC, id ASC",
            arrayOf(FavoriteKind.HOME.key, FavoriteKind.WORK.key),
        ).use { c ->
            while (c.moveToNext()) result.add(read(c))
        }
        return result
    }

    fun get(id: Long): Favorite? =
        db.readableDatabase.rawQuery(
            "$SQL_SELECT WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) read(c) else null }

    /** The single row of a fixed kind (HOME / WORK); for other kinds the oldest row, or null. */
    fun getByKind(kind: FavoriteKind): Favorite? =
        db.readableDatabase.rawQuery(
            "$SQL_SELECT WHERE kind = ? ORDER BY id ASC LIMIT 1",
            arrayOf(kind.key),
        ).use { c -> if (c.moveToFirst()) read(c) else null }

    /** Replaces any existing row of [kind] (HOME / WORK) with a fresh one; description is ''. */
    fun upsertFixed(kind: FavoriteKind, name: String, lat: Double, lon: Double): Favorite {
        val w = db.writableDatabase
        val createdAt = System.currentTimeMillis()
        w.beginTransaction()
        try {
            w.delete(TrackDatabase.TABLE_FAVORITES, "kind = ?", arrayOf(kind.key))
            val id = w.insertOrThrow(TrackDatabase.TABLE_FAVORITES, null, values(name, "", kind, lat, lon, createdAt))
            w.setTransactionSuccessful()
            return Favorite(id, name, "", kind, lat, lon, createdAt)
        } finally {
            w.endTransaction()
        }
    }

    fun add(name: String, description: String, kind: FavoriteKind, lat: Double, lon: Double): Favorite {
        val createdAt = System.currentTimeMillis()
        val id = db.writableDatabase.insertOrThrow(
            TrackDatabase.TABLE_FAVORITES,
            null,
            values(name, description, kind, lat, lon, createdAt),
        )
        return Favorite(id, name, description, kind, lat, lon, createdAt)
    }

    /** Writes name, description, kind and position of [fav]; `created_at` is left unchanged. */
    fun update(fav: Favorite) {
        val values = ContentValues().apply {
            put("name", fav.name)
            put("description", fav.description)
            put("kind", fav.kind.key)
            put("lat", fav.lat)
            put("lon", fav.lon)
        }
        db.writableDatabase.update(TrackDatabase.TABLE_FAVORITES, values, "id = ?", arrayOf(fav.id.toString()))
    }

    fun delete(id: Long) {
        db.writableDatabase.delete(TrackDatabase.TABLE_FAVORITES, "id = ?", arrayOf(id.toString()))
    }

    private fun values(
        name: String,
        description: String,
        kind: FavoriteKind,
        lat: Double,
        lon: Double,
        createdAt: Long,
    ): ContentValues = ContentValues().apply {
        put("name", name)
        put("description", description)
        put("kind", kind.key)
        put("lat", lat)
        put("lon", lon)
        put("created_at", createdAt)
    }

    private fun read(c: Cursor): Favorite = Favorite(
        id = c.getLong(0),
        name = c.getString(1),
        description = c.getString(2) ?: "",
        kind = FavoriteKind.fromKey(c.getString(3)),
        lat = c.getDouble(4),
        lon = c.getDouble(5),
        createdAt = c.getLong(6),
    )

    private companion object {
        const val SQL_SELECT =
            "SELECT id, name, description, kind, lat, lon, created_at FROM ${TrackDatabase.TABLE_FAVORITES}"
    }
}
