package com.fablab503.velotrack.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
import com.fablab503.velotrack.databinding.ItemListRowBinding
import com.fablab503.velotrack.gpx.GpxWriter
import com.fablab503.velotrack.model.RecordingStatus
import com.fablab503.velotrack.model.TrackSummary
import com.fablab503.velotrack.model.Units
import com.fablab503.velotrack.recording.RideSession
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

/** List of recorded rides: tap to view on the map, trailing menu or long-press for continue / rename / export / delete. */
class TracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTracksBinding
    private lateinit var prefs: Prefs
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var adapter: ListRowAdapter<TrackSummary>

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

        adapter = ListRowAdapter(this) { row, track -> bindRow(row, track) }
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
            adapter.items = list
            val empty = list.isEmpty()
            binding.emptyText.isVisible = empty
            binding.emptyHint.isVisible = empty
        }
    }

    private fun bindRow(row: ItemListRowBinding, t: TrackSummary) {
        val units: Units = prefs.units
        row.leadingIcon.setImageResource(R.drawable.ic_directions_bike)
        row.title.text = if (t.state == TrackSummary.STATE_RECORDING) {
            "${t.name} ${getString(R.string.track_recording_suffix)}"
        } else {
            t.name
        }
        row.subtitle.text = getString(
            R.string.track_subtitle,
            Format.dateTime(t.startedAtMs),
            Format.distance(t.distanceM, units),
            Format.duration(t.movingMs),
            Format.elevation(t.elevationGainM, units),
        )
        row.trailingIcon.setImageResource(R.drawable.ic_more_vert)
        row.trailingIcon.contentDescription = getString(R.string.cd_track_actions)
        row.trailingIcon.isVisible = true
        row.trailingIcon.setOnClickListener { showActions(t) }
    }

    private fun openOnMap(t: TrackSummary) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_VIEW_TRACK_ID, t.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun showActions(t: TrackSummary) {
        // Continue is offered only when nothing is being recorded. Nothing can be added to a ride
        // while another one is running, and an entry that always answers "not now" is worse than
        // no entry. The actions are built as a list of label-to-action pairs rather than a fixed
        // array so a hidden first item cannot silently shift what the others do.
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (!RideSession.serviceRunning && RideSession.state.value.status == RecordingStatus.IDLE) {
            actions += R.string.track_action_continue to { confirmContinue(t) }
        }
        actions += R.string.track_action_rename to { rename(t) }
        actions += R.string.track_action_share to { exportAndShare(t) }
        actions += R.string.track_action_save to { exportAndSave(t) }
        actions += R.string.track_action_delete to { confirmDelete(t) }

        MaterialAlertDialogBuilder(this)
            .setTitle(t.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    /**
     * Continuing a ride is not obvious from its name, so the dialog says exactly what will happen
     * to it before anything is written: it grows, its totals carry on, and the gap between the two
     * halves is not counted as distance ridden.
     */
    private fun confirmContinue(t: TrackSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.continue_title)
            .setMessage(
                getString(
                    R.string.continue_body,
                    t.name,
                    Format.dateTime(t.startedAtMs),
                    Format.distance(t.distanceM, prefs.units),
                    Format.duration(t.movingMs),
                ),
            )
            .setPositiveButton(R.string.continue_confirm) { _, _ -> startContinuing(t) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Handed to MainActivity rather than started here: the location and notification permissions,
     * the battery-saver warning and the foreground-service launch all live there already, and a
     * ride belongs on the map anyway.
     */
    private fun startContinuing(t: TrackSummary) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONTINUE_TRACK_ID, t.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
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
