package com.fablab503.velotrack.ui

import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityMapFilesBinding
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.MapFileStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Imported .mbtiles/.pmtiles region files: tap to activate, long-press to delete, Import to copy a new one. */
class MapFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapFilesBinding
    private lateinit var prefs: Prefs
    private lateinit var store: MapFileStore
    private lateinit var adapter: ArrayAdapter<String>

    private var files: List<File> = emptyList()
    private var progress: ProgressDialogHandle? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = MapFileStore(this, prefs)

        binding = ActivityMapFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ArrayAdapter(this, R.layout.item_list_row, R.id.text1, mutableListOf<String>())
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, position, _ ->
            files.getOrNull(position)?.let { file ->
                store.setActive(file)
                Toast.makeText(this, getString(R.string.map_file_activated, file.name), Toast.LENGTH_SHORT).show()
                reload()
            }
        }
        binding.list.setOnItemLongClickListener { _, _, position, _ ->
            val file = files.getOrNull(position)
            if (file != null) {
                confirmDelete(file)
                true
            } else {
                false
            }
        }
        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        progress?.dismiss()
        progress = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        lifecycleScope.launch {
            val (list, active) = withContext(Dispatchers.IO) {
                runCatching { store.listMaps() to store.activeMap() }.getOrDefault(emptyList<File>() to null)
            }
            files = list
            adapter.clear()
            adapter.addAll(list.map { describe(it, active) })
            adapter.notifyDataSetChanged()
            binding.emptyText.isVisible = list.isEmpty()
        }
    }

    private fun describe(file: File, active: File?): String {
        val marker = if (active != null && active.absolutePath == file.absolutePath) {
            getString(R.string.map_file_active_marker)
        } else {
            ""
        }
        val size = getString(R.string.map_file_size_mb, formatMb(file.length()))
        return getString(R.string.map_file_item, marker + file.name, size)
    }

    private fun formatMb(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_file_delete_title)
            .setMessage(getString(R.string.map_file_delete_body, file.name))
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { store.delete(file) } }
                    reload()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun importFile(uri: Uri) {
        progress?.dismiss()
        progress = showProgressDialog(getString(R.string.map_import_progress, "0 MB"))
        lifecycleScope.launch {
            var lastShownMb = -1L
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.import(uri) { copied: Long, total: Long? ->
                        val mb = copied / 1_048_576L
                        if (mb != lastShownMb) {
                            lastShownMb = mb
                            val message = if (total != null && total > 0L) {
                                getString(R.string.map_import_progress_total, "$mb MB", "${total / 1_048_576L} MB")
                            } else {
                                getString(R.string.map_import_progress, "$mb MB")
                            }
                            runOnUiThread { progress?.setMessage(message) }
                        }
                    }
                }
            }
            progress?.dismiss()
            progress = null
            result
                .onSuccess { file ->
                    store.setActive(file)
                    Toast.makeText(this@MapFilesActivity, getString(R.string.map_import_done, file.name), Toast.LENGTH_SHORT).show()
                    reload()
                }
                .onFailure { e ->
                    MaterialAlertDialogBuilder(this@MapFilesActivity)
                        .setMessage(getString(R.string.map_import_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                }
        }
    }
}
