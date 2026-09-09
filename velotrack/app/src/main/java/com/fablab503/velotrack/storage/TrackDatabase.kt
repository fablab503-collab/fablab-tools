package com.fablab503.velotrack.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite schema for recorded tracks, their points, imported routes, map regions and favourites.
 * WAL mode lets the recording service insert batches while the UI reads without blocking.
 *
 * Use [TrackDatabase.get]: one helper per process avoids competing connections that fight over
 * the journal mode ("could not change the database journal mode ... database is locked").
 */
class TrackDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TRACKS)
        db.execSQL(CREATE_POINTS)
        db.execSQL(CREATE_ROUTES)
        db.execSQL(CREATE_MAP_REGIONS)
        db.execSQL(CREATE_FAVORITES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 2: downloaded / imported map regions (one row per detail band filled).
            db.execSQL(CREATE_MAP_REGIONS)
        }
        if (oldVersion < 3) {
            // Version 3: favourite places (Home, Work and custom favourites).
            db.execSQL(CREATE_FAVORITES)
        }
        if (oldVersion == 3) {
            // Version 4: an optional emoji to draw on the map marker instead of the kind's icon.
            // Only needed when favorites already existed without it -- an upgrade from before
            // version 3 just created the table fresh, above, already including this column.
            db.execSQL("ALTER TABLE favorites ADD COLUMN emoji TEXT")
        }
    }

    companion object {
        const val DB_NAME = "velotrack.db"
        const val DB_VERSION = 4

        @Volatile
        private var instance: TrackDatabase? = null

        /** Process-wide helper; never close it. */
        fun get(context: Context): TrackDatabase =
            instance ?: synchronized(this) {
                instance ?: TrackDatabase(context).also { instance = it }
            }

        const val TABLE_TRACKS = "tracks"
        const val TABLE_POINTS = "points"
        const val TABLE_ROUTES = "routes"
        const val TABLE_MAP_REGIONS = "map_regions"
        const val TABLE_FAVORITES = "favorites"

        private const val CREATE_TRACKS = """
            CREATE TABLE tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                distance_m REAL NOT NULL DEFAULT 0,
                moving_ms INTEGER NOT NULL DEFAULT 0,
                elevation_gain_m REAL NOT NULL DEFAULT 0,
                elevation_loss_m REAL NOT NULL DEFAULT 0,
                max_speed_mps REAL NOT NULL DEFAULT 0,
                state TEXT NOT NULL
            )
        """

        private const val CREATE_POINTS = """
            CREATE TABLE points (
                track_id INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                time_ms INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                ele REAL,
                speed REAL,
                accuracy REAL NOT NULL,
                segment INTEGER NOT NULL,
                PRIMARY KEY (track_id, seq)
            )
        """

        private const val CREATE_ROUTES = """
            CREATE TABLE routes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                distance_m REAL NOT NULL,
                point_count INTEGER NOT NULL,
                file TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """

        /**
         * One row per (download or import) × detail band. `band` is the index into
         * `download.Bands.ALL`; the bbox is the requested area; `build` is the Protomaps build key
         * (e.g. `20260907.pmtiles`) or `import`.
         */
        private const val CREATE_MAP_REGIONS = """
            CREATE TABLE IF NOT EXISTS map_regions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                band INTEGER NOT NULL,
                west REAL NOT NULL,
                south REAL NOT NULL,
                east REAL NOT NULL,
                north REAL NOT NULL,
                min_zoom INTEGER NOT NULL,
                max_zoom INTEGER NOT NULL,
                tiles INTEGER NOT NULL DEFAULT 0,
                bytes INTEGER NOT NULL DEFAULT 0,
                build TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
        """

        /**
         * Saved places. `kind` is a `model.FavoriteKind` key; `home` and `work` have at most one row
         * each (see `FavoritesRepository.upsertFixed`).
         */
        private const val CREATE_FAVORITES = """
            CREATE TABLE IF NOT EXISTS favorites (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                created_at INTEGER NOT NULL,
                emoji TEXT
            )
        """
    }
}
