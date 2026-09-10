package com.fablab503.velotrack.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fablab503.velotrack.R
import com.fablab503.velotrack.pmtiles.Mercator
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.TrackDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * `dataSync` foreground service (own process `:download`) that estimates and downloads map areas
 * from the Protomaps planet archive into the band MBTiles files.
 *
 * Protocol: [ACTION_ESTIMATE] / [ACTION_START] with [EXTRA_NAME], either [EXTRA_LAT] +
 * [EXTRA_LON] + [EXTRA_RADIUS_KM] or [EXTRA_WEST]…[EXTRA_NORTH], and [EXTRA_BAND]; [ACTION_CANCEL].
 * Progress goes out as [ACTION_PROGRESS] broadcasts limited to this package (`setPackage`), so the
 * UI registers its receiver with `RECEIVER_NOT_EXPORTED`.
 *
 * The other band-file writes run here as well: [ACTION_IMPORT] (the MBTiles document as the
 * intent data, read permission granted), [ACTION_DELETE_REGION] with [EXTRA_REGION_ID] and
 * [ACTION_CLEAR]. The UI process holds the band files open through MapLibre's bundled SQLite, and
 * a second SQLite copy writing the same file inside that process breaks the POSIX locks (SQLite
 * "How to corrupt" §2.3), so every write goes through this `:download` process. Their broadcasts
 * carry [EXTRA_OPERATION] (`import|delete|clear`; downloads send `download`) so the screens can
 * tell them apart.
 *
 * This process must never touch `RideSession` or MapLibre.
 */
