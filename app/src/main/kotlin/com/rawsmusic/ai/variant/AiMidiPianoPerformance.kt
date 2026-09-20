package com.rawsmusic.ai.variant

import com.rawsmusic.ai.instrument.AiInstalledInstrumentPack
import com.rawsmusic.ai.instrument.Pcm16WaveData
import com.rawsmusic.ai.instrument.Pcm16WaveReader
import com.rawsmusic.ai.instrument.SampledPianoRenderer
import com.rawsmusic.ai.melody.AiPerformanceMidiArtifact
import com.rawsmusic.ai.melody.AiPerformanceMidiEvent
import com.rawsmusic.ai.melody.AiPerformanceMidiSequence
import com.rawsmusic.separation.AiRealtimeSeparatedBlockTransformer
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign

/**
 * MID/MIDI-style realtime playback path.
 *
 * The expensive analysis is completed once and cached as a .mid artifact. Playback only schedules
 * timestamped MIDI events and advances persistent sampled-piano voices while the cached vocal stem
 * supplies the vocal/instrumental split. No whole-song piano WAV or mixed media variant is required.
 */
internal class AiMidiPianoPerformance(
    private val artifact: AiPerformanceMidiArtifact,
    private val pack: AiInstalledInstrumentPack,
    private var mode: AiMediaVariantMode,
) : AiRealtimeSeparatedBlockTransformer {
    private val synth = MidiPianoSynth(pack, artifact.sequence)
    private var nextTimelineUs = Long.MIN_VALUE
    val instrumentPackKey: String = "${pack.manifest.id}:${pack.manifest.version}:${pack.manifest.contentFingerprint()}"

    override val realtimePlaybackSafe: Boolean = true

    fun setMode(value: AiMediaVariantMode) {
        mode = value
    }

    override fun transform(
        mixtureStereo: FloatArray,
        vocalStereo: FloatArray,
        sampleRate: Int,
    ): FloatArray = transformAt(mixtureStereo, vocalStereo, sampleRate, null)

    override fun transformAt(
        mixtureStereo: FloatArray,
        vocalStereo: FloatArray,
        sampleRate: Int,
        playbackPositionMs: Long?,
    ): FloatArray {
        require(mixtureStereo.size == vocalStereo.size && mixtureStereo.size % 2 == 0)
        val frames = mixtureStereo.size / 2
        if (frames <= 0) return mixtureStereo.copyOf()
        val reportedUs = playbackPositionMs?.coerceAtLeast(0L)?.times(1000L)
        if (nextTimelineUs == Long.MIN_VALUE) {
            nextTimelineUs = reportedUs ?: 0L
            synth.seek(nextTimelineUs)
        } else if (reportedUs != null && abs(reportedUs - nextTimelineUs) > SEEK_REANCHOR_US) {
            nextTimelineUs = reportedUs
            synth.seek(nextTimelineUs)
        }
        val startUs = nextTimelineUs
        val lead = synth.render(startUs, frames, sampleRate)
        nextTimelineUs += frames * 1_000_000L / sampleRate.coerceAtLeast(1)

        val config = AiMediaVariantMixConfig.defaultFor(mode)
        val vocalGain = dbToLinear(config.vocalGainDb)
        val instrumentalGain = dbToLinear(config.instrumentalGainDb)
        val leadGain = dbToLinear(config.leadGainDb)
        val output = FloatArray(mixtureStereo.size)
        for (index in output.indices) {
            val vocal = vocalStereo[index]
            val instrumental = mixtureStereo[index] - vocal
            output[index] = softLimit(
                vocal * vocalGain + instrumental * instrumentalGain + lead[index] * leadGain,
            )
        }
        return output
    }

    override fun reset(reason: String) {
        nextTimelineUs = Long.MIN_VALUE
        synth.reset()
    }

    override fun close() {
        reset("close")
    }

    fun midiFilePath(): String = artifact.file.absolutePath

    companion object {
        private const val SEEK_REANCHOR_US = 250_000L

        private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

        private fun softLimit(value: Float): Float {
            val magnitude = abs(value)
            if (magnitude <= 0.98f) return value
            val shoulder = (1.0 - kotlin.math.exp(-(magnitude - 0.98f) * 4.0)).toFloat()
            return sign(value) * (0.98f + 0.02f * shoulder).coerceAtMost(1f)
        }
    }
}

