package com.rawsmusic.transcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rawsmusic.MainActivity
import com.rawsmusic.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground owner for long-running offline audio conversion.
 *
 * [AudioTranscodeQueue] remains the single ordering/cancellation owner. This service only keeps the
 * process eligible for long local media processing, holds a bounded partial wake lock while work is
 * active, and projects the queue state into one notification. Queue state is hydrated before the
 * observer is attached. START_NOT_STICKY remains deliberate: pending entries are restored when the
 * app/service is legitimately started again, while Android must not implicitly recreate an encoder
 * process and guess how to continue an interrupted partial file.
 */
class AudioTranscodeForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queueObserver: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var lastNotificationPermille = -1
    private var lastNotificationTaskId = ""
    private var lastNotificationStage: AudioTranscodeStage? = null
    private var lastNotificationActiveCount = -1

    override fun onCreate() {
        super.onCreate()
        AudioTranscodeQueue.initialize(applicationContext)
        ensureChannel()
        queueObserver = serviceScope.launch {
            AudioTranscodeQueue.entries.collectLatest(::onQueueChanged)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_TASK_ID)
                    ?.takeIf(String::isNotBlank)
                    ?.let(AudioTranscodeQueue::cancel)
            }
            ACTION_STOP_IF_IDLE -> stopIfIdle()
            else -> Unit
        }

        val active = activeEntries(AudioTranscodeQueue.entries.value)
        if (active.isNotEmpty()) {
            ensureWakeLock()
            publishForeground(active, force = true)
            AudioTranscodeQueue.resumePending()
        } else {
            stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun onQueueChanged(entries: List<AudioTranscodeQueueEntry>) {
        val active = activeEntries(entries)
        if (active.isEmpty()) {
            AudioTranscodeQueue.suspendPendingExecution()
            releaseWakeLock()
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            stopSelf()
            return
        }

        ensureWakeLock()
        publishForeground(active, force = false)
    }

    private fun publishForeground(
        active: List<AudioTranscodeQueueEntry>,
        force: Boolean,
    ) {
        val current = active.firstOrNull { it.state == AudioTranscodeQueueState.RUNNING }
            ?: active.first()
        val permille = current.progress.overallPermille.coerceIn(0, 1000)
        if (
            !force &&
            foregroundStarted &&
            current.id == lastNotificationTaskId &&
            current.progress.stage == lastNotificationStage &&
            active.size == lastNotificationActiveCount &&
            permille != 0 &&
            kotlin.math.abs(permille - lastNotificationPermille) < NOTIFICATION_STEP_PERMILLE
        ) {
            return
        }

        lastNotificationTaskId = current.id
        lastNotificationPermille = permille
        lastNotificationStage = current.progress.stage
        lastNotificationActiveCount = active.size
        val notification = buildNotification(current, active.size)
        if (!foregroundStarted) {
            startProcessingForeground(notification)
            foregroundStarted = true
        } else {
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun startProcessingForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // mediaProcessing was added after Android 14. dataSync is the compatible long-work
            // declaration for older releases and is also declared in the manifest for this service.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        current: AudioTranscodeQueueEntry,
        activeCount: Int,
    ): Notification {
        val running = current.state == AudioTranscodeQueueState.RUNNING
        val permille = current.progress.overallPermille.coerceIn(0, 1000)
        val sourceName = File(current.request.inputPath).name.ifBlank {
            getString(R.string.transcode_notification_unknown_source)
        }
        val stage = if (running) stageText(current.progress.stage) else {
            getString(R.string.transcode_notification_waiting)
        }
        val queueSuffix = if (activeCount > 1) {
            getString(R.string.transcode_notification_queue_suffix, activeCount - 1)
        } else {
            ""
        }
        val cancelIntent = PendingIntent.getService(
            this,
            current.id.hashCode(),
            Intent(this, AudioTranscodeForegroundService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TASK_ID, current.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val indeterminate = !running || permille <= 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(
                getString(
                    R.string.transcode_notification_title,
                    sourceName,
                    current.request.format.name,
                )
            )
            .setContentText("$stage$queueSuffix")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(1000, permille, indeterminate)
            .addAction(0, getString(R.string.common_cancel), cancelIntent)
            .setContentIntent(appPendingIntent())
            .build()
    }

    private fun stageText(stage: AudioTranscodeStage): String = getString(
        when (stage) {
            AudioTranscodeStage.QUEUED -> R.string.transcode_stage_queued
            AudioTranscodeStage.PROBING -> R.string.transcode_stage_probing
            AudioTranscodeStage.ENCODING -> R.string.transcode_stage_encoding
            AudioTranscodeStage.MIGRATING_METADATA -> R.string.transcode_stage_metadata
            AudioTranscodeStage.VERIFYING -> R.string.transcode_stage_verifying
            AudioTranscodeStage.COMMITTING -> R.string.transcode_stage_committing
            AudioTranscodeStage.COMPLETED -> R.string.transcode_stage_completed
            AudioTranscodeStage.FAILED -> R.string.transcode_stage_failed
            AudioTranscodeStage.CANCELLED -> R.string.transcode_stage_cancelled
        }
    )

    private fun appPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0x5452,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transcode_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.transcode_notification_channel_description)
                setSound(null, null)
            }
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun ensureWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun stopIfIdle() {
        if (activeEntries(AudioTranscodeQueue.entries.value).isNotEmpty()) return
        AudioTranscodeQueue.suspendPendingExecution()
        releaseWakeLock()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeEntries(AudioTranscodeQueue.entries.value).forEach { entry ->
            AudioTranscodeQueue.cancel(entry.id)
        }
        AudioTranscodeQueue.suspendPendingExecution()
        releaseWakeLock()
        stopSelf(startId)
    }

    override fun onDestroy() {
        queueObserver?.cancel()
        AudioTranscodeQueue.suspendPendingExecution()
        releaseWakeLock()
        serviceScope.cancel()
        foregroundStarted = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "rawsmusic_audio_transcode"
        private const val NOTIFICATION_ID = 0x545243
        private const val ACTION_SYNC = "com.rawsmusic.transcode.action.SYNC"
        private const val ACTION_CANCEL = "com.rawsmusic.transcode.action.CANCEL"
        private const val ACTION_STOP_IF_IDLE = "com.rawsmusic.transcode.action.STOP_IF_IDLE"
        private const val EXTRA_TASK_ID = "transcode_task_id"
        private const val WAKE_LOCK_TAG = "RawSMusic:AudioTranscode"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val NOTIFICATION_STEP_PERMILLE = 5

        /**
         * Preferred entry point for UI callers. The queue starts first, then the foreground owner
         * is promoted immediately; if foreground-service startup is rejected, the new task is
         * cancelled so it cannot continue silently in a background-restricted process.
         */
        fun enqueue(context: Context, request: AudioTranscodeRequest): Result<String> = runCatching {
            AudioTranscodeQueue.initialize(context.applicationContext)
            val taskId = AudioTranscodeQueue.enqueue(request)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AudioTranscodeForegroundService::class.java)
                        .setAction(ACTION_SYNC)
                        .putExtra(EXTRA_TASK_ID, taskId),
                )
            } catch (error: Throwable) {
                AudioTranscodeQueue.cancel(taskId)
                throw error
            }
            taskId
        }

        /**
         * User explicitly pressed the play-shaped "start conversion" action. Keep single-job
         * execution, but insert this task ahead of every ordinary waiting item.
         */
        fun enqueueNow(context: Context, request: AudioTranscodeRequest): Result<String> = runCatching {
            AudioTranscodeQueue.initialize(context.applicationContext)
            val taskId = AudioTranscodeQueue.enqueuePriority(request)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AudioTranscodeForegroundService::class.java)
                        .setAction(ACTION_SYNC)
                        .putExtra(EXTRA_TASK_ID, taskId),
                )
            } catch (error: Throwable) {
                AudioTranscodeQueue.cancel(taskId)
                throw error
            }
            taskId
        }

        /** Batch entry point used by the conversion UI. Starts the foreground owner only once. */
        fun enqueueAll(
            context: Context,
            requests: List<AudioTranscodeRequest>,
        ): Result<List<String>> = runCatching {
            if (requests.isEmpty()) return@runCatching emptyList()
            AudioTranscodeQueue.initialize(context.applicationContext)
            val taskIds = AudioTranscodeQueue.enqueueAll(requests)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AudioTranscodeForegroundService::class.java)
                        .setAction(ACTION_SYNC),
                )
            } catch (error: Throwable) {
                taskIds.forEach(AudioTranscodeQueue::cancel)
                throw error
            }
            taskIds
        }

        fun enqueueAllNow(
            context: Context,
            requests: List<AudioTranscodeRequest>,
        ): Result<List<String>> = runCatching {
            if (requests.isEmpty()) return@runCatching emptyList()
            AudioTranscodeQueue.initialize(context.applicationContext)
            val taskIds = AudioTranscodeQueue.enqueueAllPriority(requests)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AudioTranscodeForegroundService::class.java)
                        .setAction(ACTION_SYNC),
                )
            } catch (error: Throwable) {
                taskIds.forEach(AudioTranscodeQueue::cancel)
                throw error
            }
            taskIds
        }

        fun cancel(context: Context, taskId: String) {
            AudioTranscodeQueue.initialize(context.applicationContext)
            AudioTranscodeQueue.cancel(taskId)
            runCatching {
                context.startService(
                    Intent(context, AudioTranscodeForegroundService::class.java)
                        .setAction(ACTION_CANCEL)
                        .putExtra(EXTRA_TASK_ID, taskId)
                )
            }
        }

        fun sync(context: Context) {
            AudioTranscodeQueue.initialize(context.applicationContext)
            if (activeEntries(AudioTranscodeQueue.entries.value).isEmpty()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, AudioTranscodeForegroundService::class.java).setAction(ACTION_SYNC),
            )
        }

        private fun activeEntries(entries: List<AudioTranscodeQueueEntry>): List<AudioTranscodeQueueEntry> =
            entries.filter { entry ->
                entry.state == AudioTranscodeQueueState.QUEUED ||
                    entry.state == AudioTranscodeQueueState.RUNNING
            }
    }
}
