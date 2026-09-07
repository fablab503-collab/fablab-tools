package com.fablab503.velotrack.download

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.OpenableColumns
import com.fablab503.velotrack.pmtiles.Mercator
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.TrackDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The on-device map library: the four band MBTiles files in the maps directory plus the
 * `map_regions` rows describing what was downloaded or imported into them.
 *
 * Reading ([listRegions], [bandSizesBytes], …) and creating missing files ([ensureBandFiles],
 * before MapLibre opens them) is fine from any process. The tile writers — [deleteRegion],
 * [clearAll], [importFile] and the downloads — must run in the `:download` process only: the UI
 * process holds the band files open through MapLibre's bundled SQLite, and a second SQLite copy
 * writing the same file in that process breaks the POSIX locks (SQLite "How to corrupt" §2.3).
 * The UI reaches them through `MapDownloadService` (`ACTION_IMPORT` / `ACTION_DELETE_REGION` /
 * `ACTION_CLEAR`) and reloads the map style on the `done` broadcast.
 */
class MapLibrary(private val context: Context, private val prefs: Prefs, private val db: TrackDatabase) {

    /** `getExternalFilesDir("maps")`, falling back to `filesDir/maps` (same directory as `MapFileStore`). */
    val dir: File = (context.getExternalFilesDir(MAPS_DIR_NAME) ?: File(context.filesDir, MAPS_DIR_NAME)).also { it.mkdirs() }

    @Volatile
    private var cachedVectorLayers: String? = null

    fun bandFile(band: Bands.Band): File = File(dir, band.fileName)

    /** All four band files in band order (they may not exist yet; see [ensureBandFiles]). */
    fun bandFiles(): List<File> = Bands.ALL.map { bandFile(it) }

    /** Creates any missing (or empty) band file with its metadata so the style always has four valid sources. */
    fun ensureBandFiles() {
        dir.mkdirs()
        val layers = vectorLayersJson()
        for (band in Bands.ALL) {
            val file = bandFile(band)
            if (file.isFile && file.length() > 0L) continue
            MbtilesWriter.open(file, band.minZoom, band.maxZoom, layers).close()
        }
    }

    /**
     * `{"vector_layers":[…]}` for the metadata `json` row, read from `assets/vector_layers.json`
     * (either the object or the bare array), or the built-in Protomaps v4 layer list.
     */
    fun vectorLayersJson(): String {
        cachedVectorLayers?.let { return it }
        val fromAssets = try {
            context.assets.open(VECTOR_LAYERS_ASSET).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: IOException) {
            null
        }
        val json = fromAssets?.let { normalizeVectorLayers(it) } ?: FALLBACK_VECTOR_LAYERS_JSON
        cachedVectorLayers = json
        return json
    }

    // ---- regions ---------------------------------------------------------------------------------

    /** All regions, newest first. */
    fun listRegions(): List<MapRegion> {
        val out = ArrayList<MapRegion>()
        db.readableDatabase.query(
            TrackDatabase.TABLE_MAP_REGIONS, null, null, null, null, null, "created_at DESC, id DESC",
        ).use { c ->
            while (c.moveToNext()) out.add(readRegion(c))
        }
        return out
    }

    fun getRegion(id: Long): MapRegion? {
        db.readableDatabase.query(
            TrackDatabase.TABLE_MAP_REGIONS, null, "id = ?", arrayOf(id.toString()), null, null, null,
        ).use { c ->
            return if (c.moveToFirst()) readRegion(c) else null
        }
    }

    /** Inserts [r] (its `id` is ignored) and returns the new row id. */
    fun addRegion(r: MapRegion): Long {
        val values = ContentValues().apply {
            put("name", r.name)
            put("band", r.band)
            put("west", r.west)
            put("south", r.south)
            put("east", r.east)
            put("north", r.north)
            put("min_zoom", r.minZoom)
            put("max_zoom", r.maxZoom)
            put("tiles", r.tiles)
            put("bytes", r.bytes)
            put("build", r.build)
            put("created_at", r.createdAt)
        }
        return db.writableDatabase.insertOrThrow(TrackDatabase.TABLE_MAP_REGIONS, null, values)
    }

    /**
     * Deletes the region row and its tiles in its band (zooms and bbox of the region) unless
     * another region of the same band still covers the whole area.
     */
    fun deleteRegion(id: Long) {
        val region = getRegion(id) ?: return
        val band = Bands.byIndex(region.band)
        if (band != null) {
            val bbox = region.bbox
            val stillCovered = listRegions().any { it.id != id && it.band == region.band && it.covers(bbox) }
            val file = bandFile(band)
            if (!stillCovered && file.isFile) {
                MbtilesWriter.open(file, band.minZoom, band.maxZoom, vectorLayersJson()).use { w ->
                    w.deleteInBBox(bbox, region.minZoom, region.maxZoom)
                }
            }
        }
        db.writableDatabase.delete(TrackDatabase.TABLE_MAP_REGIONS, "id = ?", arrayOf(id.toString()))
    }

