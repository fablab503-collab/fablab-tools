package com.fablab503.velotrack.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite schema for recorded tracks, their points and imported routes.
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema; nothing to migrate yet.
    }

    companion object {
        const val DB_NAME = "velotrack.db"
        const val DB_VERSION = 1

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
    }
}
