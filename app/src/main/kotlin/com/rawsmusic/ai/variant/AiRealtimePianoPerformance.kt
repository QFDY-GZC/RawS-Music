package com.rawsmusic.ai.variant

import android.content.Context
import com.rawsmusic.ai.instrument.AiInstalledInstrumentPack
import com.rawsmusic.ai.instrument.AiInstrumentPackStore
import com.rawsmusic.ai.instrument.Pcm16WaveData
import com.rawsmusic.ai.instrument.Pcm16WaveReader
import com.rawsmusic.ai.instrument.SampledPianoRenderer
import com.rawsmusic.ai.melody.AiInstalledMelodyModel
import com.rawsmusic.ai.melody.AiMelodyModelStore
import com.rawsmusic.ai.melody.AiPerformanceNote
import com.rawsmusic.ai.melody.AiPerformanceTrack
import com.rawsmusic.ai.melody.AiPitchContourRefiner
import com.rawsmusic.ai.melody.RmvpeMelFeatureExtractor
import com.rawsmusic.ai.melody.RmvpeOnnxSession
import com.rawsmusic.ai.melody.RmvpePitchDecoder
import com.rawsmusic.separation.AiRealtimeSeparatedBlockTransformer
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** Buffered realtime piano transform. Heavy separation and RMVPE inference stay on the AI worker. */
internal class AiRealtimePianoPerformance(
    context: Context,
    private val model: AiInstalledMelodyModel,
    private val pack: AiInstalledInstrumentPack,
    private var mode: AiMediaVariantMode,
) : AiRealtimeSeparatedBlockTransformer {
    private val appContext = context.applicationContext
    private val descriptor = model.descriptor
    private val featureExtractor = RmvpeMelFeatureExtractor(descriptor)
    @Volatile private var session: RmvpeOnnxSession? = null
    @Volatile private var closed = false
    private val sessionLock = Any()
    private val synth = RealtimePianoSegmentSynth(pack)
    private var historyMono = FloatArray(0)
    val instrumentPackKey: String = "${pack.manifest.id}:${pack.manifest.version}:${pack.manifest.contentFingerprint()}"

    fun setMode(value: AiMediaVariantMode) {
        mode = value
    }

    override fun transform(
        mixtureStereo: FloatArray,
        vocalStereo: FloatArray,
        sampleRate: Int,
    ): FloatArray {
        require(mixtureStereo.size == vocalStereo.size && mixtureStereo.size % 2 == 0)
        val sourceFrames = mixtureStereo.size / 2
        if (sourceFrames <= 1) return mixtureStereo.copyOf()

        val vocalMono = FloatArray(sourceFrames) { frame ->
            (vocalStereo[frame * 2] + vocalStereo[frame * 2 + 1]) * 0.5f
        }
        val vocal16k = resampleMono(vocalMono, sampleRate, descriptor.sampleRate)
        val historyFrames = historyMono.size
        val combined = FloatArray(historyFrames + vocal16k.size)
        historyMono.copyInto(combined)
        vocal16k.copyInto(combined, historyFrames)

        val frameCount = (combined.size / descriptor.hopLength + 1).coerceAtLeast(1)
        val features = featureExtractor.extract(combined, 0, frameCount)
        val paddedFrames = ((frameCount + 31) / 32) * 32
        val paddedMel = if (paddedFrames == frameCount) {
            features.mel
        } else {
            FloatArray(descriptor.melBins * paddedFrames).also { output ->
                repeat(descriptor.melBins) { mel ->
                    System.arraycopy(
                        features.mel,
                        mel * frameCount,
                        output,
                        mel * paddedFrames,
                        frameCount,
                    )
                }
            }
        }
        val salience = session().infer(paddedMel, paddedFrames)
        val f0 = FloatArray(frameCount)
        val confidence = FloatArray(frameCount)
        repeat(frameCount) { frame ->
            val pitch = RmvpePitchDecoder.decodeFrame(
                salience = salience,
                offset = frame * descriptor.classCount,
                threshold = descriptor.voicingThreshold,
            )
            f0[frame] = pitch.f0Hz
            confidence[frame] = pitch.confidence
        }
        val refined = AiPitchContourRefiner.refine(f0, confidence, descriptor.voicingThreshold)
        val performance = AiPerformanceTrack(
            dependencyFingerprint = "realtime",
            modelId = descriptor.id,
            modelVersion = descriptor.version,
            extractorVersion = REALTIME_EXTRACTOR_VERSION,
            sampleRate = descriptor.sampleRate,
            hopLength = descriptor.hopLength,
            f0Hz = refined,
            voicingConfidence = confidence,
            loudnessRms = features.rms,
        )

        val combinedPackFrames = max(
            1,
            ceil(combined.size.toDouble() * pack.manifest.sampleRate / descriptor.sampleRate).toInt(),
        )
        val renderedCombined = synth.render(performance, combinedPackFrames)
        val historyPackFrames = (historyFrames.toLong() * pack.manifest.sampleRate / descriptor.sampleRate)
            .toInt().coerceIn(0, combinedPackFrames)
        val currentPack = renderedCombined.copyOfRange(
            historyPackFrames * pack.manifest.channels,
            renderedCombined.size,
        )
        val lead = resampleStereoExact(
            input = currentPack,
            inputChannels = pack.manifest.channels,
            outputFrames = sourceFrames,
        )

        val config = AiMediaVariantMixConfig.defaultFor(mode)
        val vocalGain = dbToLinear(config.vocalGainDb)
        val instrumentalGain = dbToLinear(config.instrumentalGainDb)
        val leadGain = dbToLinear(config.leadGainDb)
        val output = FloatArray(mixtureStereo.size)
        for (i in output.indices) {
            val vocal = vocalStereo[i]
            val instrumental = mixtureStereo[i] - vocal
            output[i] = softLimit(
                vocal * vocalGain + instrumental * instrumentalGain + lead[i] * leadGain,
            )
        }

        val keep = minOf(HISTORY_SAMPLES, combined.size)
        historyMono = combined.copyOfRange(combined.size - keep, combined.size)
        return output
    }

    override fun reset(reason: String) {
        historyMono = FloatArray(0)
        synth.reset()
    }

    fun prewarm(): Result<Unit> = runCatching {
        session()
        Unit
    }

    private fun session(): RmvpeOnnxSession {
        check(!closed) { "AI 演奏器已关闭" }
        return session ?: synchronized(sessionLock) {
            check(!closed) { "AI 演奏器已关闭" }
            session ?: RmvpeOnnxSession.open(appContext, model).also { session = it }
        }
    }

    override fun close() {
        closed = true
        synchronized(sessionLock) {
            session?.close()
            session = null
        }
        synth.reset()
        historyMono = FloatArray(0)
    }

    companion object {
        const val REALTIME_EXTRACTOR_VERSION = "rmvpe-realtime-performance-v1"
        private const val HISTORY_SAMPLES = 8_000 // 500 ms at 16 kHz, keeps notes continuous over model blocks.

        fun createPreferred(context: Context, mode: AiMediaVariantMode): Result<AiRealtimePianoPerformance> =
            runCatching {
                val pack = AiInstrumentPackStore.get(context).installedPacks()
                    .sortedWith(compareByDescending<AiInstalledInstrumentPack> { it.manifest.version }.thenBy { it.manifest.id })
                    .firstOrNull()
                    ?: error("请先安装乐器采样包")
                create(context, pack, mode).getOrThrow()
            }

        fun create(
            context: Context,
            pack: AiInstalledInstrumentPack,
            mode: AiMediaVariantMode,
        ): Result<AiRealtimePianoPerformance> = runCatching {
            val model = AiMelodyModelStore.get(context).resolveRecommended()
                ?: error("请先安装 RMVPE 旋律模型")
            AiRealtimePianoPerformance(context, model, pack, mode)
        }

        private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

        private fun softLimit(value: Float): Float {
            val magnitude = abs(value)
            if (magnitude <= 0.98f) return value
            val shoulder = (1.0 - kotlin.math.exp(-(magnitude - 0.98f) * 4.0)).toFloat()
            return sign(value) * (0.98f + 0.02f * shoulder).coerceAtMost(1f)
        }

        private fun resampleMono(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
            if (inputRate == outputRate) return input.copyOf()
            val outputFrames = max(2, (input.size.toDouble() * outputRate / inputRate).roundToInt())
            val output = FloatArray(outputFrames)
            val scale = inputRate.toDouble() / outputRate
            for (frame in 0 until outputFrames) {
                val position = (frame * scale).coerceAtMost((input.size - 1).toDouble())
                val lower = floor(position).toInt()
                val upper = minOf(lower + 1, input.lastIndex)
                val fraction = (position - lower).toFloat()
                output[frame] = input[lower] + (input[upper] - input[lower]) * fraction
            }
            return output
        }

        private fun resampleStereoExact(
            input: FloatArray,
            inputChannels: Int,
            outputFrames: Int,
        ): FloatArray {
            require(inputChannels in 1..2 && input.size % inputChannels == 0)
            val inputFrames = input.size / inputChannels
            if (inputFrames <= 1) return FloatArray(outputFrames * 2)
            val output = FloatArray(outputFrames * 2)
            val scale = (inputFrames - 1).toDouble() / (outputFrames - 1).coerceAtLeast(1)
            for (frame in 0 until outputFrames) {
                val position = frame * scale
                val lower = floor(position).toInt().coerceIn(0, inputFrames - 1)
                val upper = minOf(lower + 1, inputFrames - 1)
                val fraction = (position - lower).toFloat()
                for (channel in 0..1) {
                    val sourceChannel = channel.coerceAtMost(inputChannels - 1)
                    val a = input[lower * inputChannels + sourceChannel]
                    val b = input[upper * inputChannels + sourceChannel]
                    output[frame * 2 + channel] = a + (b - a) * fraction
                }
            }
            return output
        }
    }
}