    /** True when a region of [bandIndex] already contains [bbox]. */
    fun hasCoveringRegion(bandIndex: Int, bbox: Mercator.BBox): Boolean =
        listRegions().any { it.band == bandIndex && it.covers(bbox) }

    /** Bytes on disk per band (main file plus WAL), indexed like [Bands.ALL]. */
    fun bandSizesBytes(): LongArray = LongArray(Bands.ALL.size) { i ->
        val file = bandFile(Bands.ALL[i])
        file.length() + File(dir, file.name + WAL_SUFFIX).length()
    }

    /** Total bytes on disk of all band files. */
    fun totalSizeBytes(): Long = bandSizesBytes().sum()

    /**
     * Removes every region row and every tile from all band files (files are kept, emptied and
     * compacted, because MapLibre may hold them open; a corrupt file is deleted and recreated).
     */
    fun clearAll() {
        db.writableDatabase.delete(TrackDatabase.TABLE_MAP_REGIONS, null, null)
        val layers = vectorLayersJson()
        for (band in Bands.ALL) {
            val file = bandFile(band)
            try {
                MbtilesWriter.open(file, band.minZoom, band.maxZoom, layers).use { it.clearTiles() }
            } catch (e: SQLiteException) {
                deleteWithSideFiles(file)
                MbtilesWriter.open(file, band.minZoom, band.maxZoom, layers).close()
            }
        }
    }

    // ---- import ----------------------------------------------------------------------------------

    /**
     * Copies the tiles of an MBTiles document into the band files by zoom (rows are already TMS
     * and copied verbatim) and records one region per band that received tiles, using the file's
     * `bounds` metadata (world when absent). Runs on [Dispatchers.IO].
     *
     * [onProgress] receives `(copiedBytes, totalBytesOrNull)` while the document is streamed to a
     * temporary file, then `(tilesDone, tilesTotal)` while rows are copied.
     *
     * @throws IOException when the document cannot be read or is not an MBTiles database
     */
    suspend fun importFile(uri: Uri, onProgress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}.mbtiles")
        try {
            val (displayName, total) = queryNameAndSize(uri)
            copyToFile(uri, tmp, total, onProgress)
            val source = openSource(tmp)
            source.use { src ->
                val meta = readMetadata(src)
                val bounds = parseBounds(meta["bounds"]) ?: WORLD_BBOX
                val fileMinZoom = meta["minzoom"]?.trim()?.toIntOrNull()
                val fileMaxZoom = meta["maxzoom"]?.trim()?.toIntOrNull()
                val regionName = displayName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
                    ?: meta["name"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_IMPORT_NAME
                val tilesTotal = try {
                    DatabaseUtils.queryNumEntries(src, "tiles")
                } catch (e: SQLiteException) {
                    throw IOException("Not an MBTiles file: ${e.message}")
                }
                onProgress(0L, tilesTotal)
                var done = 0L
                val layers = vectorLayersJson()
                val now = System.currentTimeMillis()
                for (band in Bands.ALL) {
                    ensureActive()
                    var tiles = 0L
                    var bytes = 0L
                    var zMin = Int.MAX_VALUE
                    var zMax = Int.MIN_VALUE
                    val writer = MbtilesWriter.open(bandFile(band), band.minZoom, band.maxZoom, layers)
                    try {
                        src.rawQuery(
                            SQL_SELECT_TILES,
                            arrayOf(band.minZoom.toString(), band.maxZoom.toString()),
                        ).use { c ->
                            while (c.moveToNext()) {
                                if (c.isNull(3)) continue
                                val z = c.getInt(0)
                                val x = c.getInt(1)
                                val row = c.getInt(2)
                                val data = c.getBlob(3) ?: continue
                                writer.putTileTms(z, x, row, data)
                                tiles++
                                bytes += data.size
                                if (z < zMin) zMin = z
                                if (z > zMax) zMax = z
                                done++
                                if (done % PROGRESS_EVERY_TILES == 0L) {
                                    ensureActive()
                                    onProgress(done, tilesTotal)
                                }
                            }
                        }
                        writer.commit()
                    } finally {
                        writer.close()
                    }
                    if (tiles > 0L) {
                        addRegion(
                            MapRegion(
                                id = 0L,
                                name = regionName,
                                band = band.index,
                                west = bounds.west,
                                south = bounds.south,
                                east = bounds.east,
                                north = bounds.north,
                                minZoom = maxOf(zMin, fileMinZoom ?: zMin),
                                maxZoom = minOf(zMax, fileMaxZoom ?: zMax),
                                tiles = tiles,
                                bytes = bytes,
                                build = MapRegion.BUILD_IMPORT,
                                createdAt = now,
                            ),
                        )
                    }
                }
                onProgress(tilesTotal, tilesTotal)
            }
        } finally {
            deleteWithSideFiles(tmp)
        }
    }

