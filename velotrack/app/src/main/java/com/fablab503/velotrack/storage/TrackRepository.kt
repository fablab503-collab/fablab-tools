package com.fablab503.velotrack.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fablab503.velotrack.model.RideStatsSnapshot
import com.fablab503.velotrack.model.TrackPoint
import com.fablab503.velotrack.model.TrackSummary

/**
 * Data access for the `tracks` and `points` tables.
 * All methods are synchronous and may be called from any thread; SQLite serialises access and
 * WAL mode lets the recording service write while the UI reads.
 */
class TrackRepository(private val db: TrackDatabase) {

    fun createTrack(name: String, startedAtMs: Long): Long {
        val values = ContentValues().apply {
            put("name", name)
            put("started_at", startedAtMs)
            put("state", TrackSummary.STATE_RECORDING)
        }
        return db.writableDatabase.insertOrThrow(TrackDatabase.TABLE_TRACKS, null, values)
    }

    /** Inserts all points in one transaction; `seq` continues from the current max(seq) + 1. */
    fun appendPoints(trackId: Long, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            var seq = nextSeq(w, trackId)
            w.compileStatement(SQL_INSERT_POINT).use { stmt ->
                for (p in points) {
                    stmt.clearBindings()
                    stmt.bindLong(1, trackId)
                    stmt.bindLong(2, seq)
                    stmt.bindLong(3, p.timeMs)
                    stmt.bindDouble(4, p.lat)
                    stmt.bindDouble(5, p.lon)
                    val ele = p.ele
                    if (ele != null) stmt.bindDouble(6, ele) else stmt.bindNull(6)
                    val speed = p.speedMps
                    if (speed != null) stmt.bindDouble(7, speed.toDouble()) else stmt.bindNull(7)
                    stmt.bindDouble(8, p.accuracyM.toDouble())
                    stmt.bindLong(9, p.segment.toLong())
                    stmt.executeInsert()
                    seq++
                }
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun updateStats(trackId: Long, stats: RideStatsSnapshot) {
        db.writableDatabase.update(
            TrackDatabase.TABLE_TRACKS,
            statsValues(stats),
            "id = ?",
            arrayOf(trackId.toString()),
        )
    }

    fun finishTrack(trackId: Long, stats: RideStatsSnapshot, finishedAtMs: Long) {
        val values = statsValues(stats).apply {
            put("finished_at", finishedAtMs)
            put("state", TrackSummary.STATE_FINISHED)
        }
        db.writableDatabase.update(TrackDatabase.TABLE_TRACKS, values, "id = ?", arrayOf(trackId.toString()))
    }

    fun renameTrack(trackId: Long, name: String) {
        val values = ContentValues().apply { put("name", name) }
        db.writableDatabase.update(TrackDatabase.TABLE_TRACKS, values, "id = ?", arrayOf(trackId.toString()))
    }

    fun deleteTrack(trackId: Long) {
        val w = db.writableDatabase
        val args = arrayOf(trackId.toString())
        w.beginTransaction()
        try {
            w.delete(TrackDatabase.TABLE_POINTS, "track_id = ?", args)
            w.delete(TrackDatabase.TABLE_TRACKS, "id = ?", args)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** All tracks, newest first. */
    fun listTracks(): List<TrackSummary> {
        val result = ArrayList<TrackSummary>()
        db.readableDatabase.rawQuery(
            "$SQL_SELECT_SUMMARY ORDER BY t.started_at DESC, t.id DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) result.add(readSummary(c))
        }
        return result
    }

    fun getTrack(trackId: Long): TrackSummary? =
        db.readableDatabase.rawQuery(
            "$SQL_SELECT_SUMMARY WHERE t.id = ?",
            arrayOf(trackId.toString()),
        ).use { c -> if (c.moveToFirst()) readSummary(c) else null }

    /** The most recent track still in state `recording`, or null. */
    fun findUnfinished(): TrackSummary? =
        db.readableDatabase.rawQuery(
            "$SQL_SELECT_SUMMARY WHERE t.state = ? ORDER BY t.started_at DESC, t.id DESC LIMIT 1",
            arrayOf(TrackSummary.STATE_RECORDING),
        ).use { c -> if (c.moveToFirst()) readSummary(c) else null }

    fun pointCount(trackId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${TrackDatabase.TABLE_POINTS} WHERE track_id = ?",
            arrayOf(trackId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Highest stored segment index, 0 when the track has no points. */
    fun lastSegment(trackId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(segment), 0) FROM ${TrackDatabase.TABLE_POINTS} WHERE track_id = ?",
            arrayOf(trackId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun loadPoints(trackId: Long): List<TrackPoint> {
        val result = ArrayList<TrackPoint>()
        forEachPoint(trackId) { result.add(it) }
        return result
    }

    /** Streams every point in seq order through a single cursor (for GPX export). */
    fun forEachPoint(trackId: Long, block: (TrackPoint) -> Unit) {
        db.readableDatabase.rawQuery(
            "$SQL_SELECT_POINT WHERE track_id = ? ORDER BY seq",
            arrayOf(trackId.toString()),
        ).use { c ->
            while (c.moveToNext()) block(readPoint(c))
        }
    }

    /** Lazily loads the points in chunks of [CHUNK_SIZE] rows, ordered by seq. */
    fun pointsAsSequence(trackId: Long): Sequence<TrackPoint> = sequence {
        var fromSeq = 0L
        while (true) {
            val chunk = ArrayList<TrackPoint>(CHUNK_SIZE)
            var lastSeq = fromSeq
            db.readableDatabase.rawQuery(
                "$SQL_SELECT_POINT WHERE track_id = ? AND seq >= ? ORDER BY seq LIMIT $CHUNK_SIZE",
                arrayOf(trackId.toString(), fromSeq.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    chunk.add(readPoint(c))
                    lastSeq = c.getLong(COL_SEQ)
                }
            }
            if (chunk.isEmpty()) break
            yieldAll(chunk)
            if (chunk.size < CHUNK_SIZE) break
            fromSeq = lastSeq + 1
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun nextSeq(w: SQLiteDatabase, trackId: Long): Long =
        w.rawQuery(
            "SELECT COALESCE(MAX(seq), -1) + 1 FROM ${TrackDatabase.TABLE_POINTS} WHERE track_id = ?",
            arrayOf(trackId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun statsValues(stats: RideStatsSnapshot): ContentValues = ContentValues().apply {
        put("distance_m", stats.distanceM)
        put("moving_ms", stats.movingMs)
        put("elevation_gain_m", stats.elevationGainM)
        put("elevation_loss_m", stats.elevationLossM)
        put("max_speed_mps", stats.maxSpeedMps)
    }

    private fun readSummary(c: Cursor): TrackSummary = TrackSummary(
        id = c.getLong(0),
        name = c.getString(1) ?: "",
        startedAtMs = c.getLong(2),
        finishedAtMs = if (c.isNull(3)) null else c.getLong(3),
        distanceM = c.getDouble(4),
        movingMs = c.getLong(5),
        elevationGainM = c.getDouble(6),
        state = c.getString(7) ?: TrackSummary.STATE_FINISHED,
        pointCount = c.getInt(8),
    )

    private fun readPoint(c: Cursor): TrackPoint = TrackPoint(
        timeMs = c.getLong(COL_TIME_MS),
        lat = c.getDouble(COL_LAT),
        lon = c.getDouble(COL_LON),
        ele = if (c.isNull(COL_ELE)) null else c.getDouble(COL_ELE),
        speedMps = if (c.isNull(COL_SPEED)) null else c.getFloat(COL_SPEED),
        accuracyM = c.getFloat(COL_ACCURACY),
        segment = c.getInt(COL_SEGMENT),
    )

    companion object {
        const val CHUNK_SIZE = 5000

        private const val SQL_INSERT_POINT =
            "INSERT INTO points (track_id, seq, time_ms, lat, lon, ele, speed, accuracy, segment) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        // Column order for readSummary: 0 id, 1 name, 2 started_at, 3 finished_at, 4 distance_m,
        // 5 moving_ms, 6 elevation_gain_m, 7 state, 8 point_count
        private const val SQL_SELECT_SUMMARY =
            "SELECT t.id, t.name, t.started_at, t.finished_at, t.distance_m, t.moving_ms, " +
                "t.elevation_gain_m, t.state, " +
                "(SELECT COUNT(*) FROM points p WHERE p.track_id = t.id) AS point_count " +
                "FROM tracks t"

        // Column order for readPoint (indices below).
        private const val SQL_SELECT_POINT =
            "SELECT seq, time_ms, lat, lon, ele, speed, accuracy, segment FROM points"
        private const val COL_SEQ = 0
        private const val COL_TIME_MS = 1
        private const val COL_LAT = 2
        private const val COL_LON = 3
        private const val COL_ELE = 4
        private const val COL_SPEED = 5
        private const val COL_ACCURACY = 6
        private const val COL_SEGMENT = 7
    }
}
