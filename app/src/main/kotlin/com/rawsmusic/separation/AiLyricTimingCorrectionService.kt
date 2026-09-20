package com.rawsmusic.separation

import android.util.Log
import com.rawsmusic.core.common.model.LyricActivityTrimPolicy
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricTimingCorrection
import com.rawsmusic.core.common.model.LyricTimingCorrectionStatus
import com.rawsmusic.core.common.model.previewActivityTrim
import com.rawsmusic.core.common.model.stableTimingFingerprint
import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Joins a cached vocal activity map with lyrics without mutating or writing lyric files.
 * Callers can present the returned correction and explicitly accept, reject, or roll it back.
 */
class AiLyricTimingCorrectionService private constructor(
    private val appContext: android.content.Context,
    private val resultStore: AiSeparationResultStore,
    private val decisionStore: AiLyricTimingDecisionStore,
    private val writebackService: AiLyricTimingWritebackService,
    private val fastVocalAlignmentService: AiFastVocalAlignmentService,
) {
    suspend fun preview(
        result: AiSeparationResult,
        lyrics: LyricData,
        originalLyricHash: String,
        policy: LyricActivityTrimPolicy = LyricActivityTrimPolicy(),
    ): Result<LyricTimingCorrection> = withContext(Dispatchers.Default) {
        runCatching {
            require(originalLyricHash.isNotBlank()) { "原始歌词指纹不能为空" }
            val activityMap = resultStore.loadVoiceActivity(result)
                ?: error("当前分离结果还没有可用的人声活动图")
            val preview = lyrics.previewActivityTrim(
                activityMap = activityMap,
                sourceIdentity = result.id,
                originalLyricHash = originalLyricHash,
                correctionId = correctionId(result, originalLyricHash, activityMap.analyzerVersion),
                durationMs = result.durationMs(),
                policy = policy,
            )
            decisionStore.load(preview)?.let { status ->
                if (status == LyricTimingCorrectionStatus.FALLBACK) preview
                else preview.copy(status = status)
            } ?: preview
        }
    }

    /**
     * Resolves the cached separation result for the exact song currently owned by the player.
     * This is the entry point for the player and lyric screens; callers do not need to recreate
     * result matching or invent a lyric hash.
     */
    suspend fun preview(
        song: AudioFile,
        lyrics: LyricData,
        policy: LyricActivityTrimPolicy = LyricActivityTrimPolicy(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<LyricTimingCorrection> = withContext(Dispatchers.IO) {
        val fast = fastVocalAlignmentService.previewLineTiming(
            song,
            lyrics,
            policy,
            onProgress,
            isCancelled,
        )
        if (fast.isSuccess) {
            Log.i(TAG, "AI_LYRIC_TIMING_FAST path=activity_curve result=success")
            return@withContext fast
        }
        Log.i(
            TAG,
            "AI_LYRIC_TIMING_FAST path=activity_curve result=fallback " +
                "reason=${fast.exceptionOrNull()?.message.orEmpty()}",
        )
        val candidates = resultStore.findCandidatesFor(song)
        val result = resultStore.findFor(song)
        Log.i(
            TAG,
            "AI_LYRIC_TIMING_MATCH path=${song.path.substringAfterLast('/')} " +
                "songId=${song.id} exact=${result?.id ?: "none"} candidates=${candidates.size}",
        )
        result ?: return@withContext Result.failure(
            IllegalStateException("当前歌曲没有可用的人声分离结果")
        )
        preview(
            result = result,
            lyrics = lyrics,
            originalLyricHash = lyrics.stableTimingFingerprint(),
            policy = policy,
        )
    }

    /**
     * Explicit fast line-timing entry point. It never falls back to an offline stem result and
     * never writes an audio file; callers can use the failure to keep the original timeline.
     */
    suspend fun previewFastLineTiming(
        song: AudioFile,
        lyrics: LyricData,
        policy: LyricActivityTrimPolicy = LyricActivityTrimPolicy(),
    ): Result<LyricTimingCorrection> =
        fastVocalAlignmentService.previewLineTiming(song, lyrics, policy)

    /**
     * Word-level preview requires a text-aware CTC or HSMM provider. It is intentionally separate
     * from the conservative line-level activity pass and never allocates character timing from
     * average vocal energy.
     */
    suspend fun previewWordTiming(
        song: AudioFile,
        lyrics: LyricData,
        policy: com.rawsmusic.core.common.model.LyricWordActivityPolicy =
            com.rawsmusic.core.common.model.LyricWordActivityPolicy(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<LyricTimingCorrection> = withContext(Dispatchers.IO) {
        val source = resultStore.findFor(song)?.vocalsFile
            ?.takeIf { it.isFile }
            ?: java.io.File(song.path)
        previewWordTimingFromSource(
            source = source,
            sourceIdentity = "song:${song.id}:${song.path.hashCode()}",
            sourceName = song.displayName,
            durationMs = song.duration,
            lyrics = lyrics,
            originalLyricHash = lyrics.stableTimingFingerprint(),
            policy = policy,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    suspend fun previewWordTiming(
        result: AiSeparationResult,
        lyrics: LyricData,
        originalLyricHash: String,
        policy: com.rawsmusic.core.common.model.LyricWordActivityPolicy =
            com.rawsmusic.core.common.model.LyricWordActivityPolicy(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<LyricTimingCorrection> = withContext(Dispatchers.IO) {
        previewWordTimingFromSource(
            source = result.vocalsFile,
            sourceIdentity = result.id,
            sourceName = result.sourceName,
            durationMs = result.durationMs(),
            lyrics = lyrics,
            originalLyricHash = originalLyricHash,
            policy = policy,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    private suspend fun previewWordTimingFromSource(
        source: java.io.File,
        sourceIdentity: String,
        sourceName: String,
        durationMs: Long,
        lyrics: LyricData,
        originalLyricHash: String,
        policy: com.rawsmusic.core.common.model.LyricWordActivityPolicy,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<LyricTimingCorrection> = withContext(Dispatchers.IO) {
        runCatching {
            require(source.isFile) { "当前歌曲音频文件不可读" }
            val selection = AiLyricForcedAlignmentProviderFactory.resolve(appContext, lyrics)
                ?: error("没有与当前歌词语言匹配的已安装 CTC 模型")
            check(!isCancelled()) { "CTC analysis cancelled" }
            val wav = AiLyricCtcAudioInput.prepare(
                cacheDir = appContext.cacheDir,
                source = source,
                sampleRate = selection.contract.sampleRate,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            try {
                val syntheticResult = AiSeparationResult(
                    id = sourceIdentity,
                    sourceName = sourceName,
                    modelId = selection.contract.id,
                    modelVersion = selection.contract.version,
                    modelName = selection.contract.id,
                    sampleRate = selection.contract.sampleRate,
                    totalFrames = durationMs.coerceAtLeast(0L) *
                        selection.contract.sampleRate / 1_000L,
                    processedSegments = 0,
                    elapsedMs = 0L,
                    createdAtEpochMs = System.currentTimeMillis(),
                    directory = wav.parentFile?.absolutePath.orEmpty(),
                    sourceDurationMs = durationMs,
                    outputFormat = "wav",
                )
                selection.provider.preview(
                    context = appContext,
                    result = syntheticResult,
                    vocalStem = wav,
                    lyrics = lyrics,
                    originalLyricHash = originalLyricHash,
                    contract = selection.contract,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )?.getOrThrow() ?: error("当前 CTC 模型不支持逐字歌词对齐")
            } finally {
                wav.delete()
            }
        }.onFailure { error ->
            Log.w(TAG, "AI_LYRIC_CTC failed source=$sourceName reason=${error.message}", error)
        }
    }

    suspend fun decide(
        correction: LyricTimingCorrection,
        status: LyricTimingCorrectionStatus,
    ): Result<LyricTimingCorrection> = withContext(Dispatchers.IO) {
        runCatching {
            require(status == LyricTimingCorrectionStatus.PREVIEW ||
                status == LyricTimingCorrectionStatus.ACCEPTED ||
                status == LyricTimingCorrectionStatus.REJECTED) {
                "不支持保存当前歌词修正状态"
            }
            correction.copy(status = status).also { decisionStore.save(it) }
        }
    }

    suspend fun apply(
        song: AudioFile,
        correction: LyricTimingCorrection,
        outputMode: AiLyricTimingOutputMode = AiLyricTimingOutputMode.KEEP_CURRENT,
    ): Result<AiLyricTimingWritebackReceipt> =
        writebackService.apply(song, correction, outputMode)

    suspend fun rollback(
        receipt: AiLyricTimingWritebackReceipt,
    ): Result<Unit> = writebackService.rollback(receipt)

    private fun correctionId(
        result: AiSeparationResult,
        originalLyricHash: String,
        analyzerVersion: String,
    ): String = "${result.id}:$analyzerVersion:${originalLyricHash.trim()}"

    private fun AiSeparationResult.durationMs(): Long = sourceDurationMs.takeIf { it > 0L }
        ?: (totalFrames * 1000L / sampleRate.coerceAtLeast(1))

    companion object {
        private const val TAG = "AiLyricTiming"

        fun get(context: android.content.Context): AiLyricTimingCorrectionService {
            return AiLyricTimingCorrectionService(
                appContext = context.applicationContext,
                resultStore = AiSeparationResultStore.get(context),
                decisionStore = AiLyricTimingDecisionStore.get(context),
                writebackService = AiLyricTimingWritebackService.get(context),
                fastVocalAlignmentService = AiFastVocalAlignmentService.get(context),
            )
        }
    }
}
