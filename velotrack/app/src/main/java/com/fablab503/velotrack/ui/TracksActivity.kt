package com.fablab503.velotrack.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityTracksBinding
import com.fablab503.velotrack.gpx.GpxWriter
import com.fablab503.velotrack.model.TrackSummary
import com.fablab503.velotrack.model.Units
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.TrackDatabase
import com.fablab503.velotrack.storage.TrackRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** List of recorded rides: tap to view on the map, long-press for rename / export / delete. */
class TracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTracksBinding
    private lateinit var prefs: Prefs
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var adapter: ArrayAdapter<String>

    private var tracks: List<TrackSummary> = emptyList()
    private var pendingSaveFile: File? = null
    private var progress: ProgressDialogHandle? = null

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri: Uri? ->
            val src = pendingSaveFile
            pendingSaveFile = null
            if (uri != null && src != null) copyToUri(src, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = TrackDatabase.get(this)
        repo = TrackRepository(db)

        binding = ActivityTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ArrayAdapter(this, R.layout.item_list_row, R.id.text1, mutableListOf<String>())
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, position, _ ->
            tracks.getOrNull(position)?.let { openOnMap(it) }
        }
        binding.list.setOnItemLongClickListener { _, _, position, _ ->
            val track = tracks.getOrNull(position)
            if (track != null) {
                showActions(track)
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        progress?.dismiss()
        progress = null
        // TrackDatabase is process-wide; never close it here.
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { repo.listTracks() }.getOrDefault(emptyList()) }
            tracks = list
            val units = prefs.units
            adapter.clear()
            adapter.addAll(list.map { describe(it, units) })
            adapter.notifyDataSetChanged()
            binding.emptyText.isVisible = list.isEmpty()
        }
    }

    private fun describe(t: TrackSummary, units: Units): String {
        val name = if (t.state == TrackSummary.STATE_RECORDING) {
            "${t.name} ${getString(R.string.track_recording_suffix)}"
        } else {
            t.name
        }
        return getString(
            R.string.track_item,
            name,
            Format.dateTime(t.startedAtMs),
            Format.distance(t.distanceM, units),
            Format.duration(t.movingMs),
            Format.elevation(t.elevationGainM, units),
        )
    }

    private fun openOnMap(t: TrackSummary) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_VIEW_TRACK_ID, t.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun showActions(t: TrackSummary) {
        val items = arrayOf(
            getString(R.string.track_action_rename),
            getString(R.string.track_action_share),
            getString(R.string.track_action_save),
            getString(R.string.track_action_delete),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(t.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> rename(t)
                    1 -> exportAndShare(t)
                    2 -> exportAndSave(t)
                    3 -> confirmDelete(t)
                }
            }
            .show()
    }

    private fun rename(t: TrackSummary) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(t.name)
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.track_rename_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { runCatching { repo.renameTrack(t.id, newName) } }
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDelete(t: TrackSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.track_delete_title)
            .setMessage(getString(R.string.track_delete_body, t.name))
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { repo.deleteTrack(t.id) } }
                    reload()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---- Export -------------------------------------------------------------------------------

    /** Matches res/xml/file_paths.xml: external-files-path "tracks/" with an internal fallback. */
    private fun exportDir(): File {
        val dir = getExternalFilesDir("tracks") ?: File(filesDir, "tracks")
        dir.mkdirs()
        return dir
    }

    private fun safeFileName(t: TrackSummary): String {
        val cleaned = t.name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.')
        return if (cleaned.isEmpty()) "track-${t.id}" else cleaned.take(60)
    }

    private suspend fun writeGpx(t: TrackSummary): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(), safeFileName(t) + ".gpx")
        FileOutputStream(file).use { out ->
            GpxWriter.write(out, t.name, repo.pointsAsSequence(t.id))
            out.flush()
        }
        file
    }

    private fun runExport(t: TrackSummary, onDone: (File) -> Unit) {
        progress?.dismiss()
        progress = showProgressDialog(getString(R.string.track_exporting))
        lifecycleScope.launch {
            val result = runCatching { writeGpx(t) }
            progress?.dismiss()
            progress = null
            result
                .onSuccess { file ->
                    runCatching { onDone(file) }.onFailure { e -> showError(getString(R.string.track_export_failed, describe(e))) }
                }
                .onFailure { e -> showError(getString(R.string.track_export_failed, describe(e))) }
        }
    }

    private fun exportAndShare(t: TrackSummary) = runExport(t) { file ->
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.track_share_title)))
    }

    private fun exportAndSave(t: TrackSummary) = runExport(t) { file ->
        pendingSaveFile = file
        saveLauncher.launch(file.name)
    }

    private fun copyToUri(src: File, uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Cannot open destination")
                    out.use { o -> src.inputStream().use { it.copyTo(o) } }
                }
            }
            result
                .onSuccess { Toast.makeText(this@TracksActivity, R.string.track_saved, Toast.LENGTH_SHORT).show() }
                .onFailure { e -> showError(getString(R.string.track_export_failed, describe(e))) }
        }
    }

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }
}
