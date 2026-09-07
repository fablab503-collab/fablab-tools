package com.fablab503.velotrack.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.R
import com.fablab503.velotrack.location.GpsSource
import com.fablab503.velotrack.location.HeadingEstimator
import com.fablab503.velotrack.model.GpsFix
import com.fablab503.velotrack.model.GpsStatus
import com.fablab503.velotrack.model.RecordingStatus
import com.fablab503.velotrack.model.RideState
import com.fablab503.velotrack.model.TrackPoint
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.TrackDatabase
import com.fablab503.velotrack.storage.TrackRepository
import com.fablab503.velotrack.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that records a ride: GPS fixes go through [PointFilter] and [RideStats],
 * stored points are batched into [TrackRepository], and [RideSession] is updated on every fix so
 * the UI can render the HUD. Everything except database work runs on the main thread; database
 * work runs on a single-thread dispatcher so writes stay ordered.
 */
class RecordingService : LifecycleService(), GpsSource.Listener {

    private lateinit var prefs: Prefs
    private lateinit var gps: GpsSource
    private lateinit var database: TrackDatabase
    private lateinit var repo: TrackRepository
    private val dbDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredUptimeMs: Long = 0L
    private var foregroundStarted = false

    // Ride state (main thread only)
    private var trackId: Long? = null
    private var filter: PointFilter? = null
    private var stats: RideStats = RideStats()
    private val heading = HeadingEstimator()
    private var status: RecordingStatus = RecordingStatus.IDLE
    private var manualPaused = false
    private var lastAcceptedElapsedNs: Long? = null
    private var gpsStatus = GpsStatus()
    private var starting = false
    private var stopping = false

