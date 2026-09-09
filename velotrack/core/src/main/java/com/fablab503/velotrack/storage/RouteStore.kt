package com.fablab503.velotrack.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.gpx.GpxParser
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.model.RouteSummary
import com.fablab503.velotrack.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Imported GPX routes. Metadata lives in the `routes` table; the polyline is stored as a compact
 * binary file `filesDir/routes/<id>.bin` of little-endian doubles (lat, lon, lat, lon, ...).
 */
class RouteStore(private val context: Context, private val db: TrackDatabase, private val prefs: Prefs) {

    private val routesDir: File get() = File(context.filesDir, "routes")

    /**
     * Parses the GPX document at [uri], writes the polyline file and inserts the `routes` row.
     * Runs on [Dispatchers.IO].
     *
     * @throws IllegalArgumentException when the GPX is malformed or has no points.
     * @throws IOException when the document cannot be opened or the route file cannot be written.
     */
    suspend fun importGpx(uri: Uri): RouteSummary = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        val data = input.use { GpxParser.parse(it) }
        val points = data.points
        if (points.isEmpty()) throw IllegalArgumentException("Route has no points")

        var distanceM = 0.0
        for (i in 1 until points.size) distanceM += Geo.distanceM(points[i - 1], points[i])

        val name = data.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: queryDisplayName(uri)?.trim()?.removeSuffix(".gpx")?.removeSuffix(".GPX")?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_NAME

        val dir = routesDir
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create ${dir.absolutePath}")

        val w = db.writableDatabase
        var file: File? = null
        w.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("name", name)
                put("distance_m", distanceM)
                put("point_count", points.size)
                put("file", "")
                put("created_at", System.currentTimeMillis())
            }
            val id = w.insertOrThrow(TrackDatabase.TABLE_ROUTES, null, values)
            val fileName = "$id$FILE_EXT"
            val target = File(dir, fileName)
            file = target
            writePoints(target, points)
            val update = ContentValues().apply { put("file", fileName) }
            w.update(TrackDatabase.TABLE_ROUTES, update, "id = ?", arrayOf(id.toString()))
            w.setTransactionSuccessful()
            RouteSummary(
                id = id,
                name = name,
                distanceM = distanceM,
                pointCount = points.size,
                file = target.absolutePath,
            )
        } catch (t: Throwable) {
            file?.delete()
            throw t
        } finally {
            w.endTransaction()
        }
    }

    /** All routes, newest first. */
    fun listRoutes(): List<RouteSummary> {
        val result = ArrayList<RouteSummary>()
        db.readableDatabase.rawQuery(
            "$SQL_SELECT_ROUTE ORDER BY created_at DESC, id DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) result.add(readRoute(c))
        }
        return result
    }

    /** The route's polyline, or an empty list when the route or its file is missing. */
    fun loadPoints(routeId: Long): List<LatLon> {
        val route = getRoute(routeId) ?: return emptyList()
        val file = File(route.file)
        if (!file.isFile) return emptyList()
        return readPoints(file)
    }

    /** Removes the row and the polyline file; clears the active route if it was this one. */
    fun delete(routeId: Long) {
        val route = getRoute(routeId)
        if (route != null) {
            val file = File(route.file)
            if (file.exists()) file.delete()
        }
        db.writableDatabase.delete(TrackDatabase.TABLE_ROUTES, "id = ?", arrayOf(routeId.toString()))
        if (prefs.activeRouteId == routeId) setActive(null)
    }

    fun activeRoute(): RouteSummary? {
        val id = prefs.activeRouteId ?: return null
        return getRoute(id)
    }

    fun setActive(routeId: Long?) {
        prefs.activeRouteId = routeId
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun getRoute(routeId: Long): RouteSummary? =
        db.readableDatabase.rawQuery(
            "$SQL_SELECT_ROUTE WHERE id = ?",
            arrayOf(routeId.toString()),
        ).use { c -> if (c.moveToFirst()) readRoute(c) else null }

    /** Column order: 0 id, 1 name, 2 distance_m, 3 point_count, 4 file. */
    private fun readRoute(c: Cursor): RouteSummary {
        val storedFile = c.getString(4) ?: ""
        // Rows store the bare file name; resolve against the current routes directory so the
        // absolute path stays valid even if the app's data directory moves.
        val resolved = if (storedFile.isEmpty() || storedFile.startsWith("/")) storedFile else File(routesDir, storedFile).absolutePath
        return RouteSummary(
            id = c.getLong(0),
            name = c.getString(1) ?: "",
            distanceM = c.getDouble(2),
            pointCount = c.getInt(3),
            file = resolved,
        )
    }

    private fun writePoints(file: File, points: List<LatLon>) {
        FileOutputStream(file).use { out ->
            val channel = out.channel
            val buf = ByteBuffer.allocate(IO_CHUNK_POINTS * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
            for (p in points) {
                if (buf.remaining() < BYTES_PER_POINT) {
                    buf.flip()
                    while (buf.hasRemaining()) channel.write(buf)
                    buf.clear()
                }
                buf.putDouble(p.lat)
                buf.putDouble(p.lon)
            }
            buf.flip()
            while (buf.hasRemaining()) channel.write(buf)
            out.flush()
            out.getFD().sync()
        }
    }

    private fun readPoints(file: File): List<LatLon> =
        FileInputStream(file).use { ins ->
            val channel = ins.channel
            val expected = (channel.size() / BYTES_PER_POINT).toInt().coerceAtLeast(0)
            val result = ArrayList<LatLon>(expected)
            val buf = ByteBuffer.allocate(IO_CHUNK_POINTS * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
            var eof = false
            while (!eof) {
                buf.clear()
                while (buf.hasRemaining()) {
                    val n = channel.read(buf)
                    if (n < 0) {
                        eof = true
                        break
                    }
                }
                buf.flip()
                while (buf.remaining() >= BYTES_PER_POINT) {
                    val lat = buf.getDouble()
                    val lon = buf.getDouble()
                    result.add(LatLon(lat, lon))
                }
            }
            result
        }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val FILE_EXT = ".bin"
        private const val DEFAULT_NAME = "Route"
        private const val BYTES_PER_POINT = 16           // two little-endian doubles
        private const val IO_CHUNK_POINTS = 4096
        private const val SQL_SELECT_ROUTE =
            "SELECT id, name, distance_m, point_count, file FROM routes"
    }
}