class MapDownloadService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var library: MapLibrary
    private lateinit var http: OkHttpClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null
    private var estimateJob: Job? = null

    /** Where a multi-part download has got to; 1 and 0 whenever a single area is being downloaded. */
    private var partsTotal = 1
    private var partIndex = 0
    /** Import / delete / clear; never runs concurrently with [downloadJob]. */
    private var maintenanceJob: Job? = null
    private var foreground = false
    private var cachedPlans: CachedPlans? = null

    private val publishLock = Any()
    private var lastPublishUptimeMs = 0L

    private class DownloadRequest(val name: String, val bbox: Mercator.BBox, val bandIndex: Int, val allowMetered: Boolean) {
        val key: String
            get() = String.format(
                Locale.ROOT, "%d|%.6f|%.6f|%.6f|%.6f", bandIndex, bbox.west, bbox.south, bbox.east, bbox.north,
            )
    }

    private class BandPlan(val band: Bands.Band, val minZoom: Int, val maxZoom: Int, val plan: ExtractPlan)

    private class CachedPlans(
        val requestKey: String,
        val url: String,
        val buildKey: String,
        val remote: PmTilesRemote,
        val plans: List<BandPlan>,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        library = MapLibrary(this, prefs, TrackDatabase.get(this))
        http = RangeClient.defaultClient()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ESTIMATE -> {
                val req = parseRequest(intent)
                if (req == null) {
                    broadcast(PHASE_FAILED, message = MSG_BAD_REQUEST)
                    stopIfIdle()
                } else {
                    startEstimate(req)
                }
            }
            ACTION_START -> {
                val reqs = parseRequests(intent)
                if (reqs.isNullOrEmpty()) {
                    broadcast(PHASE_FAILED, message = MSG_BAD_REQUEST)
                    stopIfIdle()
                } else {
                    startDownload(reqs)
                }
            }
            ACTION_IMPORT -> {
                val uri = intent.data
                if (uri == null) {
                    broadcast(PHASE_FAILED, message = MSG_BAD_REQUEST, operation = OP_IMPORT)
                    stopIfIdle()
                } else {
                    startImport(uri)
                }
            }
            ACTION_DELETE_REGION -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id < 0L) {
                    broadcast(PHASE_FAILED, message = MSG_BAD_REQUEST, operation = OP_DELETE)
                    stopIfIdle()
                } else {
                    startDeleteRegion(id)
                }
            }
            ACTION_CLEAR -> startClear()
            ACTION_CANCEL -> {
                val hadWork = (downloadJob?.isActive == true) || (estimateJob?.isActive == true) ||
                    (maintenanceJob?.isActive == true)
                downloadJob?.cancel()
                estimateJob?.cancel()
                maintenanceJob?.cancel()
                if (!hadWork) {
                    finishForeground()
                    stopSelf()
                }
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    /** Android 15: the dataSync budget ran out while in the background; stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= 35) super.onTimeout(startId, fgsType)
        downloadJob?.cancel()
        estimateJob?.cancel()
        maintenanceJob?.cancel()
        finishForeground()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        } catch (e: Exception) {
            // Best-effort cleanup; the process ends with the service anyway.
        }
        super.onDestroy()
    }

    // ---- request parsing -------------------------------------------------------------------------

    /**
     * Every piece this start command asks for, in order.
     *
     * A whole country at the finest band cannot be planned in one pass - that is what
     * [AreaTooLargeException] guards against - so the picker splits it with [splitForBudget] and
     * hands the pieces over together. One piece is the ordinary case and is simply a list of one,
     * which is why nothing else in this file had to learn about parts.
     */
    private fun parseRequests(intent: Intent): List<DownloadRequest>? {
        val wests = intent.getDoubleArrayExtra(EXTRA_PART_WESTS)
        val souths = intent.getDoubleArrayExtra(EXTRA_PART_SOUTHS)
        val easts = intent.getDoubleArrayExtra(EXTRA_PART_EASTS)
        val norths = intent.getDoubleArrayExtra(EXTRA_PART_NORTHS)
        val names = intent.getStringArrayExtra(EXTRA_PART_NAMES)
        if (wests == null || souths == null || easts == null || norths == null || names == null) {
            return parseRequest(intent)?.let { listOf(it) }
        }
        val n = wests.size
        if (n == 0 || souths.size != n || easts.size != n || norths.size != n || names.size != n) return null
        val band = intent.getIntExtra(EXTRA_BAND, -1)
        if (Bands.byIndex(band) == null) return null
        val allowMetered = intent.getBooleanExtra(EXTRA_ALLOW_METERED, false)
        val out = ArrayList<DownloadRequest>(n)
        for (i in 0 until n) {
            val box = Mercator.BBox(wests[i], souths[i], easts[i], norths[i])
            if (box.west > box.east || box.south > box.north) return null
            out.add(DownloadRequest(names[i], box, band, allowMetered))
        }
        return out
    }

    private fun parseRequest(intent: Intent): DownloadRequest? {
        val name = intent.getStringExtra(EXTRA_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_NAME
        val allowMetered = intent.getBooleanExtra(EXTRA_ALLOW_METERED, false)
        var radiusKm: Double? = null
        val bbox: Mercator.BBox = when {
            intent.hasExtra(EXTRA_WEST) && intent.hasExtra(EXTRA_SOUTH) &&
                intent.hasExtra(EXTRA_EAST) && intent.hasExtra(EXTRA_NORTH) -> Mercator.BBox(
                intent.getDoubleExtra(EXTRA_WEST, -180.0),
                intent.getDoubleExtra(EXTRA_SOUTH, -Mercator.MAX_LAT),
                intent.getDoubleExtra(EXTRA_EAST, 180.0),
                intent.getDoubleExtra(EXTRA_NORTH, Mercator.MAX_LAT),
            )
            intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LON) -> {
                val r = intent.getDoubleExtra(EXTRA_RADIUS_KM, 10.0)
                radiusKm = r
                Mercator.circleBBox(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0), r)
            }
            else -> return null
        }
        if (bbox.west > bbox.east || bbox.south > bbox.north) return null
        val explicitBand = intent.getIntExtra(EXTRA_BAND, -1)
        val bandIndex = when {
            Bands.byIndex(explicitBand) != null -> explicitBand
            radiusKm != null -> Bands.forRadiusKm(radiusKm).index
            else -> return null
        }
        return DownloadRequest(name, bbox, bandIndex, allowMetered)
    }

    // ---- estimate ---------------------------------------------------------------------------------

    private fun startEstimate(req: DownloadRequest) {
        estimateJob?.cancel()
        estimateJob = scope.launch {
            try {
                broadcast(PHASE_ESTIMATING, name = req.name, band = req.bandIndex)
                val cached = planOrCached(req)
                val bytesTotal = cached.plans.sumOf { it.plan.totalBytes }
                val tilesTotal = cached.plans.sumOf { it.plan.tiles.size.toLong() }
                broadcast(
                    PHASE_ESTIMATED,
                    bytesDone = 0L, bytesTotal = bytesTotal, tilesDone = 0L, tilesTotal = tilesTotal,
                    message = cached.buildKey, name = req.name, band = req.bandIndex,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                broadcast(PHASE_FAILED, message = describe(e), name = req.name, band = req.bandIndex)
            } finally {
                // Non-immediate Main dispatcher: runs after this coroutine has completed, so
                // stopIfIdle() sees estimateJob as inactive.
                scope.launch(Dispatchers.Main) { stopIfIdle() }
            }
        }
    }

    /** Resolves the archive and plans every band to fill, reusing the last estimate when it matches. */
    private suspend fun planOrCached(req: DownloadRequest): CachedPlans {
        cachedPlans?.let { if (it.requestKey == req.key) return it }
        val (url, buildKey) = PlanetSource.resolveUrl(http, prefs.planetUrlOverride)
        val remote = PmTilesRemote(RangeClient(http), url)
        val plans = planBands(remote, req)
        return CachedPlans(req.key, url, buildKey, remote, plans).also { cachedPlans = it }
    }

    /** The target band plus every coarser band that has no region covering the area yet, coarse first. */
    private suspend fun planBands(remote: PmTilesRemote, req: DownloadRequest): List<BandPlan> {
        val header = remote.open()
        val regions = withContext(Dispatchers.IO) { library.listRegions() }
        val result = ArrayList<BandPlan>()
        for (band in Bands.ALL) {
            if (band.index > req.bandIndex) break
            if (band.index < req.bandIndex && regions.any { it.band == band.index && it.covers(req.bbox) }) continue
            val zMin = maxOf(band.minZoom, header.minZoom)
            val zMax = minOf(band.maxZoom, header.maxZoom)
            if (zMin > zMax) continue
            val plan = remote.plan(req.bbox, zMin, zMax)
            result.add(BandPlan(band, zMin, zMax, plan))
        }
        return result
    }

    // ---- download ---------------------------------------------------------------------------------

    private fun startDownload(reqs: List<DownloadRequest>) {
        val req = reqs.first()
        if (downloadJob?.isActive == true || maintenanceJob?.isActive == true) {
            broadcast(PHASE_FAILED, message = MSG_BUSY, name = req.name, band = req.bandIndex)
            return
        }
        if (!ensureForeground()) {
            broadcast(PHASE_FAILED, message = MSG_FOREGROUND, name = req.name, band = req.bandIndex)
            stopSelf()
            return
        }
        if (prefs.wifiOnlyDownloads && !req.allowMetered && isActiveNetworkMetered()) {
            broadcast(PHASE_FAILED, message = MSG_METERED, name = req.name, band = req.bandIndex)
            finishForeground()
            stopIfIdle()
            return
        }
        estimateJob?.cancel()
        downloadJob = scope.launch { runQueue(reqs) }
    }

    /**
     * Runs every piece in turn, and owns the foreground notification for the whole run rather than
     * for one piece: stopping between pieces would drop the service and, on Android 12 and later,
     * make starting the next one from the background impossible.
     *
     * A piece that fails ends the run. The pieces of one country share an archive and a build, so
     * whatever stopped one - no network, no disk, a withdrawn build - stops the rest too, and
     * grinding through eight more failures to say so eight more times helps nobody.
     */
    private suspend fun runQueue(reqs: List<DownloadRequest>) {
        partsTotal = reqs.size
        try {
            for ((index, req) in reqs.withIndex()) {
                partIndex = index
                if (!runDownload(req)) break
            }
        } finally {
            partsTotal = 1
            partIndex = 0
            finishForeground()
            if (estimateJob?.isActive != true && maintenanceJob?.isActive != true) stopSelf()
        }
    }

    /** One piece. Returns true when it finished; false when it failed (the run then stops). */
    private suspend fun runDownload(req: DownloadRequest): Boolean {
        val bytesDone = AtomicLong(0L)
        val tilesDone = AtomicLong(0L)
        var bytesTotal = 0L
        var tilesTotal = 0L
        var buildKey = ""
        try {
            broadcast(PHASE_ESTIMATING, name = req.name, band = req.bandIndex)
            updateNotification(TEXT_PREPARING, 0L, 0L, indeterminate = true)
            val cached = planOrCached(req)
            cachedPlans = null // plans are consumed by this download
            buildKey = cached.buildKey
            bytesTotal = cached.plans.sumOf { it.plan.totalBytes }
            tilesTotal = cached.plans.sumOf { it.plan.tiles.size.toLong() }
            publishProgress(req, PHASE_DOWNLOADING, 0L, bytesTotal, 0L, tilesTotal, buildKey, force = true)

            val layers = library.vectorLayersJson()
            for (bandPlan in cached.plans) {
                val band = bandPlan.band
                val plan = bandPlan.plan
                val base = bytesDone.get()
                val bandBytes = AtomicLong(0L)
                val writer = withContext(Dispatchers.IO) {
                    MbtilesWriter.open(library.bandFile(band), band.minZoom, band.maxZoom, layers)
                }
                try {
                    // Off the service's main thread: the sink blocks on SQLite writes and slices blobs.
                    withContext(Dispatchers.Default) {
                        cached.remote.download(
                            plan,
                            PARALLELISM,
                            onProgress = { done, _ ->
                                bytesDone.set(base + done)
                                publishProgress(
                                    req, PHASE_DOWNLOADING, bytesDone.get(), bytesTotal, tilesDone.get(), tilesTotal, buildKey,
                                    force = false,
                                )
                            },
                        ) { z, x, y, bytes ->
                            writer.putTile(z, x, y, bytes)
                            tilesDone.incrementAndGet()
                            bandBytes.addAndGet(bytes.size.toLong())
                        }
                    }
                    withContext(Dispatchers.IO) { writer.commit() }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { writer.close() }
                }
                bytesDone.set(base + plan.totalBytes)
                val region = MapRegion(
                    id = 0L,
                    name = req.name,
                    band = band.index,
                    west = req.bbox.west,
                    south = req.bbox.south,
                    east = req.bbox.east,
                    north = req.bbox.north,
                    minZoom = bandPlan.minZoom,
                    maxZoom = bandPlan.maxZoom,
                    tiles = plan.tiles.size.toLong(),
                    bytes = bandBytes.get(),
                    build = buildKey,
                    createdAt = System.currentTimeMillis(),
                )
                withContext(Dispatchers.IO) { library.addRegion(region) }
            }
            publishProgress(req, PHASE_DONE, bytesTotal, bytesTotal, tilesTotal, tilesTotal, buildKey, force = true)
            if (partIndex == partsTotal - 1) showResultNotification(TITLE_DONE, req.name)
            return true
        } catch (e: CancellationException) {
            broadcast(
                PHASE_CANCELLED,
                bytesDone = bytesDone.get(), bytesTotal = bytesTotal, tilesDone = tilesDone.get(), tilesTotal = tilesTotal,
                message = buildKey, name = req.name, band = req.bandIndex,
            )
            throw e
        } catch (e: Exception) {
            val message = describe(e)
            broadcast(
                PHASE_FAILED,
                bytesDone = bytesDone.get(), bytesTotal = bytesTotal, tilesDone = tilesDone.get(), tilesTotal = tilesTotal,
                message = message, name = req.name, band = req.bandIndex,
            )
            showResultNotification(TITLE_FAILED, message)
            return false
        }
    }

    // ---- import / delete / clear ------------------------------------------------------------------

    /** Imports an MBTiles document into the bands; long, so it runs as a foreground `dataSync` job. */
    private fun startImport(uri: Uri) {
        startMaintenance(OP_IMPORT, foregroundTitle = TITLE_IMPORTING) {
            publishImport(0L, 0L, force = true)
            library.importFile(uri) { done, total -> publishImport(done, total ?: 0L, force = false) }
            showResultNotification(TITLE_IMPORT_DONE, TEXT_IMPORT_DONE)
        }
    }

    private fun startDeleteRegion(id: Long) {
        startMaintenance(OP_DELETE, foregroundTitle = null) {
            withContext(Dispatchers.IO) { library.deleteRegion(id) }
        }
    }

    private fun startClear() {
        startMaintenance(OP_CLEAR, foregroundTitle = null) {
            withContext(Dispatchers.IO) { library.clearAll() }
        }
    }

    /**
     * Runs one band-file write in this process (see the class comment). Refused with `failed: busy`
     * while a download or another write is running; a download cannot start while it runs. Ends
     * with a `done` / `failed` / `cancelled` broadcast tagged [operation]; the cached estimate is
     * dropped because the regions changed.
     */
    private fun startMaintenance(operation: String, foregroundTitle: String?, block: suspend () -> Unit) {
        // A foreground request must reach startForeground() within seconds even when it is then
        // refused as busy (the running job's finally block takes the notification down again).
        if (foregroundTitle != null && !ensureForeground(foregroundTitle)) {
            broadcast(PHASE_FAILED, message = MSG_FOREGROUND, operation = operation)
            stopIfIdle()
            return
        }
        if (downloadJob?.isActive == true || maintenanceJob?.isActive == true) {
            broadcast(PHASE_FAILED, message = MSG_BUSY, operation = operation)
            return
        }
        maintenanceJob = scope.launch {
            try {
                block()
                broadcast(PHASE_DONE, operation = operation)
            } catch (e: CancellationException) {
                broadcast(PHASE_CANCELLED, operation = operation)
                throw e
            } catch (e: Exception) {
                val message = describe(e)
                broadcast(PHASE_FAILED, message = message, operation = operation)
                if (operation == OP_IMPORT) showResultNotification(TITLE_IMPORT_FAILED, message)
            } finally {
                cachedPlans = null
                finishForeground()
                if (estimateJob?.isActive != true && downloadJob?.isActive != true) stopSelf()
            }
        }
    }

    /** Throttled import progress (`bytesDone/bytesTotal`, total 0 when unknown); safe from any thread. */
    private fun publishImport(bytesDone: Long, bytesTotal: Long, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        synchronized(publishLock) {
            if (!force && now - lastPublishUptimeMs < PUBLISH_INTERVAL_MS) return
            lastPublishUptimeMs = now
        }
        broadcast(PHASE_DOWNLOADING, bytesDone = bytesDone, bytesTotal = bytesTotal, operation = OP_IMPORT)
        val text = if (bytesTotal > 0L) {
            Formatter.formatShortFileSize(this, bytesDone) + " / " + Formatter.formatShortFileSize(this, bytesTotal)
        } else {
            Formatter.formatShortFileSize(this, bytesDone)
        }
        updateNotification(text, bytesDone, bytesTotal, indeterminate = bytesTotal <= 0L, title = TITLE_IMPORTING)
    }

    // ---- progress / broadcasts ------------------------------------------------------------------

    /** Throttled (500 ms) broadcast + notification update; safe from any thread. */
    private fun publishProgress(
        req: DownloadRequest,
        phase: String,
        bytesDone: Long,
        bytesTotal: Long,
        tilesDone: Long,
        tilesTotal: Long,
        buildKey: String,
        force: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(publishLock) {
            if (!force && now - lastPublishUptimeMs < PUBLISH_INTERVAL_MS) return
            lastPublishUptimeMs = now
        }
        broadcast(phase, bytesDone, bytesTotal, tilesDone, tilesTotal, buildKey, req.name, req.bandIndex)
        if (phase == PHASE_DOWNLOADING) {
            val text = if (bytesTotal > 0L) {
                Formatter.formatShortFileSize(this, bytesDone) + " / " + Formatter.formatShortFileSize(this, bytesTotal)
            } else {
                TEXT_PREPARING
            }
            updateNotification(text, bytesDone, bytesTotal, indeterminate = bytesTotal <= 0L)
        }
    }

    private fun broadcast(
        phase: String,
        bytesDone: Long = 0L,
        bytesTotal: Long = 0L,
        tilesDone: Long = 0L,
        tilesTotal: Long = 0L,
        message: String? = null,
        name: String? = null,
        band: Int = -1,
        operation: String = OP_DOWNLOAD,
    ) {
        val intent = Intent(ACTION_PROGRESS)
            .setPackage(packageName)
            .putExtra(EXTRA_PHASE, phase)
            .putExtra(EXTRA_OPERATION, operation)
            .putExtra(EXTRA_BYTES_DONE, bytesDone)
            .putExtra(EXTRA_BYTES_TOTAL, bytesTotal)
            .putExtra(EXTRA_TILES_DONE, tilesDone.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .putExtra(EXTRA_TILES_TOTAL, tilesTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .putExtra(EXTRA_PART_INDEX, partIndex)
            .putExtra(EXTRA_PART_COUNT, partsTotal)
            .putExtra(EXTRA_BAND, band)
        if (message != null) intent.putExtra(EXTRA_MESSAGE, message)
        if (name != null) intent.putExtra(EXTRA_NAME, name)
        sendBroadcast(intent)
    }

    private fun describe(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    // ---- connectivity -----------------------------------------------------------------------------

    /** Needs `ACCESS_NETWORK_STATE`; without it the check is skipped (treated as unmetered). */
    private fun isActiveNetworkMetered(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            cm.isActiveNetworkMetered
        } catch (e: SecurityException) {
            false
        }
    }

    // ---- foreground / notifications -------------------------------------------------------------

    private fun ensureForeground(title: String = TITLE_DOWNLOADING): Boolean {
        if (foreground) return true
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildProgressNotification(TEXT_PREPARING, 0L, 0L, indeterminate = true, title = title),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            foreground = true
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun finishForeground() {
        if (!foreground) return
        foreground = false
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Already stopped.
        }
    }

    private fun stopIfIdle() {
        if (downloadJob?.isActive != true && estimateJob?.isActive != true && maintenanceJob?.isActive != true) {
            finishForeground()
            stopSelf()
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        text: String,
        done: Long,
        total: Long,
        indeterminate: Boolean,
        title: String = TITLE_DOWNLOADING,
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            Intent(this, MapDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progress = if (total > 0L) (done * PROGRESS_MAX / total).toInt().coerceIn(0, PROGRESS_MAX) else 0
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(PROGRESS_MAX, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, ACTION_LABEL_CANCEL, cancelIntent)
        launchIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    @SuppressLint("MissingPermission") // the foreground notification is exempt from POST_NOTIFICATIONS
    private fun updateNotification(
        text: String,
        done: Long,
        total: Long,
        indeterminate: Boolean,
        title: String = TITLE_DOWNLOADING,
    ) {
        if (!foreground) return
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildProgressNotification(text, done, total, indeterminate, title))
        } catch (e: Exception) {
            // A notification failure must never abort the download.
        }
    }

    @SuppressLint("MissingPermission") // silently dropped when POST_NOTIFICATIONS is denied
    private fun showResultNotification(title: String, text: String) {
        try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
            launchIntent()?.let { builder.setContentIntent(it) }
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID_RESULT, builder.build())
        } catch (e: Exception) {
            // Optional.
        }
    }

    /** Opens the app's launcher activity without referencing UI classes from this process. */
    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_ESTIMATE = "com.fablab503.velotrack.download.ESTIMATE"
        const val ACTION_START = "com.fablab503.velotrack.download.START"
        const val ACTION_CANCEL = "com.fablab503.velotrack.download.CANCEL"

        /** Intent data: the MBTiles document (`content:` Uri with read permission granted). */
        const val ACTION_IMPORT = "com.fablab503.velotrack.download.IMPORT"

        /** Deletes the region [EXTRA_REGION_ID] and its tiles (see `MapLibrary.deleteRegion`). */
        const val ACTION_DELETE_REGION = "com.fablab503.velotrack.download.DELETE_REGION"

        /** Removes every region and every tile (see `MapLibrary.clearAll`). */
        const val ACTION_CLEAR = "com.fablab503.velotrack.download.CLEAR"

        /** Broadcast (package-local) carrying [EXTRA_PHASE], [EXTRA_OPERATION] and the progress extras. */
        const val ACTION_PROGRESS = "com.fablab503.velotrack.download.PROGRESS"

        /** Long: `map_regions.id` for [ACTION_DELETE_REGION]. */
        const val EXTRA_REGION_ID = "com.fablab503.velotrack.download.extra.REGION_ID"

        /** String: one of the OP_* values; which kind of job a broadcast belongs to. */
        const val EXTRA_OPERATION = "com.fablab503.velotrack.download.extra.OPERATION"

        const val OP_DOWNLOAD = "download"
        const val OP_IMPORT = "import"
        const val OP_DELETE = "delete"
        const val OP_CLEAR = "clear"

        /** String: region name shown in Map data. */
        const val EXTRA_NAME = "com.fablab503.velotrack.download.extra.NAME"

        /** Double extras for a circle around a point. */
        const val EXTRA_LAT = "com.fablab503.velotrack.download.extra.LAT"
        const val EXTRA_LON = "com.fablab503.velotrack.download.extra.LON"
        const val EXTRA_RADIUS_KM = "com.fablab503.velotrack.download.extra.RADIUS_KM"

        /** Double extras for an explicit bounding box (presets). */
        const val EXTRA_WEST = "com.fablab503.velotrack.download.extra.WEST"
        const val EXTRA_SOUTH = "com.fablab503.velotrack.download.extra.SOUTH"
        const val EXTRA_EAST = "com.fablab503.velotrack.download.extra.EAST"
        const val EXTRA_NORTH = "com.fablab503.velotrack.download.extra.NORTH"

        /** Int: index into [Bands.ALL]; when absent, derived from the radius. Also set on every broadcast. */
        const val EXTRA_BAND = "com.fablab503.velotrack.download.extra.BAND"

        /**
         * A multi-part download: four parallel double arrays and the matching names, one entry per
         * piece. Present only when a country was too large to plan in one pass; without them a
         * start command means the single area described by the extras above, exactly as before.
         */
        const val EXTRA_PART_WESTS = "com.fablab503.velotrack.download.extra.PART_WESTS"
        const val EXTRA_PART_SOUTHS = "com.fablab503.velotrack.download.extra.PART_SOUTHS"
        const val EXTRA_PART_EASTS = "com.fablab503.velotrack.download.extra.PART_EASTS"
        const val EXTRA_PART_NORTHS = "com.fablab503.velotrack.download.extra.PART_NORTHS"
        const val EXTRA_PART_NAMES = "com.fablab503.velotrack.download.extra.PART_NAMES"

        /** Int: which piece is running and how many there are, so the screen can say "3 of 9". */
        const val EXTRA_PART_INDEX = "com.fablab503.velotrack.download.extra.PART_INDEX"
        const val EXTRA_PART_COUNT = "com.fablab503.velotrack.download.extra.PART_COUNT"

        /** Boolean: proceed even on a metered network although `wifiOnlyDownloads` is set. */
        const val EXTRA_ALLOW_METERED = "com.fablab503.velotrack.download.extra.ALLOW_METERED"

        /** String: one of the PHASE_* values. */
        const val EXTRA_PHASE = "com.fablab503.velotrack.download.extra.PHASE"

        /** Long extras. */
        const val EXTRA_BYTES_DONE = "com.fablab503.velotrack.download.extra.BYTES_DONE"
        const val EXTRA_BYTES_TOTAL = "com.fablab503.velotrack.download.extra.BYTES_TOTAL"

        /** Int extras. */
        const val EXTRA_TILES_DONE = "com.fablab503.velotrack.download.extra.TILES_DONE"
        const val EXTRA_TILES_TOTAL = "com.fablab503.velotrack.download.extra.TILES_TOTAL"

        /** String: build key (`20260907.pmtiles`) on estimated/downloading/done, error text on failed. */
        const val EXTRA_MESSAGE = "com.fablab503.velotrack.download.extra.MESSAGE"

        const val PHASE_ESTIMATING = "estimating"
        const val PHASE_ESTIMATED = "estimated"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_DONE = "done"
        const val PHASE_FAILED = "failed"
        const val PHASE_CANCELLED = "cancelled"

        /** [EXTRA_MESSAGE] values of `failed` broadcasts the UI may react to. */
        const val MSG_METERED = "metered"
        const val MSG_BUSY = "busy"
        const val MSG_BAD_REQUEST = "bad request"
        const val MSG_FOREGROUND = "foreground service not allowed"

        const val CHANNEL_ID = "velotrack.download"
        const val NOTIFICATION_ID = 20
        const val NOTIFICATION_ID_RESULT = 21

        const val PARALLELISM = 6

        private const val CHANNEL_NAME = "Map downloads"
        private const val TITLE_DOWNLOADING = "Downloading map"
        private const val TITLE_DONE = "Map downloaded"
        private const val TITLE_FAILED = "Map download failed"
        private const val TITLE_IMPORTING = "Importing map"
        private const val TITLE_IMPORT_DONE = "Map imported"
        private const val TITLE_IMPORT_FAILED = "Map import failed"
        private const val TEXT_IMPORT_DONE = "The file's tiles were added to the map data."
        private const val TEXT_PREPARING = "Preparing…"
        private const val ACTION_LABEL_CANCEL = "Cancel"
        private const val DEFAULT_NAME = "Map"

        private const val REQUEST_CANCEL = 30
        private const val REQUEST_OPEN = 31
        private const val PROGRESS_MAX = 1000
        private const val PUBLISH_INTERVAL_MS = 500L

        /** Asks for the exact size of a circle download; answer arrives as an `estimated` broadcast. */
        fun estimate(context: Context, name: String, bandIndex: Int, lat: Double, lon: Double, radiusKm: Double): Boolean =
            sendEstimate(context, circleIntent(context, ACTION_ESTIMATE, name, bandIndex, lat, lon, radiusKm, false))

        /** Asks for the exact size of a bounding-box (preset) download. */
        fun estimate(context: Context, name: String, bandIndex: Int, bbox: Mercator.BBox): Boolean =
            sendEstimate(context, bboxIntent(context, ACTION_ESTIMATE, name, bandIndex, bbox, false))

        /** Starts a circle download (call from a visible activity). */
        fun start(context: Context, name: String, bandIndex: Int, lat: Double, lon: Double, radiusKm: Double, allowMetered: Boolean = false) {
            ContextCompat.startForegroundService(
                context, circleIntent(context, ACTION_START, name, bandIndex, lat, lon, radiusKm, allowMetered),
            )
        }

        /** Starts a bounding-box (preset) download (call from a visible activity). */
        fun start(context: Context, name: String, bandIndex: Int, bbox: Mercator.BBox, allowMetered: Boolean = false) {
            ContextCompat.startForegroundService(context, bboxIntent(context, ACTION_START, name, bandIndex, bbox, allowMetered))
        }

        /**
         * Starts a download made of several pieces, run one after another (call from a visible
         * activity). Used when an area is too large for [PmTilesRemote.plan] to handle in one pass;
         * see [splitForBudget]. Each piece is stored as its own region under its own name, so a
         * half-finished country leaves behind exactly the pieces that did finish rather than
         * nothing.
         */
        fun start(
            context: Context,
            bandIndex: Int,
            parts: List<Pair<String, Mercator.BBox>>,
            allowMetered: Boolean = false,
        ) {
            if (parts.isEmpty()) return
            if (parts.size == 1) {
                start(context, parts[0].first, bandIndex, parts[0].second, allowMetered)
                return
            }
            val intent = Intent(context, MapDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BAND, bandIndex)
                .putExtra(EXTRA_ALLOW_METERED, allowMetered)
                .putExtra(EXTRA_NAME, parts[0].first)
                .putExtra(EXTRA_PART_NAMES, parts.map { it.first }.toTypedArray())
                .putExtra(EXTRA_PART_WESTS, parts.map { it.second.west }.toDoubleArray())
                .putExtra(EXTRA_PART_SOUTHS, parts.map { it.second.south }.toDoubleArray())
                .putExtra(EXTRA_PART_EASTS, parts.map { it.second.east }.toDoubleArray())
                .putExtra(EXTRA_PART_NORTHS, parts.map { it.second.north }.toDoubleArray())
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            try {
                context.startService(Intent(context, MapDownloadService::class.java).setAction(ACTION_CANCEL))
            } catch (e: IllegalStateException) {
                // App in background and service not running: nothing to cancel.
            }
        }

        /**
         * Imports an MBTiles document into the band files in the `:download` process (call from a
         * visible activity). The read grant the activity holds on [uri] is forwarded to the service.
         * Result: `done` / `failed` / `cancelled` broadcasts with [EXTRA_OPERATION] = [OP_IMPORT].
         */
        fun importFile(context: Context, uri: Uri) {
            val intent = Intent(context, MapDownloadService::class.java)
                .setAction(ACTION_IMPORT)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Deletes one region in the `:download` process; false when the service could not be started. */
        fun deleteRegion(context: Context, regionId: Long): Boolean = sendEstimate(
            context,
            Intent(context, MapDownloadService::class.java)
                .setAction(ACTION_DELETE_REGION)
                .putExtra(EXTRA_REGION_ID, regionId),
        )

        /** Clears all map data in the `:download` process; false when the service could not be started. */
        fun clearAll(context: Context): Boolean =
            sendEstimate(context, Intent(context, MapDownloadService::class.java).setAction(ACTION_CLEAR))

        /** `startService` for short, non-foreground jobs (estimate, delete, clear). */
        private fun sendEstimate(context: Context, intent: Intent): Boolean = try {
            context.startService(intent) != null
        } catch (e: IllegalStateException) {
            false
        }

        private fun circleIntent(
            context: Context, action: String, name: String, bandIndex: Int,
            lat: Double, lon: Double, radiusKm: Double, allowMetered: Boolean,
        ): Intent = Intent(context, MapDownloadService::class.java)
            .setAction(action)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_BAND, bandIndex)
            .putExtra(EXTRA_LAT, lat)
            .putExtra(EXTRA_LON, lon)
            .putExtra(EXTRA_RADIUS_KM, radiusKm)
            .putExtra(EXTRA_ALLOW_METERED, allowMetered)

        private fun bboxIntent(
            context: Context, action: String, name: String, bandIndex: Int, bbox: Mercator.BBox, allowMetered: Boolean,
        ): Intent = Intent(context, MapDownloadService::class.java)
            .setAction(action)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_BAND, bandIndex)
            .putExtra(EXTRA_WEST, bbox.west)
            .putExtra(EXTRA_SOUTH, bbox.south)
            .putExtra(EXTRA_EAST, bbox.east)
            .putExtra(EXTRA_NORTH, bbox.north)
            .putExtra(EXTRA_ALLOW_METERED, allowMetered)
    }
}