private class RealtimePianoSegmentSynth(
    private val pack: AiInstalledInstrumentPack,
) {
    private val selector = SampledPianoRenderer()
    private val cache = object : LinkedHashMap<String, Pcm16WaveData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm16WaveData>?): Boolean =
            size > 12
    }

    fun reset() = synchronized(cache) { cache.clear() }

    fun render(performance: AiPerformanceTrack, exactFrames: Int): FloatArray {
        val channels = pack.manifest.channels
        val output = FloatArray(exactFrames * channels)
        val notes = performance.notes()
        for (note in notes) {
            renderNote(note, performance, output, exactFrames)
        }
        return output
    }

    private fun renderNote(
        note: AiPerformanceNote,
        performance: AiPerformanceTrack,
        output: FloatArray,
        exactFrames: Int,
    ) {
        val velocityMidi = (note.velocity * 126f + 1f).toInt().coerceIn(1, 127)
        val sampleManifest = selector.selectSample(pack.manifest.samples, note.midiNote, velocityMidi)
        val sample = synchronized(cache) {
            cache[sampleManifest.path] ?: Pcm16WaveReader.read(
                pack.sampleFile(sampleManifest),
                pack.manifest.sampleRate,
                pack.manifest.channels,
            ).also { cache[sampleManifest.path] = it }
        }
        val startUs = performance.frameTimeUs(note.startFrame)
        val endUs = performance.frameTimeUs(note.endFrameExclusive)
        val start = (startUs * pack.manifest.sampleRate / 1_000_000L).toInt().coerceAtLeast(0)
        if (start >= exactFrames) return
        val noteFrames = max(1, ((endUs - startUs) * pack.manifest.sampleRate / 1_000_000L).toInt())
        val releaseFrames = (pack.manifest.sampleRate * 0.12f).toInt().coerceAtLeast(1)
        val attackFrames = (pack.manifest.sampleRate * 0.006f).toInt().coerceAtLeast(1)
        val maxFrames = minOf(exactFrames - start, noteFrames + releaseFrames)
        val baseRate = 2.0.pow((note.midiNote - sampleManifest.rootMidiNote) / 12.0)
        val gain = note.velocity.coerceIn(0f, 1f) * 10.0.pow(sampleManifest.gainDb / 20.0).toFloat()
        var sourcePosition = 0.0
        for (local in 0 until maxFrames) {
            val sustaining = local < noteFrames
            val loopStart = sampleManifest.loopStartFrame?.toDouble()
            val loopEnd = sampleManifest.loopEndFrameExclusive?.toDouble()
            if (sustaining && loopStart != null && loopEnd != null && sourcePosition >= loopEnd - 1.0) {
                sourcePosition = loopStart + ((sourcePosition - loopStart) % (loopEnd - loopStart))
            }
            if (sourcePosition >= sample.frameCount - 1) break
            val pitchIndex = ((local.toDouble() / noteFrames) * note.pitchCurveCents.size)
                .toInt().coerceIn(0, note.pitchCurveCents.lastIndex)
            val rate = baseRate * 2.0.pow(note.pitchCurveCents[pitchIndex] / 1200.0)
            val attack = (local.toFloat() / attackFrames).coerceIn(0f, 1f)
            val release = if (local < noteFrames) 1f else {
                1f - ((local - noteFrames).toFloat() / releaseFrames).coerceIn(0f, 1f)
            }
            val envelope = attack * release * gain
            val sourceIndex = sourcePosition.toInt()
            val fraction = (sourcePosition - sourceIndex).toFloat()
            for (channel in 0 until pack.manifest.channels) {
                val a = sample.frames[sourceIndex * sample.channels + channel.coerceAtMost(sample.channels - 1)]
                val b = sample.frames[(sourceIndex + 1) * sample.channels + channel.coerceAtMost(sample.channels - 1)]
                output[(start + local) * pack.manifest.channels + channel] +=
                    (a + (b - a) * fraction) * envelope
            }
            sourcePosition += rate
        }
    }
}