    private fun copyToFile(uri: Uri, dest: File, total: Long?, onProgress: (Long, Long?) -> Unit) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        var copied = 0L
        var lastReported = 0L
        onProgress(0L, total)
        input.use { ins ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    copied += n
                    if (copied - lastReported >= PROGRESS_STEP_BYTES) {
                        lastReported = copied
                        onProgress(copied, total)
                    }
                }
                out.flush()
            }
        }
        if (copied == 0L) throw IOException("Document is empty")
        onProgress(copied, total)
    }

    private fun openSource(file: File): SQLiteDatabase = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    } catch (e: SQLiteException) {
        // A WAL-mode file needs -shm access; the temp copy is ours, so open it read-write instead.
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } catch (e2: SQLiteException) {
            throw IOException("Not an SQLite/MBTiles file: ${e2.message}")
        }
    }

    private fun readMetadata(src: SQLiteDatabase): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            src.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0)) continue
                    out[c.getString(0)] = if (c.isNull(1)) "" else c.getString(1)
                }
            }
        } catch (e: SQLiteException) {
            // No metadata table: fall back to defaults.
        }
        return out
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                        val s = c.getLong(sizeIdx)
                        if (s > 0L) size = s
                    }
                }
            }
        } catch (e: Exception) {
            // Some providers reject metadata queries; fall back to the URI's last segment.
        }
        return Pair(name ?: uri.lastPathSegment, size)
    }

    private fun deleteWithSideFiles(file: File) {
        file.delete()
        for (suffix in SIDE_FILE_SUFFIXES) {
            val side = File(file.parentFile ?: dir, file.name + suffix)
            if (side.exists()) side.delete()
        }
    }

    private fun readRegion(c: Cursor): MapRegion = MapRegion(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        name = c.getString(c.getColumnIndexOrThrow("name")) ?: "",
        band = c.getInt(c.getColumnIndexOrThrow("band")),
        west = c.getDouble(c.getColumnIndexOrThrow("west")),
        south = c.getDouble(c.getColumnIndexOrThrow("south")),
        east = c.getDouble(c.getColumnIndexOrThrow("east")),
        north = c.getDouble(c.getColumnIndexOrThrow("north")),
        minZoom = c.getInt(c.getColumnIndexOrThrow("min_zoom")),
        maxZoom = c.getInt(c.getColumnIndexOrThrow("max_zoom")),
        tiles = c.getLong(c.getColumnIndexOrThrow("tiles")),
        bytes = c.getLong(c.getColumnIndexOrThrow("bytes")),
        build = c.getString(c.getColumnIndexOrThrow("build")) ?: "",
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
    )

    companion object {
        const val MAPS_DIR_NAME = "maps"
        const val VECTOR_LAYERS_ASSET = "vector_layers.json"
        const val DEFAULT_IMPORT_NAME = "Imported map"

        val WORLD_BBOX = Mercator.BBox(-180.0, -85.0511, 180.0, 85.0511)

        /** Protomaps basemap v4 layer names, used when `assets/vector_layers.json` is missing. */
        val FALLBACK_LAYER_NAMES: List<String> = listOf(
            "boundaries", "buildings", "earth", "landcover", "landuse", "places", "pois", "roads", "transit", "water",
        )

        val FALLBACK_VECTOR_LAYERS_JSON: String = FALLBACK_LAYER_NAMES.joinToString(
            prefix = "{\"vector_layers\":[",
            postfix = "]}",
        ) { "{\"id\":\"$it\",\"fields\":{},\"minzoom\":0,\"maxzoom\":15}" }

        private const val WAL_SUFFIX = "-wal"
        private val SIDE_FILE_SUFFIXES = listOf("-journal", "-wal", "-shm")
        private const val COPY_BUFFER_BYTES = 1 shl 20
        private const val PROGRESS_STEP_BYTES = 4L shl 20
        private const val PROGRESS_EVERY_TILES = 100L
        private const val SQL_SELECT_TILES =
            "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE zoom_level BETWEEN ? AND ?"

        /**
         * Reduces an asset / metadata JSON to exactly `{"vector_layers":[…]}`; null when it holds
         * no layer list.
         */
        fun normalizeVectorLayers(raw: String): String? = try {
            val t = raw.trim()
            when {
                t.startsWith("[") -> JSONObject().put("vector_layers", JSONArray(t)).toString()
                t.startsWith("{") -> {
                    val obj = JSONObject(t)
                    val layers = obj.optJSONArray("vector_layers")
                    if (layers != null && layers.length() > 0) JSONObject().put("vector_layers", layers).toString() else null
                }
                else -> null
            }
        } catch (e: JSONException) {
            null
        }

        /** Parses an MBTiles `bounds` value `west,south,east,north`. */
        fun parseBounds(value: String?): Mercator.BBox? {
            if (value == null) return null
            val parts = value.split(',').map { it.trim().toDoubleOrNull() ?: return null }
            if (parts.size != 4) return null
            val (w, s, e, n) = parts
            if (w > e || s > n) return null
            return Mercator.BBox(w, s, e, n)
        }
    }
}
