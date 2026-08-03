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
        } else {
            intent?.getStringExtra(EXTRA_MODEL_ID).orEmpty()
        }
        val modelVersion = if (runtimeRequest) {
            runtimeEntry?.version.orEmpty()
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
        val modelEntry = AiRecommendedModels.find(modelId, modelVersion)
            ?: store.state.value.catalog.firstOrNull {
                it.id == modelId && it.version == modelVersion
            }
        val modelName = runtimeEntry?.name ?: modelEntry?.name ?: modelId
        val totalBytes = if (runtimeRequest &&
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
                message = if (runtimeRequest) "准备下载运行库" else "准备下载模型",
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
                                message = if (runtimeRequest) "正在下载运行库" else "正在下载模型",
                            )
                        )
                    }
                val onPhase: (AiSeparationDownloadPhase) -> Unit = { phase ->
                        val message = when (phase) {
                            AiSeparationDownloadPhase.VERIFYING ->
                                if (runtimeRequest) "正在校验运行库" else "正在校验模型包"
                            AiSeparationDownloadPhase.INSTALLING ->
                                if (runtimeRequest) "正在安装运行库" else "正在安装模型"
                            else -> if (runtimeRequest) "正在处理运行库" else "正在处理模型"
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
                if (runtimeRequest) {
                    val installed = store.downloadAndInstallRuntime(
                        onProgress = onProgress,
                        onPhase = onPhase,
                        isCancelled = { cancelled.get() },
                    )
                    completedBytes = installed.librarySizeBytes
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
                        message = if (runtimeRequest) "运行库安装完成" else "模型安装完成",
                    )
                )
                notificationManager().notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this@AiSeparationDownloadService, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_music_note)
                        .setContentTitle(
                            if (runtimeRequest) "AI 推理运行库已安装" else "AI 人声分离模型已安装"
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
                        message = if (runtimeRequest) "运行库下载已取消" else "模型下载已取消",
                    )
                )
            } catch (error: Throwable) {
                publish(
                    AiSeparationDownloadProgress(
                        modelId = modelId,
                        modelVersion = modelVersion,
                        modelName = modelName,
                        phase = AiSeparationDownloadPhase.FAILED,
                        message = error.message ?: if (runtimeRequest) "运行库下载失败" else "模型下载失败",
                    )
                )
                notificationManager().notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this@AiSeparationDownloadService, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_music_note)
                        .setContentTitle(
                            if (runtimeRequest) "AI 运行库安装失败" else "AI 模型安装失败"
                        )
                        .setContentText(error.message ?: "请检查可信仓库和网络")
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
            "正在准备模型"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("下载 AI 人声分离模型")
            .setContentText("$modelName · $text")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(
                1000,
                if (total > 0L) ((downloaded.coerceIn(0L, total) * 1000L) / total).toInt() else 0,
                indeterminate || total <= 0L,
            )
            .addAction(0, "取消", cancelIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AI 模型下载",
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
        private const val CHANNEL_ID = "rawsmusic_ai_model_download"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_CANCEL = "com.rawsmusic.ai.action.CANCEL_MODEL_DOWNLOAD"
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

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