private class MidiPianoSynth(
    private val pack: AiInstalledInstrumentPack,
    private val sequence: AiPerformanceMidiSequence,
) {
    private val selector = SampledPianoRenderer()
    private val cache = object : LinkedHashMap<String, Pcm16WaveData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm16WaveData>?): Boolean =
            size > MAX_CACHED_SAMPLES
    }
    private val voices = ArrayList<Voice>()
    private var eventIndex = 0
    private var currentBendCents = 0f
    private var pendingSeekNote: AiPerformanceMidiEvent? = null

    init {
        prewarmMostUsedSamples()
    }

    fun reset() {
        voices.clear()
        eventIndex = 0
        currentBendCents = 0f
        pendingSeekNote = null
    }

    fun seek(positionUs: Long) {
        reset()
        val target = positionUs.coerceAtLeast(0L)
        // Reconstruct current monophonic note/pitch state. The sample restarts at the seek point;
        // subsequent PCM remains timeline-correct and avoids O(song length) sample advancement.
        var activeNote: AiPerformanceMidiEvent? = null
        while (eventIndex < sequence.events.size && sequence.events[eventIndex].timeUs < target) {
            val event = sequence.events[eventIndex++]
            when (event.type) {
                AiPerformanceMidiEvent.Type.NOTE_ON -> activeNote = event
                AiPerformanceMidiEvent.Type.NOTE_OFF -> if (activeNote?.note == event.note) activeNote = null
                AiPerformanceMidiEvent.Type.PITCH_BEND -> currentBendCents = event.pitchBendCents
            }
        }
        pendingSeekNote = activeNote
    }

    fun render(startUs: Long, frames: Int, outputRate: Int): FloatArray {
        val output = FloatArray(frames * 2)
        if (frames <= 0 || outputRate <= 0) return output
        pendingSeekNote?.let { event ->
            noteOn(event, outputRate)
            pendingSeekNote = null
        }
        val endUs = startUs + frames * 1_000_000L / outputRate
        var cursor = 0
        while (cursor < frames) {
            val nextEventUs = sequence.events.getOrNull(eventIndex)?.timeUs ?: Long.MAX_VALUE
            val boundary = if (nextEventUs in startUs until endUs) {
                (((nextEventUs - startUs) * outputRate) / 1_000_000L).toInt().coerceIn(cursor, frames)
            } else {
                frames
            }
            renderVoices(output, cursor, boundary, outputRate)
            cursor = boundary
            if (cursor >= frames) break
            val timestamp = sequence.events[eventIndex].timeUs
            while (eventIndex < sequence.events.size && sequence.events[eventIndex].timeUs == timestamp) {
                apply(sequence.events[eventIndex++], outputRate)
            }
        }
        return output
    }

    private fun prewarmMostUsedSamples() {
        val counts = LinkedHashMap<com.rawsmusic.ai.instrument.AiInstrumentSampleManifest, Int>()
        sequence.events.asSequence()
            .filter { it.type == AiPerformanceMidiEvent.Type.NOTE_ON }
            .forEach { event ->
                val sample = selector.selectSample(
                    pack.manifest.samples,
                    event.note,
                    event.velocity.coerceIn(1, 127),
                )
                counts[sample] = (counts[sample] ?: 0) + 1
            }
        counts.entries.sortedByDescending { it.value }
            .take(MAX_CACHED_SAMPLES)
            .forEach { (manifest, _) -> loadSample(manifest) }
    }

    private fun apply(event: AiPerformanceMidiEvent, outputRate: Int) {
        when (event.type) {
            AiPerformanceMidiEvent.Type.NOTE_ON -> noteOn(event, outputRate)
            AiPerformanceMidiEvent.Type.NOTE_OFF -> voices.filter { it.note == event.note && !it.releasing }
                .forEach { it.releasing = true }
            AiPerformanceMidiEvent.Type.PITCH_BEND -> {
                currentBendCents = event.pitchBendCents
                voices.filter { !it.releasing }.forEach { it.pitchBendCents = currentBendCents }
            }
        }
    }

    private fun noteOn(event: AiPerformanceMidiEvent, outputRate: Int) {
        val sampleManifest = selector.selectSample(pack.manifest.samples, event.note, event.velocity.coerceIn(1, 127))
        val sample = loadSample(sampleManifest)
        voices.filter { it.note == event.note && !it.releasing }.forEach { it.releasing = true }
        voices += Voice(
            note = event.note,
            sampleManifest = sampleManifest,
            sample = sample,
            velocityGain = (event.velocity.coerceIn(1, 127) / 127f) * dbToLinear(sampleManifest.gainDb),
            pitchBendCents = currentBendCents,
            attackFrames = (outputRate * ATTACK_MS / 1000f).toInt().coerceAtLeast(1),
            releaseFrames = (outputRate * RELEASE_MS / 1000f).toInt().coerceAtLeast(1),
        )
    }

    private fun loadSample(
        sampleManifest: com.rawsmusic.ai.instrument.AiInstrumentSampleManifest,
    ): Pcm16WaveData = synchronized(cache) {
        cache[sampleManifest.path] ?: Pcm16WaveReader.read(
            pack.sampleFile(sampleManifest),
            pack.manifest.sampleRate,
            pack.manifest.channels,
        ).also { cache[sampleManifest.path] = it }
    }

    private fun renderVoices(output: FloatArray, startFrame: Int, endFrame: Int, outputRate: Int) {
        if (startFrame >= endFrame) return
        val iterator = voices.iterator()
        while (iterator.hasNext()) {
            val voice = iterator.next()
            var frame = startFrame
            while (frame < endFrame) {
                if (voice.finished()) break
                val loopStart = voice.sampleManifest.loopStartFrame?.toDouble()
                val loopEnd = voice.sampleManifest.loopEndFrameExclusive?.toDouble()
                if (!voice.releasing && loopStart != null && loopEnd != null && voice.sourcePosition >= loopEnd - 1.0) {
                    voice.sourcePosition = loopStart + ((voice.sourcePosition - loopStart) % (loopEnd - loopStart))
                }
                if (voice.sourcePosition >= voice.sample.frameCount - 1) {
                    voice.done = true
                    break
                }
                val basePitch = 2.0.pow((voice.note - voice.sampleManifest.rootMidiNote) / 12.0)
                val bendPitch = 2.0.pow(voice.pitchBendCents / 1200.0)
                val sourcePerOutput = pack.manifest.sampleRate.toDouble() / outputRate.toDouble()
                val rate = sourcePerOutput * basePitch * bendPitch
                val attack = (voice.ageFrames.toFloat() / voice.attackFrames).coerceIn(0f, 1f)
                val release = if (!voice.releasing) 1f else {
                    1f - (voice.releaseAgeFrames.toFloat() / voice.releaseFrames).coerceIn(0f, 1f)
                }
                val envelope = attack * release * voice.velocityGain
                val sourceIndex = floor(voice.sourcePosition).toInt()
                val fraction = (voice.sourcePosition - sourceIndex).toFloat()
                for (channel in 0..1) {
                    val sourceChannel = channel.coerceAtMost(voice.sample.channels - 1)
                    val a = voice.sample.frames[sourceIndex * voice.sample.channels + sourceChannel]
                    val b = voice.sample.frames[(sourceIndex + 1) * voice.sample.channels + sourceChannel]
                    output[frame * 2 + channel] += (a + (b - a) * fraction) * envelope
                }
                voice.sourcePosition += rate
                voice.ageFrames++
                if (voice.releasing) voice.releaseAgeFrames++
                frame++
            }
            if (voice.finished()) iterator.remove()
        }
    }

    private data class Voice(
        val note: Int,
        val sampleManifest: com.rawsmusic.ai.instrument.AiInstrumentSampleManifest,
        val sample: Pcm16WaveData,
        val velocityGain: Float,
        var pitchBendCents: Float,
        val attackFrames: Int,
        val releaseFrames: Int,
        var sourcePosition: Double = 0.0,
        var ageFrames: Int = 0,
        var releasing: Boolean = false,
        var releaseAgeFrames: Int = 0,
        var done: Boolean = false,
    ) {
        fun finished(): Boolean = done || (releasing && releaseAgeFrames >= releaseFrames)
    }

    companion object {
        private const val ATTACK_MS = 6f
        private const val RELEASE_MS = 120f
        private const val MAX_CACHED_SAMPLES = 12
        private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
    }
}
