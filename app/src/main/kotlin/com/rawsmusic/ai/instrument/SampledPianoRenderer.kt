package com.rawsmusic.ai.instrument

import com.rawsmusic.ai.melody.AiPerformanceNote
import com.rawsmusic.ai.melody.AiPerformanceTrack
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.max
import kotlin.math.pow

/**
 * Offline expressive sampled-instrument renderer.
 *
 * The renderer consumes the note view for event boundaries, but reads each note's continuous
 * pitchCurveCents while rendering, so vibrato/portamento survives for every melodic sample pack.
 */
class SampledPianoRenderer : AiInstrumentRenderer {
    override val rendererId: String = "sampled-instrument"
    override val rendererVersion: String = "3"

    override fun render(
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        outputFile: File,
        config: AiInstrumentRenderConfig,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): AiInstrumentRenderResult {
        val notes = performance.notes()
        require(notes.isNotEmpty()) { "performance track contains no renderable notes" }
        outputFile.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "cannot create render directory" } }

        val sampleRate = pack.manifest.sampleRate
        val channels = pack.manifest.channels
        val releaseFrames = (config.releaseMs * sampleRate / 1000f).toInt().coerceAtLeast(0)
        val maxTailFrames = config.maximumTailMs.toLong() * sampleRate / 1000L
        val performanceFrames = ceil(performance.durationUs * sampleRate / 1_000_000.0).toLong()
        val lastNoteEnd = notes.maxOf { note -> performance.frameTimeUs(note.endFrameExclusive) }
        val noteEndFrames = ceil(lastNoteEnd * sampleRate / 1_000_000.0).toLong()
        val totalFrames = max(performanceFrames, noteEndFrames) + minOf(maxTailFrames, releaseFrames.toLong())
        require(totalFrames > 0) { "rendered lead duration is empty" }

        val notePlans = notes.map { note -> buildPlan(note, performance, pack, sampleRate, config) }
        val cache = SampleCache(pack)
        val block = FloatArray(BLOCK_FRAMES * channels)
        val active = ArrayList<Voice>()
        var nextPlan = 0
        var frameCursor = 0L
        var peak = 0f

        try {
            Pcm16WaveWriter(outputFile, sampleRate, channels, totalFrames).use { writer ->
                while (frameCursor < totalFrames) {
                    if (isCancelled()) throw InterruptedException("instrument render cancelled")
                    java.util.Arrays.fill(block, 0f)
                    val frames = minOf(BLOCK_FRAMES.toLong(), totalFrames - frameCursor).toInt()
                    while (nextPlan < notePlans.size && notePlans[nextPlan].startOutputFrame < frameCursor + frames) {
                        val plan = notePlans[nextPlan++]
                        active += Voice(plan, cache.load(plan.sample))
                    }
                    val iterator = active.iterator()
                    while (iterator.hasNext()) {
                        val voice = iterator.next()
                        if (voice.renderInto(block, frameCursor, frames, channels, releaseFrames)) {
                            iterator.remove()
                        }
                    }
                    val master = dbToLinear(config.masterGainDb)
                    for (i in 0 until frames * channels) {
                        val mixed = block[i] * master
                        peak = max(peak, abs(mixed))
                        // Soft saturation only protects the generated artifact; no playback DSP is involved.
                        block[i] = softLimit(mixed)
                    }
                    writer.writeInterleaved(block, frames)
                    frameCursor += frames
                    onProgress((frameCursor.toDouble() / totalFrames).toFloat().coerceIn(0f, 1f))
                }
            }
            onProgress(1f)
            return AiInstrumentRenderResult(
                outputFile = outputFile,
                audioFormat = AUDIO_FORMAT,
                sampleRate = sampleRate,
                channels = channels,
                frameCount = totalFrames,
                renderedNotes = notePlans.size,
                peakBeforeLimiter = peak,
            )
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    private fun buildPlan(
        note: AiPerformanceNote,
        performance: AiPerformanceTrack,
        pack: AiInstalledInstrumentPack,
        outputRate: Int,
        config: AiInstrumentRenderConfig,
    ): NotePlan {
        val velocityMidi = (note.velocity * 126f + 1f).toInt().coerceIn(1, 127)
        val sample = selectSample(pack.manifest.samples, note.midiNote, velocityMidi)
        val startUs = performance.frameTimeUs(note.startFrame)
        val endUs = performance.frameTimeUs(note.endFrameExclusive)
        val startOutput = startUs * outputRate / 1_000_000L
        val noteFrames = max(1L, (endUs - startUs) * outputRate / 1_000_000L)
        return NotePlan(
            note = note,
            sample = sample,
            startOutputFrame = startOutput,
            noteOutputFrames = noteFrames,
            baseRate = 2.0.pow((note.midiNote - sample.rootMidiNote) / 12.0),
            noteGain = note.velocity.coerceIn(0f, 1f) * dbToLinear(sample.gainDb),
            attackFrames = (config.attackMs * outputRate / 1000f).toInt().coerceAtLeast(1),
        )
    }

    internal fun selectSample(
        samples: List<AiInstrumentSampleManifest>,
        targetMidi: Int,
        velocityMidi: Int,
    ): AiInstrumentSampleManifest {
        require(samples.isNotEmpty())
        return samples.minWithOrNull(
            compareBy<AiInstrumentSampleManifest> {
                when {
                    velocityMidi < it.velocityMin -> it.velocityMin - velocityMidi
                    velocityMidi > it.velocityMax -> velocityMidi - it.velocityMax
                    else -> 0
                }
            }.thenBy { abs(targetMidi - it.rootMidiNote) }
                .thenBy { abs((it.velocityMin + it.velocityMax) / 2 - velocityMidi) }
                .thenBy { it.path }
        ) ?: error("instrument pack has no samples")
    }

    private data class NotePlan(
        val note: AiPerformanceNote,
        val sample: AiInstrumentSampleManifest,
        val startOutputFrame: Long,
        val noteOutputFrames: Long,
        val baseRate: Double,
        val noteGain: Float,
        val attackFrames: Int,
    )

    private class Voice(
        private val plan: NotePlan,
        private val sample: Pcm16WaveData,
    ) {
        private var sourcePosition = 0.0

        /** Returns true when this voice can be discarded. */
        fun renderInto(
            target: FloatArray,
            blockStartFrame: Long,
            blockFrames: Int,
            outputChannels: Int,
            releaseFrames: Int,
        ): Boolean {
            val localStart = max(0L, plan.startOutputFrame - blockStartFrame).toInt()
            if (localStart >= blockFrames) return false
            var outputFrame = localStart
            while (outputFrame < blockFrames) {
                val absoluteFrame = blockStartFrame + outputFrame
                val voiceFrame = absoluteFrame - plan.startOutputFrame
                if (voiceFrame < 0L) {
                    outputFrame++
                    continue
                }
                val releaseProgress = voiceFrame - plan.noteOutputFrames
                if (releaseProgress >= releaseFrames) return true
                val sustaining = voiceFrame < plan.noteOutputFrames
                val loopStart = plan.sample.loopStartFrame?.toDouble()
                val loopEnd = plan.sample.loopEndFrameExclusive?.toDouble()
                if (sustaining && loopStart != null && loopEnd != null && sourcePosition >= loopEnd - 1.0) {
                    val loopLength = loopEnd - loopStart
                    sourcePosition = loopStart + ((sourcePosition - loopStart) % loopLength)
                }
                if (sourcePosition >= sample.frameCount - 1) return true
                val pitchFrame = ((voiceFrame.toDouble() / plan.noteOutputFrames.coerceAtLeast(1L)) *
                    plan.note.pitchCurveCents.size).toInt().coerceIn(0, plan.note.pitchCurveCents.lastIndex)
                val cents = plan.note.pitchCurveCents[pitchFrame]
                val rate = plan.baseRate * 2.0.pow(cents / 1200.0)
                val attack = (voiceFrame.toFloat() / plan.attackFrames).coerceIn(0f, 1f)
                val release = if (releaseProgress <= 0L || releaseFrames <= 0) {
                    1f
                } else {
                    1f - (releaseProgress.toFloat() / releaseFrames).coerceIn(0f, 1f)
                }
                val envelope = attack * release * plan.noteGain
                val sourceIndex = sourcePosition.toInt()
                val fraction = (sourcePosition - sourceIndex).toFloat()
                for (channel in 0 until outputChannels) {
                    val sourceChannel = channel.coerceAtMost(sample.channels - 1)
                    val a = sample.frames[sourceIndex * sample.channels + sourceChannel]
                    val b = sample.frames[(sourceIndex + 1) * sample.channels + sourceChannel]
                    target[outputFrame * outputChannels + channel] += (a + (b - a) * fraction) * envelope
                }
                sourcePosition += rate
                outputFrame++
            }
            return false
        }
    }

    private class SampleCache(private val pack: AiInstalledInstrumentPack) {
        private val entries = object : LinkedHashMap<String, Pcm16WaveData>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm16WaveData>?): Boolean =
                size > MAX_CACHED_SAMPLES
        }

        fun load(sample: AiInstrumentSampleManifest): Pcm16WaveData = synchronized(entries) {
            entries[sample.path] ?: Pcm16WaveReader.read(
                pack.sampleFile(sample),
                pack.manifest.sampleRate,
                pack.manifest.channels,
            ).also { entries[sample.path] = it }
        }
    }

    companion object {
        const val AUDIO_FORMAT = "wav_pcm_s16le"
        private const val BLOCK_FRAMES = 2048
        private const val MAX_CACHED_SAMPLES = 12

        private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

        private fun softLimit(value: Float): Float {
            val magnitude = abs(value)
            if (magnitude <= 0.98f) return value
            val shoulder = (1.0 - exp(-(magnitude - 0.98f) * 4.0)).toFloat()
            return sign(value) * (0.98f + 0.02f * shoulder).coerceAtMost(1f)
        }
    }
}
