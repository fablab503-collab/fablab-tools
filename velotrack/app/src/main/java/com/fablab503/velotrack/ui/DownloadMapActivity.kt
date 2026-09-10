package com.fablab503.velotrack.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityDownloadMapBinding
import com.fablab503.velotrack.download.AreaPart
import com.fablab503.velotrack.download.Bands
import com.fablab503.velotrack.download.Countries
import com.fablab503.velotrack.download.MapDownloadService
import com.fablab503.velotrack.download.splitForBudget
import com.fablab503.velotrack.pmtiles.Mercator
import com.fablab503.velotrack.settings.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

/**
 * Picks an area (radius around the rider or the map centre, or a preset bounding box), shows the
 * exact download size computed by [MapDownloadService] and starts the download. Progress arrives
 * as [MapDownloadService.ACTION_PROGRESS] broadcasts; the service itself runs in its own process.
 */
class DownloadMapActivity : AppCompatActivity() {

    private sealed class Area(val bandIndex: Int) {
        class Radius(bandIndex: Int, val radiusKm: Double) : Area(bandIndex)

        /** Either a fixed preset ([nameRes], e.g. "All of France") or a country picked at
         *  runtime ([rawName]) -- there is no string resource for 195 country names. */
        class BBox(
            bandIndex: Int,
            val west: Double,
            val south: Double,
            val east: Double,
            val north: Double,
            val nameRes: Int = 0,
            val rawName: String? = null,
        ) : Area(bandIndex) {
            fun toMercator() = Mercator.BBox(west, south, east, north)
            fun name(context: Context): String = rawName ?: context.getString(nameRes)
        }

        /**
         * A country too large to plan in one pass, as the pieces it was split into. Downloaded one
         * after another by the service; see [MapDownloadService.start] and [splitForBudget].
         *
         * There is deliberately no size estimate for this. Estimating means planning, and planning
         * every piece of France up front is nine full passes over the archive before a single byte
         * is fetched - minutes of waiting to be told a number the rider already expects to be
         * large. The pieces report their real sizes as they run.
         */
        class Parts(
            bandIndex: Int,
            val countryName: String,
            val parts: List<Pair<String, Mercator.BBox>>,
        ) : Area(bandIndex)
    }

    private enum class Mode { IDLE, ESTIMATING, DOWNLOADING }

    private lateinit var binding: ActivityDownloadMapBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var fix: Pair<Double, Double>? = null
    private var mapCentre: Pair<Double, Double>? = null
    private var selectedArea: Area? = null
    private var countryChoice: Countries.Country? = null
    private var countryDetailed = false

    private var mode = Mode.IDLE
    private var estimateReady = false
    private var receiverRegistered = false
    private var finishing = false

    /** Name generated from the selection; the field follows it until the user edits the text. */
    private var autoName = ""
    private var userEditedName = false
    private var settingName = false