    // Storage batching (main thread only; the DB call itself runs on dbDispatcher)
    private val pending = ArrayList<TrackPoint>()
    private var startJob: Job? = null
    private var flushJob: Job? = null
    private var lastFlushUptimeMs: Long = 0L
    private var lastNotificationUptimeMs: Long = 0L
    private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
        RideSession.serviceRunning = true
        prefs = Prefs(this)
        gps = GpsSource(this)
        database = TrackDatabase(this)
        repo = TrackRepository(database)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (intent?.action) {
            ACTION_START -> handleStart(intent, lookupUnfinished = false)
            ACTION_PAUSE -> {
                setManualPause(true)
                START_STICKY
            }
            ACTION_RESUME -> {
                setManualPause(false)
                START_STICKY
            }
            ACTION_STOP -> {
                stopRecording()
                START_NOT_STICKY
            }
            else -> {
                // Restarted by the system after being killed (START_STICKY redelivers a null
                // intent): try to continue the unfinished track, otherwise stop quietly.
                if (trackId == null && !starting) handleStart(null, lookupUnfinished = true) else START_STICKY
            }
        }
    }

    override fun onDestroy() {
        gps.stop()
        releaseWakeLock()
        RideSession.serviceRunning = false
        if (RideSession.state.value.status != RecordingStatus.IDLE) {
            RideSession.update { it.copy(status = RecordingStatus.IDLE) }
        }
        dbDispatcher.close()
        super.onDestroy()
    }

    // ---- start / pause / stop -----------------------------------------------------------------

    private fun handleStart(intent: Intent?, lookupUnfinished: Boolean): Int {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
                )
                foregroundStarted = true
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (trackId != null || starting || stopping) {
            // Already recording (or shutting down): just re-publish the current state.
            publishState(RideSession.state.value.lastFix)
            return START_STICKY
        }

        acquireWakeLock()
        gps.start(this)

        val resumeId = intent?.getLongExtra(EXTRA_RESUME_TRACK_ID, -1L) ?: -1L
        beginTrack(resumeId, lookupUnfinished)
        return START_STICKY
    }

    private class StartResult(val trackId: Long, val startSegment: Int, val stats: RideStats)

    private fun beginTrack(resumeId: Long, lookupUnfinished: Boolean) {
        starting = true
        stopping = false
        manualPaused = false
        lastAcceptedElapsedNs = null
        pending.clear()
        lastError = null
        val nowMs = System.currentTimeMillis()

        startJob = lifecycleScope.launch {
            var error: String? = null
            val result: StartResult? = try {
                withContext(dbDispatcher) {
                    var id = resumeId
                    if (id < 0 && lookupUnfinished) id = repo.findUnfinished()?.id ?: -1L
                    val summary = if (id >= 0) repo.getTrack(id) else null
                    when {
                        summary != null -> {
                            val segment = repo.lastSegment(id) + 1
                            val rebuilt = RideStats.rebuild(
                                repo.pointsAsSequence(id),
                                summary.startedAtMs,
                                summary.movingMs,
                            )
                            StartResult(id, segment, rebuilt)
                        }
                        lookupUnfinished -> null // nothing to resume after a system restart
                        else -> {
                            val newId = repo.createTrack(trackName(nowMs), nowMs)
                            val fresh = RideStats()
                            fresh.start(nowMs)
                            StartResult(newId, 0, fresh)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                null
            }

            starting = false
            if (result == null) {
                lastError = error
                if (error != null) {
                    RideSession.update { it.copy(error = error) }
                }
                stopRecording()
                return@launch
            }

            trackId = result.trackId
            stats = result.stats
            if (stopping) {
                // STOP arrived while the track was being prepared: the stop coroutine (which
                // waits for this job) finishes the track; do not start filtering.
                return@launch
            }
            filter = PointFilter(filterConfig(), result.startSegment)
            status = RecordingStatus.RECORDING
            publishState(RideSession.state.value.lastFix)
            updateNotification(force = true)
        }
    }

    private fun setManualPause(paused: Boolean) {
        if (trackId == null && !starting) {
            if (!foregroundStarted) stopSelf()
            return
        }
        if (manualPaused == paused) return
        manualPaused = paused
        filter?.forcePause(paused)
        if (paused) {
            flush(force = true)
            status = RecordingStatus.PAUSED
        } else {
            // The next fix corrects this to AUTO_PAUSED when the rider is still standing.
            status = RecordingStatus.RECORDING
        }
        publishState(RideSession.state.value.lastFix)
        updateNotification(force = true)
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        gps.stop()

        status = RecordingStatus.IDLE
        // Stats stay visible until the next start; only the status changes.
        val preliminary = stats.snapshot(System.currentTimeMillis())
        RideSession.update {
            it.copy(status = RecordingStatus.IDLE, stats = preliminary, gps = gpsStatus, error = lastError)
        }

        lifecycleScope.launch {
            // Wait for an in-flight start (track creation / rebuild) and flush before finishing.
            startJob?.join()
            flushJob?.join()
            val id = trackId
            val nowMs = System.currentTimeMillis()
            val snapshot = stats.snapshot(nowMs)
            if (id != null) {
                val batch = ArrayList(pending)
                pending.clear()
                val error = try {
                    withContext(dbDispatcher) {
                        if (batch.isNotEmpty()) repo.appendPoints(id, batch)
                        repo.finishTrack(id, snapshot, nowMs)
                    }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                if (error != null) lastError = error
            }
            val finalError = lastError
            RideSession.update {
                it.copy(status = RecordingStatus.IDLE, trackId = id, stats = snapshot, error = finalError)
            }
            trackId = null
            filter = null
            releaseWakeLock()
            RideSession.serviceRunning = false
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    // ---- GPS callbacks (main thread) ----------------------------------------------------------

    override fun onFix(fix: GpsFix) {
        val headingDeg = heading.update(fix)
        val f = filter
        if (f == null || stopping) {
            RideSession.update { it.copy(lastFix = fix, headingDeg = headingDeg, gps = gpsStatus) }
            return
        }

        val decision = f.offer(fix)
        if (decision.accepted) {
            val newStatus = when {
                manualPaused -> RecordingStatus.PAUSED
                decision.autoPaused -> RecordingStatus.AUTO_PAUSED
                else -> RecordingStatus.RECORDING
            }
            val prevNs = lastAcceptedElapsedNs
            if (prevNs != null && status == RecordingStatus.RECORDING && newStatus == RecordingStatus.RECORDING) {
                stats.addMovingTime((fix.elapsedNs - prevNs) / 1_000_000L)
            }
            lastAcceptedElapsedNs = fix.elapsedNs
            status = newStatus
            val point = decision.point
            if (point != null) {
                stats.addStored(point, fix.verticalAccuracyM)
                pending.add(point)
            }
        }

        publishState(fix)
        flush(force = false)
        updateNotification(force = false)
        rearmWakeLockIfStale()
    }

    override fun onStatus(status: GpsStatus) {
        gpsStatus = status
        RideSession.update { it.copy(gps = status) }
    }

    private fun publishState(lastFix: GpsFix?) {
        val snapshot = stats.snapshot(System.currentTimeMillis())
        val currentStatus = status
        val id = trackId
        val headingDeg = heading.headingDeg
        val gpsNow = gpsStatus
        val error = lastError
        RideSession.update {
            RideState(
                status = currentStatus,
                trackId = id,
                stats = snapshot,
                lastFix = lastFix ?: it.lastFix,
                headingDeg = headingDeg ?: it.headingDeg,
                gps = gpsNow,
                error = error,
            )
        }
    }

    // ---- storage batching ---------------------------------------------------------------------

    /** Writes the pending points every [FLUSH_POINTS] points or [FLUSH_INTERVAL_MS], whichever comes first. */
    private fun flush(force: Boolean) {
        val id = trackId ?: return
        if (pending.isEmpty()) return
        if (flushJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (!force && pending.size < FLUSH_POINTS && now - lastFlushUptimeMs < FLUSH_INTERVAL_MS) return

        val batch = ArrayList(pending)
        pending.clear()
        val snapshot = stats.snapshot(System.currentTimeMillis())
        lastFlushUptimeMs = now

        flushJob = lifecycleScope.launch {
            val error = try {
                withContext(dbDispatcher) {
                    repo.appendPoints(id, batch)
                    repo.updateStats(id, snapshot)
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (error != null) {
                // Keep the ride: put the batch back (in order) and retry on the next flush.
                pending.addAll(0, batch)
                if (lastError != error) {
                    lastError = error
                    RideSession.update { it.copy(error = error) }
                    updateNotification(force = true)
                }
            } else if (lastError != null) {
                lastError = null
                RideSession.update { it.copy(error = null) }
                updateNotification(force = true)
            }
        }
    }

    // ---- notification -------------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val snapshot = stats.snapshot(System.currentTimeMillis())
        val title = if (status == RecordingStatus.PAUSED) {
            getString(R.string.notif_paused_title)
        } else {
            getString(R.string.notif_recording_title)
        }
        val distanceKm = snapshot.distanceM / 1000.0
        val text = String.format(Locale.ROOT, "%.1f km", distanceKm) + "  " + formatDuration(snapshot.movingMs)

        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (lastError != null) {
            builder.setSubText(getString(R.string.notif_storage_error))
        }

        if (status == RecordingStatus.PAUSED) {
            builder.addAction(0, getString(R.string.notif_action_resume), servicePendingIntent(ACTION_RESUME, REQUEST_RESUME))
        } else {
            builder.addAction(0, getString(R.string.notif_action_pause), servicePendingIntent(ACTION_PAUSE, REQUEST_PAUSE))
        }
        builder.addAction(0, getString(R.string.notif_action_stop), servicePendingIntent(ACTION_STOP, REQUEST_STOP))
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Re-posts the foreground notification, at most every [NOTIFICATION_INTERVAL_MS] unless forced. */
    @SuppressLint("MissingPermission") // foreground-service notifications are exempt from POST_NOTIFICATIONS
    private fun updateNotification(force: Boolean) {
        if (!foregroundStarted || stopping) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUptimeMs < NOTIFICATION_INTERVAL_MS) return
        lastNotificationUptimeMs = now
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // A notification failure must never stop the recording.
        }
    }

    // ---- wake lock ----------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        try {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLockAcquiredUptimeMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            // Missing WAKE_LOCK permission or OEM quirk: recording continues without it.
        }
    }

    /** Re-arms the 12 h timeout once an hour so multi-day rides keep the lock. */
    private fun rearmWakeLockIfStale() {
        if (wakeLock == null) return
        if (SystemClock.elapsedRealtime() - wakeLockAcquiredUptimeMs >= WAKE_LOCK_REARM_MS) acquireWakeLock()
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            // Already released.
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun filterConfig(): FilterConfig = FilterConfig(
        maxAccuracyM = prefs.accuracyCutoffM,
        autoPauseEnabled = prefs.autoPauseEnabled,
    )

    private fun trackName(nowMs: Long): String =
        SimpleDateFormat("'Ride' yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(nowMs))

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    }

    companion object {
        const val ACTION_START = "com.fablab503.velotrack.action.START"
        const val ACTION_PAUSE = "com.fablab503.velotrack.action.PAUSE"
        const val ACTION_RESUME = "com.fablab503.velotrack.action.RESUME"
        const val ACTION_STOP = "com.fablab503.velotrack.action.STOP"

        /** Long extra for [ACTION_START]: id of an unfinished track to continue, or -1 for a new one. */
        const val EXTRA_RESUME_TRACK_ID = "com.fablab503.velotrack.extra.RESUME_TRACK_ID"

        const val CHANNEL_ID = "velotrack.recording"
        const val NOTIFICATION_ID = 1

        private const val REQUEST_OPEN = 10
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_RESUME = 12
        private const val REQUEST_STOP = 13

        private const val FLUSH_POINTS = 20
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val NOTIFICATION_INTERVAL_MS = 5_000L

        private const val WAKE_LOCK_TAG = "VeloTrack::Recording"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L
        private const val WAKE_LOCK_REARM_MS = 60L * 60L * 1000L

        /** Starts recording (must be called from a visible activity after the permission grant). */
        fun start(context: Context, resumeTrackId: Long?) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESUME_TRACK_ID, resumeTrackId ?: -1L)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
