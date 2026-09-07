package com.fablab503.velotrack.download

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteStatement
import android.os.Build
import com.fablab503.velotrack.pmtiles.Mercator
import java.io.Closeable
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Appends tiles to a band MBTiles file. The file is kept in WAL mode with `synchronous=NORMAL`
 * so MapLibre (another process) can read while we write; rows are `INSERT OR REPLACE`d in
 * transactions of ~1000 rows / 16 MiB.
 *
 * Android binds SQLite transactions to the calling thread, so every operation runs on one private
 * writer thread; the public methods are safe to call from any thread (they block until done).
 */
class MbtilesWriter private constructor(
    private val db: SQLiteDatabase,
    private val executor: ExecutorService,
) : Closeable {

    private var insertStatement: SQLiteStatement? = null
    private var metaStatement: SQLiteStatement? = null
    private var rowsInTx = 0
    private var bytesInTx = 0L

    @Volatile
    private var closed = false

    /** Stores an XYZ-addressed tile ([y] top-down) as a TMS row; the gzip MVT blob is stored as-is. */
    fun putTile(z: Int, x: Int, y: Int, gzipMvt: ByteArray) {
        val tmsRow = ((1L shl z) - 1L - y).toInt()
        putTileTms(z, x, tmsRow, gzipMvt)
    }

    /** Stores a row already in MBTiles (TMS) convention, e.g. when copying from another MBTiles. */
    fun putTileTms(z: Int, x: Int, tmsRow: Int, data: ByteArray) {
        exec { insertLocked(z, x, tmsRow, data) }
    }

    /** Commits the open batch transaction, if any. */
    fun commit() {
        exec { commitLocked() }
    }

    /** Replaces metadata rows (`INSERT OR REPLACE`). */
    fun putMetadata(values: Map<String, String>) {
        exec {
            commitLocked()
            db.beginTransactionNonExclusive()
            try {
                val stmt = metaStatement ?: db.compileStatement(SQL_UPSERT_META).also { metaStatement = it }
                for ((name, value) in values) {
                    stmt.clearBindings()
                    stmt.bindString(1, name)
                    stmt.bindString(2, value)
                    stmt.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Deletes every tile of zooms `[zMin, zMax]` whose (x, y) lies inside [b]. */
    fun deleteInBBox(b: Mercator.BBox, zMin: Int, zMax: Int) {
        exec {
            commitLocked()
            db.beginTransactionNonExclusive()
            try {
                for (z in zMin..zMax) {
                    if (z < 0 || z > MAX_ZOOM) continue
                    val (xRanges, yRange) = Mercator.tileRanges(b, z)
                    val n = (1L shl z)
                    val rowLo = n - 1L - yRange.last
                    val rowHi = n - 1L - yRange.first
                    for (xr in xRanges) {
                        db.execSQL(
                            SQL_DELETE_BOX,
                            arrayOf<Any>(z, xr.first, xr.last, rowLo, rowHi),
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Removes every tile and compacts the file (VACUUM is best-effort: it needs no other writer). */
    fun clearTiles() {
        exec {
            commitLocked()
            db.execSQL("DELETE FROM tiles")
            try {
                db.execSQL("VACUUM")
            } catch (e: SQLiteException) {
                // A concurrent reader snapshot can block VACUUM; the pages are reused by later downloads.
            }
        }
    }

    fun tileCount(): Long = exec {
        commitLocked()
        DatabaseUtils.queryNumEntries(db, "tiles")
    }

    /** Commits, checkpoints the WAL (`wal_checkpoint(TRUNCATE)`) and closes the database. */
    override fun close() {
        if (closed) return
        try {
            exec {
                try {
                    commitLocked()
                } finally {
                    insertStatement?.close()
                    insertStatement = null
                    metaStatement?.close()
                    metaStatement = null
                    try {
                        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    } catch (e: SQLiteException) {
                        // Checkpoint is an optimisation; the WAL is replayed on the next open.
                    }
                    db.close()
                }
            }
        } finally {
            closed = true
            executor.shutdown()
        }
    }

    // ---- writer-thread internals (only called via exec) ------------------------------------------

    private fun insertLocked(z: Int, x: Int, tmsRow: Int, data: ByteArray) {
        if (rowsInTx == 0) db.beginTransactionNonExclusive()
        val stmt = insertStatement ?: db.compileStatement(SQL_INSERT_TILE).also { insertStatement = it }
        stmt.clearBindings()
        stmt.bindLong(1, z.toLong())
        stmt.bindLong(2, x.toLong())
        stmt.bindLong(3, tmsRow.toLong())
        stmt.bindBlob(4, data)
        stmt.executeInsert()
        rowsInTx++
        bytesInTx += data.size
        if (rowsInTx >= TX_MAX_ROWS || bytesInTx >= TX_MAX_BYTES) commitLocked()
    }

    private fun commitLocked() {
        if (rowsInTx == 0) return
        try {
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            rowsInTx = 0
            bytesInTx = 0L
        }
    }

    private fun <T> exec(block: () -> T): T {
        check(!closed) { "MbtilesWriter is closed" }
        return awaitFuture(executor.submit(Callable<T> { block() }))
    }

    companion object {
        const val WORLD_BOUNDS = "-180,-85.0511,180,85.0511"
        const val ATTRIBUTION = "© OpenStreetMap contributors"
        const val MAX_ZOOM = 30

        private const val THREAD_NAME = "MbtilesWriter"
        private const val TX_MAX_ROWS = 1000
        private const val TX_MAX_BYTES = 16L shl 20
        private const val OPEN_FLAGS = SQLiteDatabase.OPEN_READWRITE or
            SQLiteDatabase.CREATE_IF_NECESSARY or
            SQLiteDatabase.NO_LOCALIZED_COLLATORS

        private const val SQL_INSERT_TILE =
            "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)"
        private const val SQL_UPSERT_META =
            "INSERT OR REPLACE INTO metadata (name, value) VALUES (?, ?)"
        private const val SQL_DELETE_BOX =
            "DELETE FROM tiles WHERE zoom_level = ? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?"

        /**
         * Opens (creating when missing) the band file, ensures the MBTiles schema and writes the
         * band metadata: `format=pbf`, `minzoom`, `maxzoom`, world `bounds`, `center` and the
         * `json` row with `vector_layers` ([vectorLayersJson] may be the full
         * `{"vector_layers":[…]}` object or just the array).
         */
        fun open(file: File, minZoom: Int, maxZoom: Int, vectorLayersJson: String): MbtilesWriter {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME) }
            try {
                return awaitFuture(
                    executor.submit(
                        Callable<MbtilesWriter> {
                            val db = openDatabase(file)
                            try {
                                initSchema(db, minZoom, maxZoom, vectorLayersJson)
                            } catch (t: Throwable) {
                                db.close()
                                throw t
                            }
                            MbtilesWriter(db, executor)
                        },
                    ),
                )
            } catch (t: Throwable) {
                executor.shutdown()
                throw t
            }
        }

        private fun openDatabase(file: File): SQLiteDatabase {
            file.parentFile?.mkdirs()
            val db = if (Build.VERSION.SDK_INT >= 28) {
                val params = SQLiteDatabase.OpenParams.Builder()
                    .setOpenFlags(OPEN_FLAGS)
                    .setJournalMode("WAL")         // JOURNAL_MODE_WAL constant only exists from API 33
                    .setSynchronousMode("NORMAL")  // SYNC_MODE_NORMAL constant only exists from API 33
                    .build()
                SQLiteDatabase.openDatabase(file, params)
            } else {
                SQLiteDatabase.openDatabase(file.path, null, OPEN_FLAGS).also {
                    // execSQL("PRAGMA journal_mode=WAL") throws on API 26/27 (returns a row).
                    it.enableWriteAheadLogging()
                    it.execSQL("PRAGMA synchronous=NORMAL")
                }
            }
            try {
                db.execSQL("PRAGMA cache_size=-16384") // 16 MiB page cache for the bulk load
            } catch (e: SQLiteException) {
                // Optional tuning only.
            }
            return db
        }

        private fun initSchema(db: SQLiteDatabase, minZoom: Int, maxZoom: Int, vectorLayersJson: String) {
            db.beginTransactionNonExclusive()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT NOT NULL, value TEXT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL, " +
                        "tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
                val meta = linkedMapOf(
                    "name" to "VeloTrack band z$minZoom-$maxZoom",
                    "format" to "pbf",
                    "type" to "baselayer",
                    "version" to "1",
                    "minzoom" to minZoom.toString(),
                    "maxzoom" to maxZoom.toString(),
                    "bounds" to WORLD_BOUNDS,
                    "center" to "0,0,$minZoom",
                    "attribution" to ATTRIBUTION,
                    "json" to normalizeVectorLayers(vectorLayersJson),
                )
                for ((name, value) in meta) {
                    db.execSQL(SQL_UPSERT_META, arrayOf<Any>(name, value))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        /** Accepts `{"vector_layers":[…]}` or a bare `[…]`; anything else becomes an empty layer list. */
        fun normalizeVectorLayers(json: String): String {
            val t = json.trim()
            return when {
                t.startsWith("[") -> "{\"vector_layers\":$t}"
                t.startsWith("{") -> t
                else -> "{\"vector_layers\":[]}"
            }
        }

        private fun <T> awaitFuture(future: Future<T>): T = try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
