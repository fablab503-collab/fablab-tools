package com.fablab503.velotrack.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.core.R
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

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Asks whether the ride is over after a stretch of no movement. Held here rather than in the
     * activity because a forgotten ride is exactly the case where nobody is looking at the screen.
     */
    private val reminder = InactivityReminder(0L)
    private var reminderPosted = false
    private val reminderRunnable = Runnable { checkInactivity() }

    /**
     * Settings that change how the ride is recorded rather than how it is shown, so they have to
     * reach a running service: energy saver changes the GPS rate mid-ride, and the reminder
     * timeout re-arms the question.
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_ENERGY_SAVER -> applyGpsRate()
            Prefs.KEY_INACTIVITY_REMINDER -> {
                reminder.afterMs = reminderAfterMs()
                scheduleInactivityCheck()
            }
            else -> Unit // everything else is read when the next ride starts
        }
    }

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
        database = TrackDatabase.get(this)
        repo = TrackRepository(database)
        reminder.afterMs = reminderAfterMs()
        prefs.registerListener(prefsListener)
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
            ACTION_KEEP_RECORDING -> {
                // "Yes, I am still riding": clear the question and start the clock again.
                //
                // The guard matters. This arrives from a notification the rider may be tapping
                // after the ride already ended - a shade that was not refreshed, or a stop that
                // landed a second earlier. Without it the service would be created purely to
                // answer the tap, publish serviceRunning = true, never call startForeground, and
                // leave the app convinced a ride was in progress that no longer exists.
                if (trackId == null && !starting) {
                    forceCancelReminderNotification()
                    if (!foregroundStarted) stopSelf()
                    START_NOT_STICKY
                } else {
                    cancelReminderNotification()
                    reminder.snooze(SystemClock.elapsedRealtime())
                    scheduleInactivityCheck()
                    START_STICKY
                }
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
        handler.removeCallbacks(reminderRunnable)
        cancelReminderNotification()
        prefs.unregisterListener(prefsListener)
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
        gps.start(this, gpsIntervalMs())

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
        reminder.reset()
        cancelReminderNotification()
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
                            // A ride being continued, whether it was left open by a crash or
                            // finished days ago and picked up again from the ride list. Both are
                            // the same operation: mark the row as recording, start a fresh segment
                            // so nothing draws a straight line across the gap, and rebuild the
                            // totals from the points already stored.
                            repo.reopenTrack(id)
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
            // The clock starts at the start of the ride, not at the first fix: a recording begun
            // ten minutes ago that has never moved is worth asking about too.
            reminder.onMoving(SystemClock.elapsedRealtime())
            scheduleInactivityCheck()
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
        val now = SystemClock.elapsedRealtime()
        if (paused) {
            flush(force = true)
            status = RecordingStatus.PAUSED
            // Pausing on purpose is a rider saying "I am here". Give the question a full timeout
            // from this moment rather than counting the minutes before the button was pressed.
            reminder.snooze(now)
        } else {
            // The next fix corrects this to AUTO_PAUSED when the rider is still standing.
            status = RecordingStatus.RECORDING
            reminder.onMoving(now)
            cancelReminderNotification()
        }
        publishState(RideSession.state.value.lastFix)
        updateNotification(force = true)
        scheduleInactivityCheck()
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        gps.stop()
        handler.removeCallbacks(reminderRunnable)
        reminder.reset()
        cancelReminderNotification()

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
        lastSpeedMps = decision.speedMps
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
        noteActivity()
        rearmWakeLockIfStale()
    }

    override fun onStatus(status: GpsStatus) {
        gpsStatus = status
        RideSession.update { it.copy(gps = status) }
    }

    /** Effective speed of the last fix as decided by [PointFilter]; shown by the HUD instead of the raw receiver speed. */
    private var lastSpeedMps: Float? = null

    private fun publishState(lastFix: GpsFix?) {
        val snapshot = stats.snapshot(System.currentTimeMillis())
        val currentStatus = status
        val id = trackId
        val headingDeg = heading.headingDeg
        val gpsNow = gpsStatus
        val error = lastError
        val speed = lastSpeedMps
        val segmentNow = filter?.segment ?: 0
        RideSession.update {
            RideState(
                status = currentStatus,
                trackId = id,
                stats = snapshot,
                lastFix = lastFix ?: it.lastFix,
                headingDeg = headingDeg ?: it.headingDeg,
                gps = gpsNow,
                error = error,
                speedMps = speed ?: it.speedMps,
                segment = segmentNow,
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
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val ongoing = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        )
        ongoing.setShowBadge(false)
        manager.createNotificationChannel(ongoing)

        // Its own channel, and a louder one: the whole point of this notification is to reach a
        // rider who is not looking at the phone. Separate so it can be silenced on its own without
        // taking the ongoing ride notification with it.
        val reminderChannel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            getString(R.string.notif_channel_reminder),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        reminderChannel.description = getString(R.string.notif_channel_reminder_desc)
        manager.createNotificationChannel(reminderChannel)
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

        val contentIntent = openAppIntent()

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

    // ---- energy saver -------------------------------------------------------------------------

    private fun gpsIntervalMs(): Long =
        if (prefs.energySaver) GpsSource.SAVER_INTERVAL_MS else GpsSource.DEFAULT_INTERVAL_MS

    /**
     * Re-registers GPS at the rate energy saver asks for. Only while a ride is actually running:
     * calling [GpsSource.start] otherwise would switch the receiver on for nothing.
     */
    private fun applyGpsRate() {
        if (trackId == null && !starting) return
        if (stopping) return
        gps.start(this, gpsIntervalMs())
    }

    // ---- "are you still riding?" --------------------------------------------------------------

    private fun reminderAfterMs(): Long =
        prefs.inactivityReminderMin.coerceAtLeast(0).toLong() * 60_000L

    /** A fix arrived: whether it counted as movement decides the reminder clock. */
    private fun noteActivity() {
        val now = SystemClock.elapsedRealtime()
        if (status == RecordingStatus.RECORDING) {
            if (reminderPosted) cancelReminderNotification()
            reminder.onMoving(now)
            scheduleInactivityCheck()
            return
        }
        // Paused or auto-paused. Check here as well as on the timer so a phone that is producing
        // fixes never waits for the next tick.
        if (reminder.onStill(now)) postReminder()
        scheduleInactivityCheck()
    }

    /**
     * Runs the same check from a timer, so the question still arrives when no fixes are coming at
     * all - a phone in a bag indoors, which is exactly the forgotten-ride case.
     */
    private fun checkInactivity() {
        if (status == RecordingStatus.IDLE || stopping) return
        if (reminder.onStill(SystemClock.elapsedRealtime())) postReminder()
        scheduleInactivityCheck()
    }

    private fun scheduleInactivityCheck() {
        handler.removeCallbacks(reminderRunnable)
        if (status == RecordingStatus.IDLE || stopping) return
        val delay = reminder.nextCheckDelayMs(SystemClock.elapsedRealtime()) ?: return
        // A floor so a zero-delay answer cannot spin the handler, and a ceiling so a long timeout
        // is still re-checked periodically rather than trusting one alarm across hours of sleep.
        handler.postDelayed(reminderRunnable, delay.coerceIn(1_000L, REMINDER_MAX_CHECK_MS))
    }

    @SuppressLint("MissingPermission") // guarded by the runtime check below
    private fun postReminder() {
        if (!canPostNotifications()) return
        val minutes = prefs.inactivityReminderMin.coerceAtLeast(1)
        val snapshot = stats.snapshot(System.currentTimeMillis())
        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(getString(R.string.notif_still_recording_title))
            .setContentText(
                getString(
                    R.string.notif_still_recording_text,
                    minutes,
                    String.format(Locale.ROOT, "%.1f km", snapshot.distanceM / 1000.0),
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    getString(
                        R.string.notif_still_recording_text,
                        minutes,
                        String.format(Locale.ROOT, "%.1f km", snapshot.distanceM / 1000.0),
                    ),
                ),
            )
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                0,
                getString(R.string.notif_action_finish_ride),
                servicePendingIntent(ACTION_STOP, REQUEST_REMINDER_STOP),
            )
            .addAction(
                0,
                getString(R.string.notif_action_keep_recording),
                servicePendingIntent(ACTION_KEEP_RECORDING, REQUEST_REMINDER_KEEP),
            )
            .build()
        try {
            getSystemService(NotificationManager::class.java)?.notify(REMINDER_NOTIFICATION_ID, notification)
            reminderPosted = true
        } catch (e: Exception) {
            // A notification failure must never stop the recording.
        }
    }

    /**
     * Clears the reminder whether or not *this* instance posted it. A service that was destroyed
     * and recreated has `reminderPosted = false` while the notification is still on screen, so the
     * cheap guard in [cancelReminderNotification] would leave it there for ever.
     */
    private fun forceCancelReminderNotification() {
        reminderPosted = true
        cancelReminderNotification()
    }

    private fun cancelReminderNotification() {
        if (!reminderPosted) return
        reminderPosted = false
        try {
            getSystemService(NotificationManager::class.java)?.cancel(REMINDER_NOTIFICATION_ID)
        } catch (e: Exception) {
            // Nothing to do; it is only a notification.
        }
    }

    /**
     * The ongoing ride notification is exempt from POST_NOTIFICATIONS because it belongs to a
     * foreground service. This one is not, so it has to be checked or it is silently dropped.
     */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Resolved from the package manager rather than named directly: this service is shared by the
     * phone and the watch, whose launcher activities are different classes. Same pattern, and same
     * reason, as MapDownloadService.launchIntent(). The flags keep a tap returning to a running
     * ride instead of stacking a second copy of it.
     */
    private fun openAppIntent(): PendingIntent? {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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

        /** "Keep recording" on the inactivity reminder: dismiss it and start its clock again. */
        const val ACTION_KEEP_RECORDING = "com.fablab503.velotrack.action.KEEP_RECORDING"

        /** Long extra for [ACTION_START]: id of an unfinished track to continue, or -1 for a new one. */
        const val EXTRA_RESUME_TRACK_ID = "com.fablab503.velotrack.extra.RESUME_TRACK_ID"

        const val CHANNEL_ID = "velotrack.recording"
        const val NOTIFICATION_ID = 1

        const val REMINDER_CHANNEL_ID = "velotrack.reminder"
        const val REMINDER_NOTIFICATION_ID = 2

        private const val REQUEST_OPEN = 10
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_RESUME = 12
        private const val REQUEST_STOP = 13
        private const val REQUEST_REMINDER_STOP = 14
        private const val REQUEST_REMINDER_KEEP = 15

        private const val FLUSH_POINTS = 20
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val NOTIFICATION_INTERVAL_MS = 5_000L

        /** Longest a pending inactivity check may sleep before being re-evaluated. */
        private const val REMINDER_MAX_CHECK_MS = 5L * 60_000L

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
