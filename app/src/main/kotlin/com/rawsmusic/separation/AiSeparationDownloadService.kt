package com.rawsmusic.separation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rawsmusic.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AiSeparationDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true)
                val job = activeJob
                if (job?.isActive == true) {
                    job.cancel(CancellationException("User cancelled"))
                } else {
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
        }
        val runtimeRequest = intent?.getBooleanExtra(EXTRA_RUNTIME, false) == true
        val lyricAlignmentRequest = intent?.action == ACTION_DOWNLOAD_LYRIC_ALIGNMENT
        val fastVocalAlignmentRequest = intent?.action == ACTION_DOWNLOAD_FAST_VOCAL_ALIGNMENT
        val store = AiSeparationPluginStore.get(this)
        val runtimeEntry = if (runtimeRequest) {
            store.state.value.runtimeCatalog
                .filter { it.abi in Build.SUPPORTED_ABIS }
                .maxByOrNull { it.version }
                ?: AiRecommendedRuntime.ONNX_RUNTIME_1_26
        } else {
            null
        }
        val modelId = if (runtimeRequest) {
            runtimeEntry?.id.orEmpty()
        } else if (fastVocalAlignmentRequest) {
            AiFastVocalAlignmentBundle.ID
        } else {
            intent?.getStringExtra(EXTRA_MODEL_ID).orEmpty()
        }
        val modelVersion = if (runtimeRequest) {
            runtimeEntry?.version.orEmpty()
        } else if (fastVocalAlignmentRequest) {
            AiFastVocalAlignmentBundle.VERSION
        } else {
            intent?.getStringExtra(EXTRA_MODEL_VERSION).orEmpty()
        }
        if (modelId.isBlank() || modelVersion.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (activeJob?.isActive == true) return START_NOT_STICKY

        cancelled.set(false)
        acquireWakeLock()
        val lyricEntry = if (lyricAlignmentRequest) {
            // Do not use only the StateFlow here. A service may be recreated after the UI
            // process has been trimmed while the signed catalog is still on disk.
            store.cachedLyricAlignmentEntry(modelId, modelVersion)
        } else null
        val modelEntry = AiRecommendedModels.find(modelId, modelVersion)
            ?: store.state.value.catalog.firstOrNull {
                it.id == modelId && it.version == modelVersion
            }
        val modelName = if (fastVocalAlignmentRequest) {
            getString(R.string.ai_fast_alignment_bundle_name)
        } else {
            runtimeEntry?.name ?: modelEntry?.name ?: lyricEntry?.name ?: modelId
        }
        Log.i(
            TAG,
            "AI_DOWNLOAD_START type=${when {
                lyricAlignmentRequest -> "lyric_alignment"
                fastVocalAlignmentRequest -> "fast_alignment"
                runtimeRequest -> "runtime"
                else -> "model"
            }} id=$modelId version=$modelVersion entry=${lyricEntry != null || modelEntry != null}",
        )
        val totalBytes = if (fastVocalAlignmentRequest) {
            AiFastVocalAlignmentBundle.ARCHIVE_SIZE_BYTES
        } else if (lyricAlignmentRequest) {
            lyricEntry?.modelSizeBytes?.plus(lyricEntry.vocabularySizeBytes) ?: 0L
        } else if (runtimeRequest &&
            store.state.value.runtimeCatalog.none {
                it.id == runtimeEntry?.id &&
                    it.version == runtimeEntry.version &&
                    it.abi == runtimeEntry.abi
            }
        ) {
            AiRecommendedRuntime.ARCHIVE_SIZE_BYTES
        } else {
            runtimeEntry?.librarySizeBytes ?: modelEntry?.archiveSizeBytes ?: 0L
        }
        publish(
            AiSeparationDownloadProgress(
                modelId = modelId,
                modelVersion = modelVersion,
                modelName = modelName,
                phase = AiSeparationDownloadPhase.PREPARING,
                totalBytes = totalBytes,
                message = when {
                    fastVocalAlignmentRequest -> getString(R.string.ai_download_prepare_fast_alignment)
                    runtimeRequest -> getString(R.string.ai_download_prepare_runtime)
                    lyricAlignmentRequest -> getString(R.string.ai_download_prepare_lyric_alignment)
                    else -> getString(R.string.ai_download_prepare_model)
                },
            )
        )
        startForeground(NOTIFICATION_ID, buildNotification(modelName, 0L, totalBytes, true))

        activeJob = scope.launch {
            try {
                var completedBytes = totalBytes
                val onProgress: (Long, Long) -> Unit = { downloaded, total ->
                        publish(
                            AiSeparationDownloadProgress(
                                modelId = modelId,
                                modelVersion = modelVersion,
                                modelName = modelName,
                                phase = AiSeparationDownloadPhase.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                message = when {
                                    fastVocalAlignmentRequest -> getString(R.string.ai_download_running_fast_alignment)
                                    runtimeRequest -> getString(R.string.ai_download_running_runtime)
                                    lyricAlignmentRequest -> getString(R.string.ai_download_running_lyric_alignment)
                                    else -> getString(R.string.ai_download_running_model)
                                },
                            )
                        )
                    }
                val onPhase: (AiSeparationDownloadPhase) -> Unit = { phase ->
                            val message = when (phase) {
                            AiSeparationDownloadPhase.VERIFYING -> when {
                                fastVocalAlignmentRequest -> getString(R.string.ai_download_verifying_fast_alignment)
                                runtimeRequest -> getString(R.string.ai_download_verifying_runtime)
                                lyricAlignmentRequest -> getString(R.string.ai_download_verifying_lyric_alignment)
                                else -> getString(R.string.ai_download_verifying_model)
                            }
                            AiSeparationDownloadPhase.INSTALLING -> when {
                                fastVocalAlignmentRequest -> getString(R.string.ai_download_installing_fast_alignment)
                                runtimeRequest -> getString(R.string.ai_download_installing_runtime)
                                lyricAlignmentRequest -> getString(R.string.ai_download_installing_lyric_alignment)
                                else -> getString(R.string.ai_download_installing_model)
                            }
                            else -> when {
                                fastVocalAlignmentRequest -> getString(R.string.ai_download_processing_fast_alignment)
                                runtimeRequest -> getString(R.string.ai_download_processing_runtime)
                                lyricAlignmentRequest -> getString(R.string.ai_download_processing_lyric_alignment)
                                else -> getString(R.string.ai_download_processing_model)
                            }
                        }
                        publish(
                            AiSeparationDownloadProgress(
                                modelId = modelId,
                                modelVersion = modelVersion,
                                modelName = modelName,
                                phase = phase,
                                downloadedBytes = totalBytes,
                                totalBytes = totalBytes,
                                message = message,
                            )
                        )
                    }
                if (fastVocalAlignmentRequest) {
                    completedBytes = store.downloadAndInstallFastVocalAlignmentBundle(
                        onProgress = onProgress,
                        onPhase = onPhase,
                        isCancelled = { cancelled.get() },
                    )
                } else if (runtimeRequest) {
                    val installed = store.downloadAndInstallRuntime(
                        onProgress = onProgress,
                        onPhase = onPhase,
                        isCancelled = { cancelled.get() },
                    )
                    completedBytes = installed.librarySizeBytes
                } else if (lyricAlignmentRequest) {
                    val installed = store.downloadAndInstallLyricAlignment(
                        modelId = modelId,
                        modelVersion = modelVersion,
                        onProgress = onProgress,
                        onPhase = onPhase,
                        isCancelled = { cancelled.get() },
                    )
                    completedBytes = installed.catalog.modelSizeBytes +
                        installed.catalog.vocabularySizeBytes
                } else {
                    val installed = store.downloadAndInstall(
                        modelId = modelId,
                        modelVersion = modelVersion,
                        onProgress = onProgress,
                        onPhase = onPhase,
                        isCancelled = { cancelled.get() },
                    )
                    completedBytes = installed.catalog.archiveSizeBytes
                }
                publish(
                    AiSeparationDownloadProgress(
                        modelId = modelId,
                        modelVersion = modelVersion,
                        modelName = modelName,
                        phase = AiSeparationDownloadPhase.COMPLETED,
                        downloadedBytes = completedBytes,
                        totalBytes = completedBytes,
                        message = when {
                            fastVocalAlignmentRequest -> getString(R.string.ai_download_completed_fast_alignment)
                            runtimeRequest -> getString(R.string.ai_download_completed_runtime)
                            lyricAlignmentRequest -> getString(R.string.ai_download_completed_lyric_alignment)
                            else -> getString(R.string.ai_download_completed_model)
                        },
                    )
                )
                notificationManager().notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this@AiSeparationDownloadService, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_music_note)
                        .setContentTitle(
                            when {
                                fastVocalAlignmentRequest -> getString(R.string.ai_download_notification_fast_alignment)
                                runtimeRequest -> getString(R.string.ai_download_notification_runtime)
                                lyricAlignmentRequest -> getString(R.string.ai_download_notification_lyric_alignment)
                                else -> getString(R.string.ai_download_notification_model)
                            }
                        )
                        .setContentText(modelName)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (cancel: CancellationException) {
                publish(
                    AiSeparationDownloadProgress(
                        modelId = modelId,
                        modelVersion = modelVersion,
                        modelName = modelName,
                        phase = AiSeparationDownloadPhase.CANCELLED,
                        message = when {
                            fastVocalAlignmentRequest -> getString(R.string.ai_download_cancelled_fast_alignment)
                            runtimeRequest -> getString(R.string.ai_download_cancelled_runtime)
                            lyricAlignmentRequest -> getString(R.string.ai_download_cancelled_lyric_alignment)
                            else -> getString(R.string.ai_download_cancelled_model)
                        },
                    )
                )
            } catch (error: Throwable) {
                Log.e(TAG, "AI_DOWNLOAD_FAILED id=$modelId version=$modelVersion", error)
                publish(
                    AiSeparationDownloadProgress(
                        modelId = modelId,
                        modelVersion = modelVersion,
                        modelName = modelName,
                        phase = AiSeparationDownloadPhase.FAILED,
                        message = error.message ?: getString(
                            if (runtimeRequest) R.string.ai_runtime_download_failed
                            else if (fastVocalAlignmentRequest) R.string.ai_fast_alignment_download_failed
                            else if (lyricAlignmentRequest) R.string.ai_lyric_alignment_download_failed
                            else R.string.ai_model_download_failed
                        ),
                    )
                )
                notificationManager().notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this@AiSeparationDownloadService, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_music_note)
                        .setContentTitle(
                            getString(
                                if (runtimeRequest) R.string.ai_runtime_install_failed
                                else if (fastVocalAlignmentRequest) R.string.ai_fast_alignment_install_failed
                                else if (lyricAlignmentRequest) R.string.ai_lyric_alignment_install_failed
                                else R.string.ai_model_install_failed
                            )
                        )
                        .setContentText(error.message ?: getString(R.string.ai_download_check_source))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(error.message.orEmpty()))
                        .setAutoCancel(true)
                        .build()
                )
            } finally {
                activeJob = null
                releaseWakeLock()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun publish(progress: AiSeparationDownloadProgress) {
        AiSeparationProgressBus.publish(progress)
        if (progress.active) {
            notificationManager().notify(
                NOTIFICATION_ID,
                buildNotification(
                    progress.modelName,
                    progress.downloadedBytes,
                    progress.totalBytes,
                    progress.phase != AiSeparationDownloadPhase.DOWNLOADING,
                )
            )
        }
    }

    private fun buildNotification(
        modelName: String,
        downloaded: Long,
        total: Long,
        indeterminate: Boolean,
    ): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AiSeparationDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (total > 0L && !indeterminate) {
            "${formatBytes(downloaded)} / ${formatBytes(total)}"
        } else {
            getString(R.string.ai_download_preparing)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(getString(R.string.ai_model_download_title))
            .setContentText("$modelName · $text")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(
                1000,
                if (total > 0L) ((downloaded.coerceIn(0L, total) * 1000L) / total).toInt() else 0,
                indeterminate || total <= 0L,
            )
            .addAction(0, getString(R.string.common_cancel), cancelIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ai_download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawSMusic:AiModelDownload")
            .apply { acquire(2 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "AiSeparationDownloadService"
        private const val CHANNEL_ID = "rawsmusic_ai_model_download"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_CANCEL = "com.rawsmusic.ai.action.CANCEL_MODEL_DOWNLOAD"
        private const val ACTION_DOWNLOAD_LYRIC_ALIGNMENT =
            "com.rawsmusic.ai.action.DOWNLOAD_LYRIC_ALIGNMENT"
        private const val ACTION_DOWNLOAD_FAST_VOCAL_ALIGNMENT =
            "com.rawsmusic.ai.action.DOWNLOAD_FAST_VOCAL_ALIGNMENT"
        private const val EXTRA_MODEL_ID = "ai_model_id"
        private const val EXTRA_MODEL_VERSION = "ai_model_version"
        private const val EXTRA_RUNTIME = "ai_runtime"

        fun start(context: Context, modelId: String, modelVersion: String) {
            val intent = Intent(context, AiSeparationDownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_MODEL_VERSION, modelVersion)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startRuntime(context: Context) {
            val intent = Intent(context, AiSeparationDownloadService::class.java)
                .putExtra(EXTRA_RUNTIME, true)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startLyricAlignment(context: Context, modelId: String, modelVersion: String) {
            val intent = Intent(context, AiSeparationDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD_LYRIC_ALIGNMENT)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_MODEL_VERSION, modelVersion)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startFastVocalAlignment(context: Context) {
            val intent = Intent(context, AiSeparationDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD_FAST_VOCAL_ALIGNMENT)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
