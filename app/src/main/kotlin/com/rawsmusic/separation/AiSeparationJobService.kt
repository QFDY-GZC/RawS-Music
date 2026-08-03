package com.rawsmusic.separation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rawsmusic.R
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.ui.settings.AiSeparationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class AiSeparationJobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeTaskId: String = ""
    @Volatile private var lastNotificationMs = 0L

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
            if (taskId.isBlank() || taskId == activeTaskId) {
                cancelled.set(true)
                AiSeparationJobProgressBus.requestCancel(activeTaskId)
            }
            return START_NOT_STICKY
        }
        if (activeJob?.isActive == true || AiSeparationJobProgressBus.hasActiveTask()) {
            return START_NOT_STICKY
        }

        val taskId = intent?.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val sourceUri = intent?.getStringExtra(EXTRA_SOURCE_URI).orEmpty()
        val sourceName = intent?.getStringExtra(EXTRA_SOURCE_NAME).orEmpty().ifBlank { "audio" }
        if (taskId.isBlank() || sourceUri.isBlank()) return START_NOT_STICKY

        val store = AiSeparationPluginStore.get(this)
        val selected = store.selectedInstalledModel()
        val contract = selected?.catalog?.contract
        if (selected == null || contract == null || !selected.catalog.executable) {
            publishTerminal(
                AiSeparationJobProgress(
                    taskId = taskId,
                    sourceName = sourceName,
                    phase = AiSeparationJobPhase.FAILED,
                    message = "请先选择已安装的 schema 2/3 可执行模型",
                )
            )
            return START_NOT_STICKY
        }
        val initial = AiSeparationJobProgress(
            taskId = taskId,
            modelId = selected.catalog.id,
            modelVersion = selected.catalog.version,
            modelName = selected.catalog.name,
            sourceName = sourceName,
            phase = AiSeparationJobPhase.PREPARING,
            message = "准备 AI 分离任务",
        )
        if (!AiSeparationJobProgressBus.begin(initial)) return START_NOT_STICKY

        activeTaskId = taskId
        cancelled.set(false)
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(initial, indeterminate = true))
        activeJob = scope.launch {
            runTask(startId, taskId, Uri.parse(sourceUri), sourceName, selected, contract)
        }
        return START_NOT_STICKY
    }

    private suspend fun runTask(
        startId: Int,
        taskId: String,
        sourceUri: Uri,
        sourceName: String,
        model: AiSeparationInstalledModel,
        contract: AiSeparationModelContract,
    ) {
        val jobsRoot = File(filesDir, "ai_separation/jobs").apply { mkdirs() }
        jobsRoot.listFiles().orEmpty()
            .filter { it.name != taskId && it.lastModified() < System.currentTimeMillis() - STALE_JOB_MS }
            .forEach { it.deleteRecursively() }
        val taskDir = File(jobsRoot, taskId).apply {
            deleteRecursively()
            require(mkdirs()) { "无法创建 AI 任务目录" }
        }
        val sourceFile = File(taskDir, "source.input")
        val pcmFile = File(taskDir, "decoded_s32le_stereo.pcm")
        val vocalsFile = File(taskDir, AiSeparationResult.TEMP_VOCALS_FILE)
        val instrumentalFile = File(taskDir, AiSeparationResult.TEMP_INSTRUMENTAL_FILE)
        var liveStreamStarted = false

        try {
            val taskStartedMs = android.os.SystemClock.elapsedRealtime()
            ensureNotCancelled(taskId)
            if (AiOnnxRuntimeLoader.ensureLoaded(this).isFailure) {
                update(taskId, AiSeparationJobPhase.PREPARING, "首次准备 AI 推理运行库")
                AiSeparationPluginStore.get(this).downloadAndInstallRuntime(
                    onProgress = { downloaded, total ->
                        val percent = if (total > 0L) downloaded * 100L / total else 0L
                        update(
                            taskId,
                            AiSeparationJobPhase.PREPARING,
                            "正在下载 AI 推理运行库 $percent%",
                        )
                    },
                    onPhase = { phase ->
                        val message = when (phase) {
                            AiSeparationDownloadPhase.VERIFYING -> "正在校验 AI 推理运行库"
                            AiSeparationDownloadPhase.INSTALLING -> "正在安装 AI 推理运行库"
                            else -> "正在准备 AI 推理运行库"
                        }
                        update(taskId, AiSeparationJobPhase.PREPARING, message)
                    },
                    isCancelled = {
                        cancelled.get() || AiSeparationJobProgressBus.isCancelRequested(taskId)
                    },
                )
                AiOnnxRuntimeLoader.ensureLoaded(this).getOrThrow()
            }
            val decodeStartedMs = android.os.SystemClock.elapsedRealtime()
            val sourceMode = decodeSourceToPcm(
                sourceUri = sourceUri,
                fallbackSource = sourceFile,
                pcmFile = pcmFile,
                sampleRate = model.catalog.sampleRate,
                taskId = taskId,
            )
            val decodeElapsedMs = android.os.SystemClock.elapsedRealtime() - decodeStartedMs
            Log.i(
                TAG,
                "AI_PERF decode_ms=$decodeElapsedMs source_mode=$sourceMode " +
                    "pcm_bytes=${pcmFile.length()}",
            )
            require(pcmFile.length() % 8L == 0L) { "解码 PCM 大小不是 stereo/s32le 帧边界" }
            val totalFrames = pcmFile.length() / 8L
            ensureOutputSpace(totalFrames)
            ensureNotCancelled(taskId)
            val liveStreamingEnabled = AiSeparationPreferences.isLiveStreamingEnabled(this)
            if (liveStreamingEnabled) {
                AiSeparationLiveStreamBus.begin(
                    taskId = taskId,
                    sourceName = sourceName,
                    sampleRate = model.catalog.sampleRate,
                    vocalsFile = vocalsFile,
                    instrumentalFile = instrumentalFile,
                )
                AiSeparationLiveStreamBus.publish(taskId, 0L, totalFrames)
                liveStreamStarted = true
                Log.i(
                    TAG,
                    "AI_STEM_STREAM begin task=$taskId sampleRate=${model.catalog.sampleRate} " +
                        "frames=$totalFrames denoiseForcedOff=true",
                )
            }

            update(taskId, AiSeparationJobPhase.LOADING_MODEL, "正在加载 ONNX Runtime 模型") {
                copy(totalFrames = totalFrames)
            }
            val modelFile = File(model.directory, model.catalog.modelFile)
            val modelLoadStartedMs = android.os.SystemClock.elapsedRealtime()
            AiOnnxRuntimeSession.open(this, modelFile, contract).use { runtime ->
                Log.i(
                    TAG,
                    "AI_PERF model_load_ms=" +
                        (android.os.SystemClock.elapsedRealtime() - modelLoadStartedMs),
                )
                val startedNs = System.nanoTime()
                val nativeCallback = object : AiNativeSeparationCallback {
                    override fun isCancelled(): Boolean =
                        cancelled.get() || AiSeparationJobProgressBus.isCancelRequested(taskId)

                    override fun onProgress(
                        processedFrames: Long,
                        totalFrames: Long,
                        segmentIndex: Int,
                        segmentCount: Int,
                    ) {
                        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                        val audioMs = if (processedFrames > 0L) {
                            processedFrames * 1000.0 / model.catalog.sampleRate
                        } else {
                            0.0
                        }
                        val realtimeFactor = if (audioMs > 0.0) elapsedMs / audioMs else 0.0
                        AiSeparationJobProgressBus.update(taskId) { current ->
                            current.copy(
                                phase = AiSeparationJobPhase.SEPARATING,
                                processedFrames = processedFrames,
                                totalFrames = totalFrames,
                                processedSegments = segmentIndex,
                                totalSegments = segmentCount,
                                elapsedMs = elapsedMs,
                                realtimeFactor = realtimeFactor,
                                message = "正在分离人声与伴奏",
                            )
                        }
                        if (liveStreamingEnabled) {
                            AiSeparationLiveStreamBus.publish(
                                taskId = taskId,
                                availableFrames = processedFrames,
                                totalFrames = totalFrames,
                            )
                        }
                        publishActiveNotificationThrottled()
                    }
                }
                val denoiseEnabled = !liveStreamingEnabled && contract.supportsDenoise &&
                    AiSeparationPreferences.isDenoiseEnabled(this@AiSeparationJobService)
                val stats = AiSeparationRuntimeBridge.separatePcm(
                    pcmFile = pcmFile,
                    vocalsFile = vocalsFile,
                    instrumentalFile = instrumentalFile,
                    sampleRate = model.catalog.sampleRate,
                    segmentSamples = model.catalog.segmentSamples.toInt(),
                    overlap = model.catalog.overlap,
                    contract = contract,
                    runtimeSession = runtime,
                    callback = nativeCallback,
                    denoise = denoiseEnabled,
                ).getOrThrow()
                ensureNotCancelled(taskId)

                val losslessOutput = isLosslessSource(sourceUri, sourceName)
                val outputFormat = if (losslessOutput) "flac" else "m4a"
                val encodedVocals = File(
                    taskDir,
                    "${AiSeparationResult.VOCALS_BASENAME}.$outputFormat",
                )
                val encodedInstrumental = File(
                    taskDir,
                    "${AiSeparationResult.INSTRUMENTAL_BASENAME}.$outputFormat",
                )
                update(
                    taskId,
                    AiSeparationJobPhase.COMMITTING,
                    if (losslessOutput) {
                        "正在编码 FLAC · 16-bit/44.1kHz · Level 8"
                    } else {
                        "正在编码 AAC-LC · 44.1kHz"
                    },
                ) {
                    copy(
                        processedFrames = stats.totalFrames,
                        totalFrames = stats.totalFrames,
                        processedSegments = stats.processedSegments,
                        elapsedMs = stats.elapsedMs,
                    )
                }
                AiStemAudioEncoder.encode(vocalsFile, encodedVocals, losslessOutput).getOrThrow()
                ensureNotCancelled(taskId)
                AiStemAudioEncoder.encode(
                    instrumentalFile,
                    encodedInstrumental,
                    losslessOutput,
                ).getOrThrow()
                ensureNotCancelled(taskId)
                update(taskId, AiSeparationJobPhase.COMMITTING, "正在原子提交分离结果")
                val result = AiSeparationResultStore.get(this).commit(
                    vocals = encodedVocals,
                    instrumental = encodedInstrumental,
                    sourceName = sourceName,
                    sourceUri = sourceUri,
                    outputFormat = outputFormat,
                    model = model,
                    sampleRate = OUTPUT_SAMPLE_RATE,
                    stats = stats,
                )
                if (liveStreamingEnabled) {
                    // The progressive preview reads temporary WAV files. Stop it before
                    // publishing the final FLAC/AAC files so the old reader cannot parse
                    // compressed audio as PCM.
                    AiSeparationLivePlayer.get(this).stop()
                    AiSeparationLiveStreamBus.complete(taskId, result)
                }
                Log.i(
                    TAG,
                    "AI_PERF total_ms=${android.os.SystemClock.elapsedRealtime() - taskStartedMs} " +
                        "separation_ms=${stats.elapsedMs} frames=${stats.totalFrames} " +
                        "segments=${stats.processedSegments}",
                )
                publishTerminal(
                    AiSeparationJobProgressBus.state.value.copy(
                        phase = AiSeparationJobPhase.COMPLETED,
                        message = "AI 人声分离完成",
                        resultId = result.id,
                        cancelRequested = false,
                    )
                )
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildTerminalNotification("AI 人声分离完成", "$sourceName · 已生成人声与伴奏")
                )
            }
        } catch (error: Throwable) {
            val cancelledTask = error.message == CANCELLED_MESSAGE || cancelled.get() ||
                AiSeparationJobProgressBus.isCancelRequested(taskId)
            if (liveStreamStarted) {
                AiSeparationLiveStreamBus.fail(
                    taskId,
                    if (cancelledTask) "AI 分离已取消" else (error.message ?: "AI 分离失败"),
                )
            }
            publishTerminal(
                AiSeparationJobProgressBus.state.value.copy(
                    phase = if (cancelledTask) AiSeparationJobPhase.CANCELLED else AiSeparationJobPhase.FAILED,
                    message = if (cancelledTask) "AI 人声分离已取消" else (error.message ?: "AI 人声分离失败"),
                    cancelRequested = cancelledTask,
                )
            )
            if (!cancelledTask) {
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildTerminalNotification("AI 人声分离失败", error.message ?: "请检查模型契约")
                )
            }
        } finally {
            taskDir.deleteRecursively()
            activeJob = null
            activeTaskId = ""
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            stopSelfResult(startId)
        }
    }

    private fun decodeSourceToPcm(
        sourceUri: Uri,
        fallbackSource: File,
        pcmFile: File,
        sampleRate: Int,
        taskId: String,
    ): String {
        val directDescriptor = runCatching {
            contentResolver.openFileDescriptor(sourceUri, "r")
        }.getOrNull()
        if (directDescriptor != null) {
            directDescriptor.use { descriptor ->
                val directPath = "/proc/self/fd/${descriptor.fd}"
                val durationMs = FFmpegBridge.probeDuration(directPath).coerceAtLeast(0L)
                ensureDecodeSpace(durationMs, sampleRate)
                update(taskId, AiSeparationJobPhase.DECODING, "正在直接解码和重采样音频")
                val directResult = FFmpegBridge.convertToRawPcm(
                    inputPath = directPath,
                    outputPath = pcmFile.absolutePath,
                    targetSampleRate = sampleRate,
                    bitsPerSample = 32,
                    channels = 2,
                )
                if (directResult == 0 && pcmFile.isFile && pcmFile.length() > 0L) {
                    return "descriptor"
                }
                Log.w(TAG, "Direct descriptor decode failed result=$directResult; falling back to copy")
                pcmFile.delete()
            }
        }

        ensureInputCopySpace(sourceUri)
        copySource(sourceUri, fallbackSource)
        require(fallbackSource.length() > 0L) { "输入音频为空" }
        val durationMs = FFmpegBridge.probeDuration(fallbackSource.absolutePath).coerceAtLeast(0L)
        ensureDecodeSpace(durationMs, sampleRate)
        update(taskId, AiSeparationJobPhase.DECODING, "正在解码和重采样音频")
        val result = FFmpegBridge.convertToRawPcm(
            inputPath = fallbackSource.absolutePath,
            outputPath = pcmFile.absolutePath,
            targetSampleRate = sampleRate,
            bitsPerSample = 32,
            channels = 2,
        )
        require(result == 0 && pcmFile.isFile && pcmFile.length() > 0L) {
            "FFmpeg PCM 解码失败：$result"
        }
        return "copied"
    }

    private fun copySource(uri: Uri, target: File) {
        contentResolver.openInputStream(uri)?.use { source ->
            BufferedInputStream(source).use { input ->
                BufferedOutputStream(FileOutputStream(target)).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
            }
        } ?: error("无法读取所选音频")
    }

    private fun ensureInputCopySpace(uri: Uri) {
        val declared = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0L } ?: MIN_UNKNOWN_INPUT_RESERVE
        ensureFreeSpace(declared + WORK_MARGIN_BYTES)
    }

    private fun ensureDecodeSpace(durationMs: Long, sampleRate: Int) {
        val estimatedFrames = if (durationMs > 0L) {
            Math.multiplyExact(durationMs, sampleRate.toLong()) / 1000L
        } else {
            MIN_UNKNOWN_DURATION_FRAMES
        }
        val pcm = Math.multiplyExact(estimatedFrames, 8L)
        val outputs = Math.multiplyExact(estimatedFrames, 16L)
        ensureFreeSpace(pcm + outputs + WORK_MARGIN_BYTES)
    }

    private fun ensureOutputSpace(totalFrames: Long) {
        val singleWav = Math.addExact(Math.multiplyExact(totalFrames, 8L), 44L)
        require(singleWav <= UINT32_MAX) { "音轨超过 32-bit RIFF WAV 的 4 GiB 上限" }
        ensureFreeSpace(Math.multiplyExact(singleWav, 2L) + WORK_MARGIN_BYTES)
    }

    private fun isLosslessSource(sourceUri: Uri, sourceName: String): Boolean {
        val extension = sourceName.substringAfterLast('.', "").lowercase()
        if (extension in LOSSLESS_EXTENSIONS) return true
        return contentResolver.getType(sourceUri)?.lowercase() in LOSSLESS_MIME_TYPES
    }

    private fun ensureFreeSpace(requiredBytes: Long) {
        val available = StatFs(filesDir.absolutePath).availableBytes
        require(available >= requiredBytes) {
            "可用空间不足，需要约 ${formatBytes(requiredBytes)}，当前 ${formatBytes(available)}"
        }
    }

    private fun ensureNotCancelled(taskId: String) {
        if (cancelled.get() || AiSeparationJobProgressBus.isCancelRequested(taskId)) {
            throw IllegalStateException(CANCELLED_MESSAGE)
        }
    }

    private fun update(
        taskId: String,
        phase: AiSeparationJobPhase,
        message: String,
        transform: AiSeparationJobProgress.() -> AiSeparationJobProgress = { this },
    ) {
        AiSeparationJobProgressBus.update(taskId) { current ->
            current.transform().copy(phase = phase, message = message)
        }
        publishActiveNotificationThrottled(force = true)
    }

    private fun publishTerminal(progress: AiSeparationJobProgress) {
        AiSeparationJobProgressBus.update(progress.taskId) { progress }
    }

    private fun publishActiveNotificationThrottled(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationMs < NOTIFICATION_INTERVAL_MS) return
        lastNotificationMs = now
        val progress = AiSeparationJobProgressBus.state.value
        if (progress.active) {
            notificationManager().notify(
                NOTIFICATION_ID,
                buildNotification(progress, indeterminate = progress.totalFrames <= 0L)
            )
        }
    }

    private fun buildNotification(
        progress: AiSeparationJobProgress,
        indeterminate: Boolean,
    ): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AiSeparationJobService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TASK_ID, progress.taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = (progress.fraction * 1000f).toInt().coerceIn(0, 1000)
        val text = when (progress.phase) {
            AiSeparationJobPhase.SEPARATING -> {
                val rtf = if (progress.realtimeFactor > 0.0) " · %.2f×".format(progress.realtimeFactor) else ""
                "${progress.processedSegments}/${max(1, progress.totalSegments)} 段$rtf"
            }
            else -> progress.message
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("AI 人声分离 · ${progress.sourceName}")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, percent, indeterminate)
            .addAction(0, "取消", cancelIntent)
            .setContentIntent(settingsPendingIntent())
            .build()
    }

    private fun buildTerminalNotification(title: String, text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(settingsPendingIntent())
            .build()

    private fun settingsPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        74,
        Intent(this, AiSeparationActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 人声分离", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawSMusic:AiSeparation")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelled.set(true)
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled.set(true)
        AiSeparationJobProgressBus.requestCancel(activeTaskId)
        stopSelf(startId)
    }

    companion object {
        private const val TAG = "AiSeparationJob"
        private const val CHANNEL_ID = "rawsmusic_ai_separation_job"
        private const val NOTIFICATION_ID = 7401
        private const val ACTION_CANCEL = "com.rawsmusic.ai.action.CANCEL_SEPARATION"
        private const val EXTRA_TASK_ID = "ai_task_id"
        private const val EXTRA_SOURCE_URI = "ai_source_uri"
        private const val EXTRA_SOURCE_NAME = "ai_source_name"
        private const val CANCELLED_MESSAGE = "__AI_SEPARATION_CANCELLED__"
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val NOTIFICATION_INTERVAL_MS = 250L
        private const val STALE_JOB_MS = 24L * 60L * 60L * 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val WORK_MARGIN_BYTES = 128L * 1024L * 1024L
        private const val MIN_UNKNOWN_INPUT_RESERVE = 64L * 1024L * 1024L
        private const val MIN_UNKNOWN_DURATION_FRAMES = 10L * 60L * 48_000L
        private const val UINT32_MAX = 0xffff_ffffL
        private const val OUTPUT_SAMPLE_RATE = 44_100
        private val LOSSLESS_EXTENSIONS = setOf(
            "flac",
            "wav",
            "wave",
            "aif",
            "aiff",
            "alac",
            "ape",
            "wv",
            "dsf",
            "dff",
        )
        private val LOSSLESS_MIME_TYPES = setOf(
            "audio/flac",
            "audio/x-flac",
            "audio/wav",
            "audio/x-wav",
            "audio/aiff",
            "audio/x-aiff",
        )

        fun start(context: Context, sourceUri: Uri, sourceName: String): Result<String> = runCatching {
            require(!AiSeparationJobProgressBus.hasActiveTask()) { "已有 AI 分离任务正在运行" }
            val taskId = UUID.randomUUID().toString()
            val intent = Intent(context, AiSeparationJobService::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_SOURCE_URI, sourceUri.toString())
                .putExtra(EXTRA_SOURCE_NAME, sourceName)
            ContextCompat.startForegroundService(context, intent)
            taskId
        }

        fun cancel(context: Context, taskId: String) {
            AiSeparationJobProgressBus.requestCancel(taskId)
            context.startService(
                Intent(context, AiSeparationJobService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_TASK_ID, taskId)
            )
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.1f KB".format(bytes / 1024.0)
        }
    }
}
