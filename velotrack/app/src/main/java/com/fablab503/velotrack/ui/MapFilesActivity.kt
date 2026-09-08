package com.fablab503.velotrack.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityMapFilesBinding
import com.fablab503.velotrack.databinding.ItemListRowBinding
import com.fablab503.velotrack.download.Bands
import com.fablab503.velotrack.download.MapDownloadService
import com.fablab503.velotrack.download.MapLibrary
import com.fablab503.velotrack.download.MapRegion
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.TrackDatabase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Map data": size of the four band files, the list of downloaded regions (long-press to delete),
 * a Download map FAB, and Import file / Clear all in the toolbar menu. Bands are always active, so
 * there is no per-file selection any more.
 *
 * This process only reads the band files (MapLibre holds them open). Delete, clear and import are
 * sent to [MapDownloadService], which writes in the `:download` process and reports back through
 * [MapDownloadService.ACTION_PROGRESS] broadcasts tagged with [MapDownloadService.EXTRA_OPERATION].
 */
class MapFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapFilesBinding
    private lateinit var prefs: Prefs
    private lateinit var library: MapLibrary
    private lateinit var adapter: ListRowAdapter<MapRegion>

    private var regions: List<MapRegion> = emptyList()
    private var progress: ProgressDialogHandle? = null
    private var receiverRegistered = false
    private var lastShownMb = -1L

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importFile(uri)
        }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MapDownloadService.ACTION_PROGRESS) return
            onServiceBroadcast(intent)
        }
    }

    /** A download finished from here changes the band files too: pass the signal up to [MainActivity]. */
    private val downloadLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) setResult(RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        library = MapLibrary(this, prefs, TrackDatabase.get(this))

        binding = ActivityMapFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ListRowAdapter(this) { row, region -> bindRow(row, region) }
        binding.list.adapter = adapter
        binding.list.setOnItemLongClickListener { _, _, position, _ ->
            val region = regions.getOrNull(position)
            if (region != null) {
                confirmDelete(region)
                true
            } else {
                false
            }
        }
        binding.btnDownload.setOnClickListener {
            downloadLauncher.launch(Intent(this, DownloadMapActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.map_data_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import_file -> {
                importLauncher.launch(arrayOf("*/*"))
                true
            }
            R.id.action_clear_all -> {
                confirmClearAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                serviceReceiver,
                IntentFilter(MapDownloadService.ACTION_PROGRESS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onStop() {
        // The job keeps running in the service (with its notification); the dialog would otherwise
        // outlive a result broadcast delivered while this screen is stopped.
        progress?.dismiss()
        progress = null
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                unregisterReceiver(serviceReceiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        super.onStop()
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

    // ---------------------------------------------------------------- data

    private fun reload() {
        lifecycleScope.launch {
            val (sizes, list) = withContext(Dispatchers.IO) {
                runCatching { library.ensureBandFiles() }
                val bandSizes = runCatching { library.bandSizesBytes() }.getOrDefault(LongArray(Bands.ALL.size))
                val regionList = runCatching { library.listRegions() }.getOrDefault(emptyList())
                Pair(bandSizes, regionList)
            }
            regions = list
            adapter.items = list
            binding.totalText.text = getString(R.string.map_data_total, Format.bytes(sizes.sum()))
            binding.bandsText.text = Bands.ALL.joinToString("\n") { band ->
                getString(
                    R.string.map_data_band_line,
                    bandLabel(band.index),
                    Format.bytes(sizes.getOrElse(band.index) { 0L }),
                )
            }
            val empty = list.isEmpty()
            binding.emptyText.isVisible = empty
            binding.emptyHint.isVisible = empty
        }
    }

    private fun bindRow(row: ItemListRowBinding, region: MapRegion) {
        val band = bandOrDefault(region.band)
        row.leadingIcon.setImageResource(if (band.index == 0) R.drawable.ic_public else R.drawable.ic_map)
        row.title.text = region.name
        row.subtitle.text = getString(
            R.string.map_region_subtitle,
            bandLabel(band.index),
            Format.bytes(region.bytes),
            Format.date(region.createdAt),
        )
        row.trailingIcon.setOnClickListener(null)
        row.trailingIcon.isClickable = false
        row.trailingIcon.isVisible = false
        row.trailingIcon.contentDescription = null
    }

    // ---------------------------------------------------------------- actions

    private fun confirmDelete(region: MapRegion) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_region_delete_title)
            .setMessage(getString(R.string.map_region_delete_body, region.name))
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                if (!MapDownloadService.deleteRegion(this, region.id)) showError(SERVICE_UNAVAILABLE)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_data_clear_all_title)
            .setMessage(R.string.map_data_clear_all_body)
            .setPositiveButton(R.string.map_data_clear_all_action) { _, _ ->
                if (!MapDownloadService.clearAll(this)) showError(SERVICE_UNAVAILABLE)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun importFile(uri: Uri) {
        progress?.dismiss()
        lastShownMb = -1L
        progress = showProgressDialog(getString(R.string.map_import_progress, Format.bytes(0L)))
        MapDownloadService.importFile(this, uri)
    }

    /** Results of delete / clear / import jobs; download broadcasts are for the other screens. */
    private fun onServiceBroadcast(intent: Intent) {
        val operation = intent.getStringExtra(MapDownloadService.EXTRA_OPERATION) ?: return
        if (operation == MapDownloadService.OP_DOWNLOAD) return
        val phase = intent.getStringExtra(MapDownloadService.EXTRA_PHASE) ?: return
        val message = intent.getStringExtra(MapDownloadService.EXTRA_MESSAGE)
        when (phase) {
            MapDownloadService.PHASE_DOWNLOADING -> if (operation == MapDownloadService.OP_IMPORT) {
                val copied = intent.getLongExtra(MapDownloadService.EXTRA_BYTES_DONE, 0L)
                val total = intent.getLongExtra(MapDownloadService.EXTRA_BYTES_TOTAL, 0L)
                val mb = copied / 1_000_000L
                if (mb != lastShownMb) {
                    lastShownMb = mb
                    val text = if (total > 0L) {
                        getString(R.string.map_import_progress_total, Format.bytes(copied), Format.bytes(total))
                    } else {
                        getString(R.string.map_import_progress, Format.bytes(copied))
                    }
                    progress?.setMessage(text)
                }
            }
            MapDownloadService.PHASE_DONE -> {
                dismissProgress()
                setResult(RESULT_OK)
                if (operation == MapDownloadService.OP_IMPORT) {
                    Toast.makeText(this, R.string.map_import_done, Toast.LENGTH_SHORT).show()
                }
                reload()
            }
            MapDownloadService.PHASE_FAILED -> {
                dismissProgress()
                val detail = when (message) {
                    MapDownloadService.MSG_BUSY -> BUSY
                    null -> "unknown error"
                    else -> message
                }
                if (operation == MapDownloadService.OP_IMPORT) {
                    if (!isFinishing && !isDestroyed) {
                        MaterialAlertDialogBuilder(this)
                            .setMessage(getString(R.string.map_import_failed, detail))
                            .setPositiveButton(R.string.dialog_ok, null)
                            .show()
                    }
                } else {
                    showError(detail)
                }
                reload()
            }
            MapDownloadService.PHASE_CANCELLED -> {
                dismissProgress()
                reload()
            }
        }
    }

    private fun dismissProgress() {
        progress?.dismiss()
        progress = null
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.error_prefix, message))
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    companion object {
        private const val BUSY = "a map download is still running"
        private const val SERVICE_UNAVAILABLE = "map service not available"
    }
}
