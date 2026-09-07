package com.fablab503.velotrack.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fablab503.velotrack.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Imported offline map files (`.mbtiles` / `.pmtiles`) living in the app's external files
 * directory, plus the "active map" selection kept in [Prefs].
 */
class MapFileStore(private val context: Context, private val prefs: Prefs) {

    /** `getExternalFilesDir("maps")`, falling back to `filesDir/maps` when external storage is unavailable. */
    val mapsDir: File = (context.getExternalFilesDir("maps") ?: File(context.filesDir, "maps")).also { it.mkdirs() }

    /** All `*.mbtiles` and `*.pmtiles` files in [mapsDir], sorted by name. */
    fun listMaps(): List<File> {
        val files = mapsDir.listFiles { f -> f.isFile && hasMapExtension(f.name) } ?: return emptyList()
        return files.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /** The active map file from preferences, or null when unset or no longer on disk. */
    fun activeMap(): File? {
        val path = prefs.activeMapFile ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    fun setActive(file: File?) {
        prefs.activeMapFile = file?.absolutePath
    }

    /** Deletes the file (and any SQLite side files); clears the active selection if it pointed here. */
    fun delete(file: File) {
        val wasActive = prefs.activeMapFile == file.absolutePath
        file.delete()
        for (suffix in SIDE_FILE_SUFFIXES) {
            val side = File(file.parentFile ?: mapsDir, file.name + suffix)
            if (side.exists()) side.delete()
        }
        if (wasActive) setActive(null)
    }

    /**
     * Copies the document at [uri] into [mapsDir] under a sanitised ASCII name, replacing an
     * existing file of the same name. Runs on [Dispatchers.IO]. [onProgress] receives
     * (copiedBytes, totalBytesOrNull) at most every 4 MiB plus once at start and once at the end.
     *
     * @throws IllegalArgumentException when the document is not a `.mbtiles` / `.pmtiles` file.
     * @throws IOException when the document cannot be opened or written.
     */
    suspend fun import(uri: Uri, onProgress: (Long, Long?) -> Unit): File = withContext(Dispatchers.IO) {
        val (displayName, total) = queryNameAndSize(uri)
        val name = sanitizeName(displayName ?: uri.lastPathSegment)
        mapsDir.mkdirs()
        val dest = File(mapsDir, name)
        val tmp = File(mapsDir, "$name$TMP_SUFFIX")
        val resolver = context.contentResolver
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            var copied = 0L
            var lastReported = 0L
            input.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(COPY_BUFFER_BYTES)
                    onProgress(0L, total)
                    while (true) {
                        ensureActive()
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
                    out.getFD().sync()
                }
            }
            if (copied == 0L) throw IOException("Document is empty: $name")
            if (dest.exists() && !dest.delete()) throw IOException("Cannot replace ${dest.name}")
            if (!tmp.renameTo(dest)) throw IOException("Cannot move ${tmp.name} to ${dest.name}")
            onProgress(copied, total)
            dest
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
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
                        if (s > 0) size = s
                    }
                }
            }
        } catch (e: Exception) {
            // Some providers reject metadata queries; fall back to the URI's last segment.
        }
        return Pair(name, size)
    }

    companion object {
        const val EXT_MBTILES = ".mbtiles"
        const val EXT_PMTILES = ".pmtiles"

        private const val TMP_SUFFIX = ".part"
        private const val COPY_BUFFER_BYTES = 1 shl 20          // 1 MiB
        private const val PROGRESS_STEP_BYTES = 4L shl 20       // 4 MiB
        private const val MAX_BASE_NAME_LENGTH = 100
        private val SIDE_FILE_SUFFIXES = listOf("-journal", "-wal", "-shm")

        fun hasMapExtension(fileName: String): Boolean {
            val lower = fileName.lowercase(Locale.ROOT)
            return lower.endsWith(EXT_MBTILES) || lower.endsWith(EXT_PMTILES)
        }

        /**
         * Reduces a document display name to `[A-Za-z0-9._-]` (runs of other characters collapse
         * to one `_`) and keeps the original `.mbtiles` / `.pmtiles` extension.
         *
         * @throws IllegalArgumentException when the name is missing or has another extension.
         */
        fun sanitizeName(displayName: String?): String {
            val raw = displayName?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Map file has no name")
            val lower = raw.lowercase(Locale.ROOT)
            val ext = when {
                lower.endsWith(EXT_MBTILES) -> EXT_MBTILES
                lower.endsWith(EXT_PMTILES) -> EXT_PMTILES
                else -> throw IllegalArgumentException("Unsupported map file '$raw' (expected .mbtiles or .pmtiles)")
            }
            val base = raw.substring(0, raw.length - ext.length)
            val sb = StringBuilder(base.length)
            var pendingUnderscore = false
            for (ch in base) {
                val keep = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-'
                if (keep) {
                    sb.append(ch)
                    pendingUnderscore = false
                } else if (!pendingUnderscore) {
                    sb.append('_')
                    pendingUnderscore = true
                }
            }
            var cleaned = sb.toString().trim('_', '.')
            if (cleaned.length > MAX_BASE_NAME_LENGTH) cleaned = cleaned.substring(0, MAX_BASE_NAME_LENGTH).trim('_', '.')
            if (cleaned.isEmpty()) cleaned = "map"
            return cleaned + ext
        }
    }
}
