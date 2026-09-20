package com.rawsmusic.ai.variant

import android.content.Context
import com.rawsmusic.ai.instrument.AiInstrumentLeadArtifact
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.separation.AiStemDependency
import java.io.File

internal data class AiOfflineVariantMixResult(
    val wavFile: File,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
    val peakBeforeNormalization: Float,
    val normalizationGainDb: Float,
)

/**
 * Offline-only decoder/mixer boundary. Every input is normalized to the stem timeline first;
 * generated lead tails are deliberately truncated and short inputs are zero padded so switching
 * variants cannot change the semantic song duration.
 */
internal class AiOfflineMediaMixer(context: Context) {
    private val jobsRoot = File(context.applicationContext.cacheDir, "ai_performance/variant_mix").apply {
        mkdirs()
    }

    fun mix(
        dependency: AiStemDependency,
        lead: AiInstrumentLeadArtifact,
        mode: AiMediaVariantMode,
        config: AiMediaVariantMixConfig,
        workToken: String,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AiOfflineVariantMixResult {
        require(lead.performanceDependencyFingerprint == dependency.dependencyFingerprint) {
            "AI instrument lead does not belong to the selected stem dependency"
        }
        require(dependency.isUsable()) { "AI stem dependency is unavailable" }
        require(lead.audioFile.isFile && lead.audioFile.length() > 44L) { "AI instrument lead is missing" }
        require(FFmpegBridge.isLoaded()) { "FFmpeg 当前不可用" }

        val sampleRate = dependency.sampleRate
        val frameCount = dependency.totalFrames
        require(sampleRate > 0 && frameCount > 0L) { "AI stem timeline is invalid" }
        val jobDir = File(jobsRoot, workToken).apply {
            deleteRecursively()
            require(mkdirs()) { "无法创建 AI media variant 临时目录" }
        }
        try {
            val instrumentalPcm = normalize(
                dependency.instrumentalFile,
                File(jobDir, "instrumental.s16le"),
                sampleRate,
            )
            onProgress(0.08f)
            check(!isCancelled()) { "AI media variant generation cancelled" }

            val leadPcm = normalize(
                lead.audioFile,
                File(jobDir, "lead.s16le"),
                sampleRate,
            )
            onProgress(0.16f)
            check(!isCancelled()) { "AI media variant generation cancelled" }

            val inputs = mutableListOf(
                AiRawMixInput(instrumentalPcm, config.instrumentalGainDb),
                AiRawMixInput(leadPcm, config.leadGainDb),
            )
            if (mode == AiMediaVariantMode.VOCAL_ENSEMBLE) {
                val vocalsPcm = normalize(
                    dependency.vocalsFile,
                    File(jobDir, "vocals.s16le"),
                    sampleRate,
                )
                inputs.add(0, AiRawMixInput(vocalsPcm, config.vocalGainDb))
                onProgress(0.24f)
            }

            val mixBase = if (mode == AiMediaVariantMode.VOCAL_ENSEMBLE) 0.24f else 0.16f
            val mixed = AiPcm16StereoMixer.mix(
                inputs = inputs,
                outputWav = File(jobDir, "mixed.wav"),
                sampleRate = sampleRate,
                frameCount = frameCount,
                outputCeilingDb = config.outputCeilingDb,
                onProgress = { fraction -> onProgress(mixBase + (1f - mixBase) * fraction) },
                isCancelled = isCancelled,
            )
            return AiOfflineVariantMixResult(
                wavFile = mixed.outputFile,
                sampleRate = mixed.sampleRate,
                channels = mixed.channels,
                frameCount = mixed.frameCount,
                peakBeforeNormalization = mixed.peakBeforeNormalization,
                normalizationGainDb = mixed.normalizationGainDb,
            )
        } catch (error: Throwable) {
            jobDir.deleteRecursively()
            throw error
        }
    }

    fun cleanup(workToken: String) {
        File(jobsRoot, workToken).deleteRecursively()
    }

    private fun normalize(input: File, output: File, sampleRate: Int): File {
        output.delete()
        val result = FFmpegBridge.convertToRawPcm(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            targetSampleRate = sampleRate,
            bitsPerSample = 16,
            channels = 2,
        )
        require(result == 0 && output.isFile && output.length() >= 4L) {
            "无法解码 AI media variant 输入：${input.name} ($result)"
        }
        require(output.length() % 4L == 0L) { "AI media variant PCM 不是 stereo/s16le 帧边界" }
        return output
    }
}