    private val estimateRunnable = Runnable { requestEstimate() }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MapDownloadService.ACTION_PROGRESS) return
            // Import / delete / clear jobs share the broadcast action; only downloads concern this screen.
            val operation = intent.getStringExtra(MapDownloadService.EXTRA_OPERATION) ?: MapDownloadService.OP_DOWNLOAD
            if (operation != MapDownloadService.OP_DOWNLOAD) return
            onProgress(intent)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityDownloadMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        fix = coordinatesFrom(intent, EXTRA_LAT, EXTRA_LON)
        mapCentre = coordinatesFrom(intent, EXTRA_CENTER_LAT, EXTRA_CENTER_LON)

        setupCentre()
        setupChips()
        setupCountry()
        setupName()
        binding.btnDownload.setOnClickListener { startDownload(allowMetered = false) }
        binding.btnCancel.setOnClickListener { MapDownloadService.cancel(this) }

        // Default selection: 10 km around the available centre, otherwise the France preset.
        if (fix != null || mapCentre != null) {
            binding.chip10km.isChecked = true
        } else {
            binding.chipFrance.isChecked = true
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                progressReceiver,
                IntentFilter(MapDownloadService.ACTION_PROGRESS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        updateMeteredWarning()
        if (mode != Mode.DOWNLOADING) scheduleEstimate()
    }

    override fun onStop() {
        handler.removeCallbacks(estimateRunnable)
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                unregisterReceiver(progressReceiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------- setup

    private fun coordinatesFrom(intent: Intent?, latKey: String, lonKey: String): Pair<Double, Double>? {
        if (intent == null) return null
        val lat = intent.getDoubleExtra(latKey, Double.NaN)
        val lon = intent.getDoubleExtra(lonKey, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return Pair(lat, lon)
    }

    private fun setupCentre() {
        val hasFix = fix != null
        val hasCentre = mapCentre != null
        binding.noCentreText.isVisible = !hasFix && !hasCentre
        if (!hasFix && !hasCentre) {
            // Radius choices need a centre; the world and the presets do not.
            binding.chip10km.isEnabled = false
            binding.chip100km.isEnabled = false
            binding.chip1000km.isEnabled = false
        }
    }

    private fun setupChips() {
        binding.areaChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                binding.presetChips.clearCheck()
                clearCountrySelection()
                selectedArea = areaForChip(checkedIds[0])
                onSelectionChanged()
            } else if (binding.presetChips.checkedChipId == View.NO_ID) {
                selectedArea = null
                onSelectionChanged()
            }
        }
        binding.presetChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                binding.areaChips.clearCheck()
                clearCountrySelection()
                selectedArea = areaForChip(checkedIds[0])
                onSelectionChanged()
            } else if (binding.areaChips.checkedChipId == View.NO_ID) {
                selectedArea = null
                onSelectionChanged()
            }
        }
    }

    private fun areaForChip(chipId: Int): Area? = when (chipId) {
        R.id.chip10km -> Area.Radius(BAND_STREETS, 10.0)
        R.id.chip100km -> Area.Radius(BAND_ROADS, 100.0)
        R.id.chip1000km -> Area.Radius(BAND_REGION, 1000.0)
        R.id.chipWorld -> Area.Radius(BAND_OVERVIEW, WORLD_RADIUS_KM)
        R.id.chipFrance -> Area.BBox(BAND_REGION, -5.5, 41.3, 9.7, 51.2, R.string.download_name_france)
        else -> null
    }

    // ---------------------------------------------------------------- country

    private val countryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val country = CountryPickerActivity.countryFrom(data) ?: return@registerForActivityResult
            onCountryPicked(country)
        }

    private fun setupCountry() {
        binding.btnChooseCountry.setOnClickListener {
            countryPickerLauncher.launch(CountryPickerActivity.intent(this))
        }
        binding.countryDetailGroup.check(R.id.btnCountrySimple)
        binding.countryDetailGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            countryDetailed = checkedId == R.id.btnCountryDetailed
            val country = countryChoice ?: return@addOnButtonCheckedListener
            applyCountryBand(country)
        }
    }

    private fun onCountryPicked(country: Countries.Country) {
        countryChoice = country
        binding.areaChips.clearCheck()
        binding.presetChips.clearCheck()
        binding.btnChooseCountry.text = country.countryName
        binding.countryDetailGroup.isVisible = true
        applyCountryBand(country)
    }

    /** Rebuilds [selectedArea] from [country] and the current Simple/Detailed choice. */
    private fun applyCountryBand(country: Countries.Country) {
        val band = if (countryDetailed) BAND_STREETS else BAND_REGION
        val bandDef = Bands.byIndex(band)
        val box = Mercator.BBox(country.west, country.south, country.east, country.north)
        val parts = if (bandDef == null) {
            emptyList()
        } else {
            splitForBudget(box, bandDef.minZoom, bandDef.maxZoom, PLAN_BUDGET_CELLS)
        }
        if (parts.size <= 1) {
            selectedArea = Area.BBox(band, country.west, country.south, country.east, country.north, rawName = country.countryName)
            onSelectionChanged()
            return
        }
        askWholeOrPart(country, band, parts)
    }

    /**
     * A whole country at this detail is more than [PmTilesRemote.plan] can hold in memory at once,
     * so it is offered as pieces. Both answers are real: the whole country runs every piece one
     * after another, and a single piece is an ordinary download of an ordinary area.
     */
    private fun askWholeOrPart(country: Countries.Country, band: Int, parts: List<AreaPart>) {
        val named = parts.map { partName(country.countryName, it) to it.bbox }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.download_split_title, country.countryName))
            .setMessage(getString(R.string.download_split_body, parts.size))
            .setPositiveButton(getString(R.string.download_split_whole, parts.size)) { _, _ ->
                selectedArea = Area.Parts(band, country.countryName, named)
                onSelectionChanged()
            }
            .setNeutralButton(R.string.download_split_choose) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.download_split_title, country.countryName))
                    .setItems(named.map { it.first }.toTypedArray()) { _, which ->
                        val (name, box) = named[which]
                        selectedArea = Area.BBox(band, box.west, box.south, box.east, box.north, rawName = name)
                        onSelectionChanged()
                    }
                    .setNegativeButton(R.string.dialog_cancel) { _, _ -> clearCountrySelection() }
                    .show()
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> clearCountrySelection() }
            .setOnCancelListener { clearCountrySelection() }
            .show()
    }

    /**
     * A name for one piece. Up to three across and three down the compass says it plainly, and that
     * covers most countries. Beyond that a compass runs out of words - Russia needs 798 pieces - so
     * it falls back to a grid reference, which is ugly but never lies about where the piece is.
     */
    private fun partName(country: String, part: AreaPart): String {
        val where = if (part.cols <= 3 && part.rows <= 3) {
            val ns = when {
                part.rows == 1 -> ""
                part.row == 0 -> getString(R.string.compass_north)
                part.row == part.rows - 1 -> getString(R.string.compass_south)
                else -> getString(R.string.compass_middle)
            }
            val ew = when {
                part.cols == 1 -> ""
                part.col == 0 -> getString(R.string.compass_west)
                part.col == part.cols - 1 -> getString(R.string.compass_east)
                else -> getString(R.string.compass_middle)
            }
            when {
                ns.isEmpty() && ew.isEmpty() -> getString(R.string.compass_middle)
                ns.isEmpty() -> ew
                ew.isEmpty() -> ns
                ns == ew -> ns
                else -> getString(R.string.compass_pair, ns, ew)
            }
        } else {
            getString(R.string.download_part_grid, ('A' + part.col), part.row + 1)
        }
        return getString(R.string.download_part_name, country, where)
    }

    private fun clearCountrySelection() {
        if (countryChoice == null) return
        countryChoice = null
        binding.btnChooseCountry.text = getString(R.string.download_choose_country)
        binding.countryDetailGroup.isVisible = false
    }

    private fun setupName() {
        binding.nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (settingName) return
                val text = s?.toString() ?: ""
                userEditedName = text.isNotBlank() && text != autoName
            }
        })
    }

    // ---------------------------------------------------------------- selection

    private fun onSelectionChanged() {
        updateAutoName()
        estimateReady = false
        binding.btnDownload.isEnabled = false
        scheduleEstimate()
    }

    private fun updateAutoName() {
        val area = selectedArea
        autoName = when (area) {
            null -> ""
            is Area.Parts -> getString(
                R.string.download_name_country,
                area.countryName,
                bandLabel(area.bandIndex),
            )
            is Area.BBox -> if (area.rawName != null) {
                getString(R.string.download_name_country, area.rawName, bandLabel(area.bandIndex))
            } else {
                getString(area.nameRes)
            }
            is Area.Radius -> when {
                area.radiusKm >= WORLD_RADIUS_KM -> getString(R.string.download_name_world)
                // No GPS fix yet: silently centred on wherever the map was showing instead.
                fix == null -> getString(R.string.download_name_map_centre, formatKm(area.radiusKm))
                else -> getString(R.string.download_name_around_me, formatKm(area.radiusKm))
            }
        }
        if (!userEditedName) {
            settingName = true
            binding.nameEdit.setText(autoName)
            binding.nameEdit.setSelection(autoName.length)
            settingName = false
        }
    }

    private fun formatKm(km: Double): String =
        if (km == Math.floor(km)) "${km.toLong()} km" else String.format(Locale.getDefault(), "%.1f km", km)

    private fun currentName(): String {
        val typed = binding.nameEdit.text?.toString()?.trim().orEmpty()
        return if (typed.isNotEmpty()) typed else autoName.ifEmpty { getString(R.string.title_download_map) }
    }

    /** The circle centre for radius areas: the rider's GPS fix, or the map's last position if none. */
    private fun centre(): Pair<Double, Double>? = fix ?: mapCentre

    // ---------------------------------------------------------------- estimate

    private fun scheduleEstimate() {
        handler.removeCallbacks(estimateRunnable)
        if (mode == Mode.DOWNLOADING) return
        val area = selectedArea
        if (area == null) {
            mode = Mode.IDLE
            showEstimateIdle()
            return
        }
        showEstimating()
        handler.postDelayed(estimateRunnable, ESTIMATE_DEBOUNCE_MS)
    }

    private fun requestEstimate() {
        if (mode == Mode.DOWNLOADING) return
        val area = selectedArea ?: return
        mode = Mode.ESTIMATING
        val name = currentName()
        when (area) {
            is Area.Parts -> {
                // Planning nine pieces of France before fetching a byte would be minutes of waiting
                // to be told a number the rider already knows is large. Each piece reports its real
                // size as it runs instead.
                mode = Mode.IDLE
                binding.estimateProgress.isVisible = false
                binding.estimateText.text = getString(R.string.download_parts_ready, area.parts.size)
                estimateReady = true
                binding.btnDownload.isEnabled = true
                updateMeteredWarning()
            }
            is Area.BBox -> MapDownloadService.estimate(this, name, area.bandIndex, area.toMercator())
            is Area.Radius -> {
                val c = centre() ?: if (area.radiusKm >= WORLD_RADIUS_KM) Pair(0.0, 0.0) else null
                if (c == null) {
                    mode = Mode.IDLE
                    binding.estimateProgress.isVisible = false
                    binding.estimateText.text = getString(R.string.download_no_centre)
                    return
                }
                MapDownloadService.estimate(this, name, area.bandIndex, c.first, c.second, area.radiusKm)
            }
        }
    }

    private fun showEstimateIdle() {
        binding.estimateProgress.isVisible = false
        binding.estimateText.text = getString(R.string.download_no_centre)
        binding.btnDownload.isEnabled = false
    }

    private fun showEstimating() {
        binding.estimateProgress.isVisible = true
        binding.estimateText.text = getString(R.string.download_estimating)
    }

    private fun showEstimate(bytesTotal: Long, tilesTotal: Long, message: String?) {
        binding.estimateProgress.isVisible = false
        val build = buildDateFrom(message)
        binding.estimateText.text = if (build != null) {
            getString(R.string.download_estimate_build, Format.bytes(bytesTotal), Format.count(tilesTotal), build)
        } else {
            getString(R.string.download_estimate, Format.bytes(bytesTotal), Format.count(tilesTotal))
        }
        estimateReady = true
        binding.btnDownload.isEnabled = mode != Mode.DOWNLOADING
        updateMeteredWarning()
    }

    private fun showEstimateFailed(message: String?) {
        binding.estimateProgress.isVisible = false
        binding.estimateText.text = getString(R.string.download_estimate_failed, message ?: "unknown error")
        estimateReady = false
        binding.btnDownload.isEnabled = false
    }

    /** "20260907.pmtiles" or "20260907" → "2026-09-07"; null when the message carries no date. */
    private fun buildDateFrom(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val m = BUILD_DATE_REGEX.find(message) ?: return null
        return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
    }

    private fun updateMeteredWarning() {
        binding.meteredText.isVisible = prefs.wifiOnlyDownloads && isActiveNetworkMetered()
    }

    private fun isActiveNetworkMetered(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            cm.isActiveNetworkMetered
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    // ---------------------------------------------------------------- download

    private fun startDownload(allowMetered: Boolean) {
        val area = selectedArea ?: return
        handler.removeCallbacks(estimateRunnable)
        val name = currentName()
        when (area) {
            is Area.Parts -> MapDownloadService.start(this, area.bandIndex, area.parts, allowMetered)
            is Area.BBox -> MapDownloadService.start(this, name, area.bandIndex, area.toMercator(), allowMetered)
            is Area.Radius -> {
                val c = centre() ?: if (area.radiusKm >= WORLD_RADIUS_KM) Pair(0.0, 0.0) else null
                if (c == null) {
                    showEstimateIdle()
                    return
                }
                MapDownloadService.start(this, name, area.bandIndex, c.first, c.second, area.radiusKm, allowMetered)
            }
        }
        enterDownloading()
        binding.progressText.text = getString(R.string.download_starting)
        binding.downloadProgress.setProgressCompat(0, false)
    }

    private fun enterDownloading() {
        if (mode == Mode.DOWNLOADING) return
        mode = Mode.DOWNLOADING
        handler.removeCallbacks(estimateRunnable)
        setInputsEnabled(false)
        binding.btnDownload.isEnabled = false
        binding.estimateProgress.isVisible = false
        binding.progressCard.isVisible = true
    }

    private fun leaveDownloading() {
        mode = Mode.IDLE
        binding.progressCard.isVisible = false
        setInputsEnabled(true)
        binding.btnDownload.isEnabled = estimateReady
    }

    private fun setInputsEnabled(enabled: Boolean) {
        val radiusOk = enabled && (fix != null || mapCentre != null)
        binding.chip10km.isEnabled = radiusOk
        binding.chip100km.isEnabled = radiusOk
        binding.chip1000km.isEnabled = radiusOk
        binding.chipWorld.isEnabled = enabled
        binding.chipFrance.isEnabled = enabled
        binding.btnChooseCountry.isEnabled = enabled
        binding.btnCountrySimple.isEnabled = enabled
        binding.btnCountryDetailed.isEnabled = enabled
        binding.nameLayout.isEnabled = enabled
        binding.nameEdit.isEnabled = enabled
    }

    private fun onProgress(intent: Intent) {
        val phase = intent.getStringExtra(MapDownloadService.EXTRA_PHASE) ?: return
        val bytesDone = numberExtra(intent, MapDownloadService.EXTRA_BYTES_DONE)
        val bytesTotal = numberExtra(intent, MapDownloadService.EXTRA_BYTES_TOTAL)
        val tilesDone = numberExtra(intent, MapDownloadService.EXTRA_TILES_DONE)
        val tilesTotal = numberExtra(intent, MapDownloadService.EXTRA_TILES_TOTAL)
        val message = intent.getStringExtra(MapDownloadService.EXTRA_MESSAGE)
        when (phase) {
            MapDownloadService.PHASE_ESTIMATING -> {
                if (mode == Mode.DOWNLOADING) return
                if (bytesTotal > 0L || tilesTotal > 0L) showEstimate(bytesTotal, tilesTotal, message) else showEstimating()
            }
            MapDownloadService.PHASE_ESTIMATED -> {
                if (mode == Mode.DOWNLOADING) return
                showEstimate(bytesTotal, tilesTotal, message)
            }
            MapDownloadService.PHASE_DOWNLOADING -> {
                enterDownloading()
                updateProgress(bytesDone, bytesTotal, tilesDone, tilesTotal)
            }
            MapDownloadService.PHASE_DONE -> when (mode) {
                Mode.DOWNLOADING -> onDownloadDone()
                Mode.ESTIMATING -> showEstimate(bytesTotal, tilesTotal, message)
                Mode.IDLE -> Unit
            }
            MapDownloadService.PHASE_FAILED -> {
                if (mode == Mode.DOWNLOADING) {
                    leaveDownloading()
                    if (message == MapDownloadService.MSG_METERED) offerDownloadAnyway() else showSnackbar(getString(R.string.download_failed, message ?: "unknown error"))
                } else {
                    mode = Mode.IDLE
                    showEstimateFailed(message)
                }
            }
            MapDownloadService.PHASE_CANCELLED -> {
                if (mode == Mode.DOWNLOADING) {
                    leaveDownloading()
                    showSnackbar(getString(R.string.download_cancelled))
                    scheduleEstimate()
                }
            }
        }
    }

    private fun updateProgress(bytesDone: Long, bytesTotal: Long, tilesDone: Long, tilesTotal: Long) {
        val pct = if (bytesTotal > 0L) ((bytesDone * 100L) / bytesTotal).toInt().coerceIn(0, 100) else 0
        val permille = if (bytesTotal > 0L) ((bytesDone * 1000L) / bytesTotal).toInt().coerceIn(0, 1000) else 0
        binding.downloadProgress.setProgressCompat(permille, true)
        binding.progressText.text = if (tilesTotal > 0L) {
            getString(
                R.string.download_progress_tiles,
                Format.bytes(bytesDone), Format.bytes(bytesTotal), pct, Format.count(tilesDone), Format.count(tilesTotal),
            )
        } else if (bytesTotal > 0L) {
            getString(R.string.download_progress, Format.bytes(bytesDone), Format.bytes(bytesTotal), pct)
        } else {
            getString(R.string.download_starting)
        }
    }

    private fun onDownloadDone() {
        if (finishing) return
        finishing = true
        binding.downloadProgress.setProgressCompat(1000, true)
        setResult(RESULT_OK)
        showSnackbar(getString(R.string.download_done))
        handler.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, DONE_LINGER_MS)
    }

    private fun offerDownloadAnyway() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_metered_title)
            .setMessage(R.string.download_metered_body)
            .setPositiveButton(R.string.download_anyway) { _, _ -> startDownload(allowMetered = true) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showSnackbar(text: String) {
        Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).show()
    }

    /** Reads a numeric extra whatever its boxed type (the service may use Int or Long). */
    @Suppress("DEPRECATION")
    private fun numberExtra(intent: Intent, key: String): Long =
        (intent.extras?.get(key) as? Number)?.toLong() ?: 0L

    companion object {
        /** Double extras: the rider's last fix and the map camera target, sent by [MainActivity]. */
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_CENTER_LAT = "center_lat"
        const val EXTRA_CENTER_LON = "center_lon"

        private const val BAND_OVERVIEW = 0
        private const val BAND_REGION = 1
        private const val BAND_ROADS = 2
        private const val BAND_STREETS = 3
        private const val WORLD_RADIUS_KM = 10_000.0

        /**
         * Grid cells one download piece may need. Deliberately below
         * [com.fablab503.velotrack.download.PmTilesRemote.MAX_PLANNABLE_TILES] (300,000): that is a
         * hard refusal, and a piece that lands exactly on it would fail after the rider chose it.
         * The headroom also absorbs the difference between a bbox's cells and the tiles that
         * actually exist in it.
         */
        private const val PLAN_BUDGET_CELLS = 250_000L

        private const val ESTIMATE_DEBOUNCE_MS = 400L
        private const val DONE_LINGER_MS = 1_200L
        private val BUILD_DATE_REGEX = Regex("(\\d{4})(\\d{2})(\\d{2})")
    }
}
