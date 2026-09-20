package com.rawsmusic.separation

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricActivityTrimPolicy
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricTimingCorrection
import com.rawsmusic.core.common.model.VoiceActivityMap
import com.rawsmusic.core.common.model.VoiceActivitySpan
import com.rawsmusic.core.common.model.previewActivityTrim
import com.rawsmusic.core.common.model.stableTimingFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fast, activity-only lyric alignment pass.
 *
 * It runs a small two-stem model over temporary raw PCM chunks and immediately reduces each
 * vocal chunk to RMS activity. No vocal or instrumental WAV/FLAC is produced or retained. The
 * larger RoFormer path remains owned by [AiSeparationJobService] for high-quality offline export.
 */
class AiFastVocalAlignmentService private constructor(
    private val appContext: Context,
    private val modelStore: AiSeparationPluginStore,
    private val cacheStore: AiFastVocalAlignmentCacheStore,
) {
    suspend fun previewLineTiming(
        song: AudioFile,
        lyrics: LyricData,
        policy: LyricActivityTrimPolicy = LyricActivityTrimPolicy(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<LyricTimingCorrection> = withContext(Dispatchers.IO) {
        runCatching {
            require(song.path.isNotBlank()) { "当前歌曲路径为空" }
            require(lyrics.isEmpty.not()) { "当前没有可对齐的歌词" }
            val audioFingerprint = song.stableAudioFingerprint()
            val lyricFingerprint = lyrics.stableTimingFingerprint()
            val directBundle = modelStore.fastVocalAlignmentBundleDirectory()
            val installed = modelStore.selectedFastVocalAlignmentModel()
            require(directBundle != null || installed != null) {
                "未安装可用的 Fast Vocal Alignment 模型"
            }
            val modelVersion = modelVersion(installed, directBundle)
            val cachedActivity = cacheStore.load(audioFingerprint, lyricFingerprint, modelVersion)
            val useCachedActivity = cachedActivity?.covers(song.duration) == true
            val activity = cachedActivity?.takeIf { useCachedActivity }
                ?: analyzeWithFallback(
                    song = song,
                    directBundle = directBundle,
                    installed = installed,
                    audioFingerprint = audioFingerprint,
                    analyzerVersion = modelVersion,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                ).also { generated ->
                    cacheStore.save(audioFingerprint, lyricFingerprint, modelVersion, generated)
                }
            if (useCachedActivity) onProgress(1.0f)
            require(activity.covers(song.duration)) {
                "整曲音频分析覆盖范围不足：${activity.durationMs}ms/${song.duration}ms"
            }
            val correction = lyrics.previewActivityTrim(
                activityMap = activity,
                sourceIdentity = audioFingerprint,
                originalLyricHash = lyricFingerprint,
                correctionId = "fast-line:$audioFingerprint:$lyricFingerprint:$modelVersion",
                durationMs = activity.durationMs,
                policy = policy,
            )
            Log.i(
                TAG,
                "FAST_VOCAL_ALIGNMENT complete=true cached=$useCachedActivity " +
                    "audioDurationMs=${activity.durationMs} lyricLines=${lyrics.lines.size} " +
                    "activitySpans=${activity.spans.size} changedLines=${correction.changes.size}",
            )
            correction
        }
    }

    suspend fun inspectActivity(
        song: AudioFile,
        lyrics: LyricData,
    ): Result<VoiceActivityMap> = withContext(Dispatchers.IO) {
        runCatching {
            val audioFingerprint = song.stableAudioFingerprint()
            val lyricFingerprint = lyrics.stableTimingFingerprint()
            val directBundle = modelStore.fastVocalAlignmentBundleDirectory()
            val installed = modelStore.selectedFastVocalAlignmentModel()
            require(directBundle != null || installed != null) {
                "未安装可用的 Fast Vocal Alignment 模型"
            }
            val modelVersion = modelVersion(installed, directBundle)
            cacheStore.load(audioFingerprint, lyricFingerprint, modelVersion)
                ?: analyzeWithFallback(
                    song = song,
                    directBundle = directBundle,
                    installed = installed,
                    audioFingerprint = audioFingerprint,
                    analyzerVersion = modelVersion,
                    onProgress = {},
                    isCancelled = { false },
                ).also { generated ->
                    cacheStore.save(audioFingerprint, lyricFingerprint, modelVersion, generated)
                }
        }
    }

    private fun analyzeWithFallback(
        song: AudioFile,
        directBundle: File?,
        installed: AiSeparationInstalledModel?,
        audioFingerprint: String,
        analyzerVersion: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): VoiceActivityMap {
        if (directBundle != null) {
            runCatching {
                analyzeSpleeter(
                    song,
                    directBundle,
                    audioFingerprint,
                    analyzerVersion,
                    onProgress,
                    isCancelled,
                )
            }.onFailure { failure ->
                Log.w(TAG, "FAST_VOCAL_ALIGNMENT spleeter_failed_fallback", failure)
            }.getOrNull()?.let { return it }
        }
        if (installed != null) {
            return analyze(song, installed, audioFingerprint, onProgress, isCancelled)
        }
        error("Fast Vocal Alignment 推理失败，且没有兼容模型可回退")
    }

    private fun analyzeSpleeter(
        song: AudioFile,
        bundleDirectory: File,
        audioFingerprint: String,
        analyzerVersion: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): VoiceActivityMap {
        val model = File(bundleDirectory, AiFastVocalAlignmentBundle.VOCALS_FILE)
        require(model.isFile) { "Spleeter 人声模型不存在" }
        val rawPcm = File(
            appContext.cacheDir,
            "fast_spleeter_activity_${System.nanoTime()}.s32le",
        )
        val sampleRate = 44_100
        try {
            onProgress(0.02f)
            val conversion = FFmpegBridge.convertToRawPcm(
                inputPath = song.path,
                outputPath = rawPcm.absolutePath,
                targetSampleRate = sampleRate,
                bitsPerSample = 32,
                channels = 2,
            )
            require(conversion == 0 && rawPcm.isFile) {
                "无法为 Spleeter 歌词分析解码源音频：$conversion"
            }
            onProgress(0.12f)
            AiOnnxRuntimeSession.open(
                context = appContext,
                modelFile = model,
                contract = AiFastVocalAlignmentModel.spleeterActivityContract(),
            ).use { runtime ->
                val raw = AiSeparationRuntimeBridge.analyzeSpleeterVocalActivity(
                    pcmFile = rawPcm,
                    sampleRate = sampleRate,
                    runtimeSession = runtime,
                    callback = object : AiNativeSeparationCallback {
                        override fun isCancelled(): Boolean = isCancelled()

                        override fun onProgress(
                            processedFrames: Long,
                            totalFrames: Long,
                            segmentIndex: Int,
                            segmentCount: Int,
                        ) {
                            val fraction = if (segmentCount <= 0) 0f else {
                                segmentIndex.toFloat() / segmentCount.toFloat()
                            }
                            onProgress(0.12f + fraction.coerceIn(0f, 1f) * 0.86f)
                        }
                    },
                ).getOrThrow()
                onProgress(1.0f)
                Log.i(TAG, "FAST_VOCAL_ALIGNMENT backend=spleeter activity_only")
                return AiSpleeterActivityParser.parse(raw, audioFingerprint, analyzerVersion)
            }
        } finally {
            rawPcm.delete()
        }
    }

    private fun analyze(
        song: AudioFile,
        installed: AiSeparationInstalledModel,
        audioFingerprint: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): VoiceActivityMap {
        val catalog = installed.catalog
        val contract = catalog.contract ?: error("快速模型缺少运行契约")
        val segmentSamples = catalog.segmentSamples.toIntOrNullOrFail("模型分块长度")
        val sampleRate = catalog.sampleRate
        val rawPcm = File(
            appContext.cacheDir,
            "fast_vocal_alignment_${System.nanoTime()}.s32le",
        )
        try {
            onProgress(0.02f)
            val conversion = FFmpegBridge.convertToRawPcm(
                inputPath = song.path,
                outputPath = rawPcm.absolutePath,
                targetSampleRate = sampleRate,
                bitsPerSample = 32,
                channels = 2,
            )
            require(conversion == 0 && rawPcm.isFile) {
                "无法为快速歌词分析解码源音频：$conversion"
            }
            onProgress(0.12f)
            val totalFrames = rawPcm.length() / BYTES_PER_STEREO_FRAME
            require(totalFrames > 0L) { "源音频没有可分析的 PCM" }
            val options = fastActivityOptions(sampleRate)
            val features = FeatureAccumulator(
                frameCount = ceil(totalFrames.toDouble() / options.hopSamples).toInt(),
                hopSamples = options.hopSamples,
                windowSamples = options.windowSamples,
            )
            val stride = max(1, (segmentSamples * (1.0 - catalog.overlap)).toInt())
            val segmentCount = max(
                1,
                ceil(max(0L, totalFrames - segmentSamples).toDouble() / stride).toInt() + 1,
            )
            val mixture = ByteBuffer
                .allocateDirect(segmentSamples * 2 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            val vocal = ByteBuffer
                .allocateDirect(segmentSamples * 2 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            AiOnnxRuntimeSession.open(
                context = appContext,
                modelFile = File(installed.directory, catalog.modelFile),
                contract = contract,
            ).use { runtime ->
                RandomAccessFile(rawPcm, "r").use { input ->
                    for (segmentIndex in 0 until segmentCount) {
                        check(!isCancelled()) { "Fast vocal alignment cancelled" }
                        val startFrame = segmentIndex.toLong() * stride
                        readPcmSegment(input, startFrame, totalFrames, segmentSamples, mixture)
                        val result = AiSeparationRuntimeBridge.separateSegment(
                            mixtureBuffer = mixture,
                            vocalBuffer = vocal,
                            sampleRate = sampleRate,
                            segmentSamples = segmentSamples,
                            contract = contract,
                            runtimeSession = runtime,
                        )
                        result.getOrThrow()
                        features.add(
                            startFrame = startFrame,
                            totalFrames = totalFrames,
                            vocalBuffer = vocal,
                        )
                        Log.d(
                            TAG,
                            "FAST_VOCAL_ALIGNMENT segment=${segmentIndex + 1}/$segmentCount " +
                                "model=${catalog.id}:${catalog.version}",
                        )
                        onProgress(
                            0.12f + ((segmentIndex + 1).toFloat() / segmentCount.toFloat()) * 0.86f,
                        )
                    }
                }
            }
            return features.toVoiceActivityMap(
                sourceIdentity = audioFingerprint,
                analyzerVersion = modelVersion(installed, null),
                sampleRate = sampleRate,
                durationMs = totalFrames * 1000L / sampleRate,
                options = options,
            ).also { onProgress(1.0f) }
        } finally {
            rawPcm.delete()
        }
    }

    private fun readPcmSegment(
        input: RandomAccessFile,
        startFrame: Long,
        totalFrames: Long,
        segmentSamples: Int,
        destination: ByteBuffer,
    ) {
        destination.clear()
        val available = min(segmentSamples.toLong(), max(0L, totalFrames - startFrame)).toInt()
        val encoded = ByteArray(available * BYTES_PER_STEREO_FRAME)
        input.seek(startFrame.coerceAtLeast(0L) * BYTES_PER_STEREO_FRAME)
        input.readFully(encoded)
        val source = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        repeat(available * 2) {
            destination.putFloat(source.int.toFloat() / 2_147_483_648.0f)
        }
        repeat((segmentSamples - available) * 2) { destination.putFloat(0.0f) }
        destination.rewind()
    }

    private fun modelVersion(
        installed: AiSeparationInstalledModel?,
        directBundle: File?,
    ): String = if (directBundle != null) {
        "${AiFastVocalAlignmentModel.ANALYZER_VERSION}:${AiFastVocalAlignmentBundle.ID}:" +
            AiFastVocalAlignmentBundle.VERSION
    } else {
        requireNotNull(installed)
        "${AiFastVocalAlignmentModel.ANALYZER_VERSION}:${installed.catalog.id}:${installed.catalog.version}"
    }

    companion object {
        private const val TAG = "AiFastVocalAlignment"
        private const val BYTES_PER_STEREO_FRAME = 8

        @Volatile private var instance: AiFastVocalAlignmentService? = null

        fun get(context: Context): AiFastVocalAlignmentService =
            instance ?: synchronized(this) {
                instance ?: AiFastVocalAlignmentService(
                    appContext = context.applicationContext,
                    modelStore = AiSeparationPluginStore.get(context),
                    cacheStore = AiFastVocalAlignmentCacheStore.get(context),
                ).also { instance = it }
            }
    }
}

private fun VoiceActivityMap.covers(expectedDurationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    if (expectedDurationMs <= 0L) return true
    val toleranceMs = max(1_500L, expectedDurationMs / 100L)
    return durationMs + toleranceMs >= expectedDurationMs
}

private data class FastActivityOptions(
    val windowMs: Int,
    val hopMs: Int,
    val startHangMs: Int,
    val stopHangMs: Int,
    val mergeGapMs: Int,
    val preRollMs: Int,
    val postRollMs: Int,
    val windowSamples: Int,
    val hopSamples: Int,
)

private fun fastActivityOptions(sampleRate: Int): FastActivityOptions =
    FastActivityOptions(
        windowMs = 40,
        hopMs = 10,
        startHangMs = 80,
        stopHangMs = 220,
        mergeGapMs = 140,
        preRollMs = 80,
        postRollMs = 180,
        windowSamples = max(1, sampleRate * 40 / 1000),
        hopSamples = max(1, sampleRate * 10 / 1000),
    )

private class FeatureAccumulator(
    private val frameCount: Int,
    private val hopSamples: Int,
    private val windowSamples: Int,
) {
    private val energy = DoubleArray(frameCount)
    private val counts = IntArray(frameCount)

    fun add(startFrame: Long, totalFrames: Long, vocalBuffer: ByteBuffer) {
        val floats = vocalBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val available = min(
            (vocalBuffer.capacity() / (Float.SIZE_BYTES * 2)).toLong(),
            (totalFrames - startFrame).coerceAtLeast(0L),
        ).toInt()
        for (localStart in 0 until available step hopSamples) {
            val globalStart = startFrame + localStart
            val frameIndex = (globalStart / hopSamples).toInt()
            if (frameIndex !in 0 until frameCount) continue
            val count = min(windowSamples, available - localStart)
            if (count <= 0) continue
            var sum = 0.0
            for (sample in 0 until count) {
                val offset = (localStart + sample) * 2
                val mono = (floats.get(offset) + floats.get(offset + 1)) * 0.5f
                sum += mono.toDouble() * mono.toDouble()
            }
            energy[frameIndex] += max(MIN_RMS.toDouble(), sqrt(sum / count.toDouble()))
            counts[frameIndex]++
        }
    }

    fun toVoiceActivityMap(
        sourceIdentity: String,
        analyzerVersion: String,
        sampleRate: Int,
        durationMs: Long,
        options: FastActivityOptions,
    ): VoiceActivityMap {
        val db = FloatArray(frameCount) { index ->
            val rms = if (counts[index] == 0) MIN_RMS.toDouble() else energy[index] / counts[index]
            (20.0 * kotlin.math.log10(rms.coerceAtLeast(MIN_RMS.toDouble()))).toFloat()
        }
        val sorted = db.copyOf().sorted()
        val noiseFloor = sorted[(0.20f * (sorted.lastIndex.coerceAtLeast(0))).toInt()]
        val startThreshold = max(noiseFloor + 9.0f, -54.0f)
        val stopThreshold = max(noiseFloor + 5.0f, -60.0f)
        val startHangFrames = max(1, (options.startHangMs + options.hopMs - 1) / options.hopMs)
        val stopHangFrames = max(1, (options.stopHangMs + options.hopMs - 1) / options.hopMs)
        val spans = ArrayList<VoiceActivitySpan>()
        var candidateStart = -1
        var activeStart = -1
        var quietFrames = 0
        fun append(start: Int, endExclusive: Int) {
            if (endExclusive <= start) return
            val average = db.copyOfRange(start, endExclusive).average().toFloat()
            val confidence = ((average - noiseFloor) / 24.0f).coerceIn(0f, 1f)
            val startMs = max(0L, start.toLong() * options.hopMs - options.preRollMs)
            val endMs = min(
                durationMs,
                (endExclusive - 1).toLong() * options.hopMs + options.windowMs + options.postRollMs,
            )
            if (endMs <= startMs) return
            val previous = spans.lastOrNull()
            if (previous != null && startMs <= previous.endMs + options.mergeGapMs) {
                spans[spans.lastIndex] = VoiceActivitySpan(
                    startMs = previous.startMs,
                    endMs = max(previous.endMs, endMs),
                    confidence = ((previous.confidence + confidence) * 0.5f).coerceIn(0f, 1f),
                )
            } else {
                spans += VoiceActivitySpan(startMs, endMs, confidence)
            }
        }
        db.forEachIndexed { index, value ->
            if (activeStart < 0) {
                if (value >= startThreshold) {
                    if (candidateStart < 0) candidateStart = index
                    if (index - candidateStart + 1 >= startHangFrames) {
                        activeStart = candidateStart
                        candidateStart = -1
                    }
                } else {
                    candidateStart = -1
                }
            } else if (value < stopThreshold) {
                quietFrames++
                if (quietFrames >= stopHangFrames) {
                    append(activeStart, max(activeStart + 1, index - stopHangFrames + 1))
                    activeStart = -1
                    quietFrames = 0
                }
            } else {
                quietFrames = 0
            }
        }
        if (activeStart >= 0) append(activeStart, db.size)
        return VoiceActivityMap(
            sourceIdentity = sourceIdentity,
            analyzerVersion = analyzerVersion,
            sampleRate = sampleRate,
            hopMs = options.hopMs.toLong(),
            durationMs = durationMs,
            spans = spans,
            generatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val MIN_RMS = 1.0e-7f
    }
}

private fun Long.toIntOrNullOrFail(label: String): Int =
    require(this in 1..Int.MAX_VALUE.toLong()) { "$label 超出支持范围" }.let { toInt() }
