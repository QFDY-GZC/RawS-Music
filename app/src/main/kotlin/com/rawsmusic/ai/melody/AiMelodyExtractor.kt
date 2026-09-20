package com.rawsmusic.ai.melody

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.separation.AiStemDependency
import com.rawsmusic.separation.AiStemDependencyRequirement
import com.rawsmusic.separation.AiStemDependencyResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Offline vocal-melody extractor used by future AI instrument renderers.
 *
 * This class consumes an already completed [AiStemDependency]. It never starts source separation
 * and never participates in the real-time playback callback. The extracted track is cached by the
 * exact stem dependency + model revision, mirroring a media-variant dependency graph.
 */
class AiMelodyExtractor private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val dependencyResolver = AiStemDependencyResolver.get(appContext)
    private val modelStore = AiMelodyModelStore.get(appContext)
    private val trackStore = AiPerformanceTrackStore.get(appContext)
    private val workRoot = File(appContext.cacheDir, "ai_melody/work").apply { mkdirs() }

    suspend fun extractForSong(
        song: AudioFile,
        stemRequirement: AiStemDependencyRequirement = AiStemDependencyRequirement(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<AiPerformanceTrack> = withContext(Dispatchers.Default) {
        runCatching {
            val dependency = dependencyResolver.resolve(song, stemRequirement)
                ?: error("请先完成这首歌曲的人声/伴奏分离")
            val model = modelStore.resolveRecommended()
                ?: error("RMVPE 旋律模型尚未安装")
            extract(dependency, model, onProgress, isCancelled).getOrThrow()
        }
    }

    suspend fun extract(
        dependency: AiStemDependency,
        model: AiInstalledMelodyModel,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<AiPerformanceTrack> = withContext(Dispatchers.Default) {
        runCatching {
            require(dependency.isUsable()) { "AI stem dependency 已失效" }
            require(model.modelFile.isFile) { "RMVPE 模型不存在" }
            trackStore.load(dependency, model.descriptor)?.let { cached ->
                onProgress(1f)
                return@runCatching cached
            }
            check(!isCancelled()) { CANCELLED_MESSAGE }
            require(FFmpegBridge.isLoaded()) { "FFmpeg 当前不可用" }
            val rawPcm = File(
                workRoot,
                "${dependency.dependencyFingerprint.take(16)}_${System.nanoTime()}.s16le",
            )
            try {
                onProgress(0.02f)
                val decodeResult = FFmpegBridge.convertToRawPcm(
                    inputPath = dependency.vocalsFile.absolutePath,
                    outputPath = rawPcm.absolutePath,
                    targetSampleRate = model.descriptor.sampleRate,
                    bitsPerSample = 16,
                    channels = 1,
                )
                require(decodeResult == 0 && rawPcm.isFile && rawPcm.length() >= 4L) {
                    "人声音轨解码为 RMVPE PCM 失败：$decodeResult"
                }
                require(rawPcm.length() % 2L == 0L) { "RMVPE PCM 不是 s16le 样本边界" }
                check(!isCancelled()) { CANCELLED_MESSAGE }
                onProgress(0.08f)

                val totalSamples = rawPcm.length() / 2L
                require(totalSamples in 2..Int.MAX_VALUE.toLong()) { "人声音轨长度不受支持" }
                val descriptor = model.descriptor
                val totalFrames = (totalSamples / descriptor.hopLength + 1L).toInt()
                val f0 = FloatArray(totalFrames)
                val confidence = FloatArray(totalFrames)
                val loudness = FloatArray(totalFrames)
                val featureExtractor = RmvpeMelFeatureExtractor(descriptor)
                val chunkCount = ceil(totalFrames.toDouble() / descriptor.inferenceFrames).toInt()
                    .coerceAtLeast(1)

                RmvpeOnnxSession.open(appContext, model).use { session ->
                    var coreStart = 0
                    var chunkIndex = 0
                    while (coreStart < totalFrames) {
                        check(!isCancelled()) { CANCELLED_MESSAGE }
                        val coreEnd = min(totalFrames, coreStart + descriptor.inferenceFrames)
                        val inputStart = max(0, coreStart - descriptor.contextFrames)
                        val inputEnd = min(totalFrames, coreEnd + descriptor.contextFrames)
                        val inputFrames = inputEnd - inputStart
                        val features = featureExtractor.extract(
                            pcm16Mono = rawPcm,
                            totalSamples = totalSamples,
                            startFrame = inputStart,
                            frameCount = inputFrames,
                        )
                        val paddedFrames = roundUp32(inputFrames)
                        val paddedMel = padMelConstant(
                            source = features.mel,
                            melBins = descriptor.melBins,
                            sourceFrames = inputFrames,
                            targetFrames = paddedFrames,
                        )
                        val salience = session.infer(paddedMel, paddedFrames)
                        val coreOffset = coreStart - inputStart
                        repeat(coreEnd - coreStart) { localCore ->
                            val inputFrame = coreOffset + localCore
                            val globalFrame = coreStart + localCore
                            val decoded = RmvpePitchDecoder.decodeFrame(
                                salience = salience,
                                offset = inputFrame * descriptor.classCount,
                                threshold = descriptor.voicingThreshold,
                            )
                            f0[globalFrame] = decoded.f0Hz
                            confidence[globalFrame] = decoded.confidence
                            loudness[globalFrame] = features.rms[inputFrame]
                        }
                        coreStart = coreEnd
                        chunkIndex++
                        onProgress(0.08f + 0.86f * chunkIndex / chunkCount.toFloat())
                    }
                }

                val refinedF0 = AiPitchContourRefiner.refine(
                    f0Hz = f0,
                    confidence = confidence,
                    minimumConfidence = descriptor.voicingThreshold,
                )
                val track = AiPerformanceTrack(
                    dependencyFingerprint = dependency.dependencyFingerprint,
                    modelId = descriptor.id,
                    modelVersion = descriptor.version,
                    extractorVersion = EXTRACTOR_VERSION,
                    sampleRate = descriptor.sampleRate,
                    hopLength = descriptor.hopLength,
                    f0Hz = refinedF0,
                    voicingConfidence = confidence,
                    loudnessRms = loudness,
                )
                check(!isCancelled()) { CANCELLED_MESSAGE }
                trackStore.commit(dependency, descriptor, track)
                onProgress(1f)
                Log.i(
                    TAG,
                    "AI_MELODY_READY dependency=${dependency.dependencyFingerprint.take(12)} " +
                        "model=${descriptor.id}:${descriptor.version} frames=${track.frameCount} " +
                        "notes=${track.notes().size}",
                )
                track
            } finally {
                rawPcm.delete()
            }
        }.onFailure { error ->
            if (error.message != CANCELLED_MESSAGE) {
                Log.w(TAG, "AI_MELODY_FAILED reason=${error.message}", error)
            }
        }
    }

    companion object {
        const val EXTRACTOR_VERSION = "rmvpe-performance-v1"
        private const val CANCELLED_MESSAGE = "AI melody extraction cancelled"
        private const val TAG = "AiMelodyExtractor"
        @Volatile private var instance: AiMelodyExtractor? = null

        fun get(context: Context): AiMelodyExtractor = instance ?: synchronized(this) {
            instance ?: AiMelodyExtractor(context).also { instance = it }
        }

        internal fun roundUp32(value: Int): Int = ((value + 31) / 32) * 32

        internal fun padMelConstant(
            source: FloatArray,
            melBins: Int,
            sourceFrames: Int,
            targetFrames: Int,
        ): FloatArray {
            require(sourceFrames > 0 && targetFrames >= sourceFrames && targetFrames % 32 == 0)
            require(source.size == melBins * sourceFrames)
            if (targetFrames == sourceFrames) return source
            val output = FloatArray(melBins * targetFrames)
            repeat(melBins) { mel ->
                System.arraycopy(
                    source,
                    mel * sourceFrames,
                    output,
                    mel * targetFrames,
                    sourceFrames,
                )
            }
            return output
        }
    }
}
