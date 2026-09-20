package com.rawsmusic.ai.melody

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Stable, renderer-agnostic representation of the lead performance extracted from a vocal stem.
 *
 * Time is implicit: frame i starts at i * hopLength / sampleRate.  Keeping the continuous F0 and
 * voicing curve instead of collapsing immediately to MIDI is important for later flute, strings,
 * guzheng and other renderers that need vibrato, slides and portamento.
 */
class AiPerformanceTrack(
    val dependencyFingerprint: String,
    val modelId: String,
    val modelVersion: String,
    val extractorVersion: String,
    val sampleRate: Int,
    val hopLength: Int,
    f0Hz: FloatArray,
    voicingConfidence: FloatArray,
    loudnessRms: FloatArray,
) {
    val f0Hz: FloatArray = f0Hz.copyOf()
    val voicingConfidence: FloatArray = voicingConfidence.copyOf()
    val loudnessRms: FloatArray = loudnessRms.copyOf()

    init {
        require(dependencyFingerprint.isNotBlank()) { "performance dependency fingerprint is empty" }
        require(modelId.isNotBlank() && modelVersion.isNotBlank()) { "performance model identity is empty" }
        require(extractorVersion.isNotBlank()) { "performance extractor version is empty" }
        require(sampleRate > 0 && hopLength > 0) { "performance timing is invalid" }
        require(this.f0Hz.size == this.voicingConfidence.size && this.f0Hz.size == this.loudnessRms.size) {
            "performance frame arrays must have identical lengths"
        }
        require(this.f0Hz.isNotEmpty()) { "performance track is empty" }
        require(this.f0Hz.all { it.isFinite() && it >= 0f }) { "performance F0 contains invalid values" }
        require(this.voicingConfidence.all { it.isFinite() && it in 0f..1f }) {
            "performance voicing confidence is invalid"
        }
        require(this.loudnessRms.all { it.isFinite() && it >= 0f }) { "performance loudness is invalid" }
    }

    val frameCount: Int get() = f0Hz.size
    val durationUs: Long get() = frameTimeUs(frameCount)

    fun frameTimeUs(frameIndex: Int): Long =
        frameIndex.toLong().coerceAtLeast(0L) * hopLength * 1_000_000L / sampleRate

    fun point(frameIndex: Int): AiPerformancePoint {
        require(frameIndex in 0 until frameCount)
        return AiPerformancePoint(
            timeUs = frameTimeUs(frameIndex),
            f0Hz = f0Hz[frameIndex],
            voicingConfidence = voicingConfidence[frameIndex],
            loudnessRms = loudnessRms[frameIndex],
        )
    }

    fun notes(config: AiPerformanceNoteSegmentation = AiPerformanceNoteSegmentation()): List<AiPerformanceNote> =
        AiPerformanceNoteSegmenter.segment(this, config)
}

data class AiPerformancePoint(
    val timeUs: Long,
    val f0Hz: Float,
    val voicingConfidence: Float,
    val loudnessRms: Float,
)

data class AiPerformanceNoteSegmentation(
    val minimumConfidence: Float = 0.03f,
    val minimumNoteMs: Int = 40,
    val maximumUnvoicedGapMs: Int = 30,
    val splitSemitones: Float = 0.72f,
    val splitHoldMs: Int = 30,
)

/** A note view derived from the continuous track; [pitchCurveCents] keeps expressive deviation. */
data class AiPerformanceNote(
    val startFrame: Int,
    val endFrameExclusive: Int,
    val midiNote: Int,
    val referenceF0Hz: Float,
    val meanConfidence: Float,
    val velocity: Float,
    val pitchCurveCents: FloatArray,
) {
    val frameCount: Int get() = endFrameExclusive - startFrame
}

internal object AiPerformanceNoteSegmenter {
    fun segment(
        track: AiPerformanceTrack,
        config: AiPerformanceNoteSegmentation,
    ): List<AiPerformanceNote> {
        require(config.minimumConfidence in 0f..1f)
        require(config.minimumNoteMs > 0 && config.maximumUnvoicedGapMs >= 0 && config.splitHoldMs >= 0)
        require(config.splitSemitones > 0f)

        val frameMs = track.hopLength * 1000.0 / track.sampleRate
        val minimumFrames = max(1, kotlin.math.ceil(config.minimumNoteMs / frameMs).toInt())
        val maxGapFrames = kotlin.math.ceil(config.maximumUnvoicedGapMs / frameMs).toInt().coerceAtLeast(0)
        val splitHoldFrames = max(1, kotlin.math.ceil(config.splitHoldMs / frameMs).toInt())
        val notes = mutableListOf<AiPerformanceNote>()
        var start = -1
        var lastVoiced = -1
        var gap = 0
        var pendingSplitStart = -1
        var referenceMidi = 0f

        fun close(endExclusive: Int) {
            if (start >= 0 && endExclusive - start >= minimumFrames) {
                buildNote(track, start, endExclusive, config.minimumConfidence)?.let(notes::add)
            }
            start = -1
            lastVoiced = -1
            gap = 0
            pendingSplitStart = -1
            referenceMidi = 0f
        }

        for (frame in 0 until track.frameCount) {
            val hz = track.f0Hz[frame]
            val voiced = hz > 0f && track.voicingConfidence[frame] >= config.minimumConfidence
            if (!voiced) {
                if (start >= 0) {
                    gap++
                    if (gap > maxGapFrames) close(lastVoiced + 1)
                }
                continue
            }

            val midi = hzToMidi(hz)
            if (start < 0) {
                start = frame
                lastVoiced = frame
                referenceMidi = midi
                gap = 0
                continue
            }

            gap = 0
            lastVoiced = frame
            val delta = abs(midi - referenceMidi)
            if (delta >= config.splitSemitones) {
                if (pendingSplitStart < 0) pendingSplitStart = frame
                if (frame - pendingSplitStart + 1 >= splitHoldFrames) {
                    val splitAt = pendingSplitStart
                    close(splitAt)
                    start = splitAt
                    lastVoiced = frame
                    referenceMidi = midi
                }
            } else {
                pendingSplitStart = -1
                // Slow adaptation follows portamento without turning every small vibrato cycle into a new note.
                referenceMidi = referenceMidi * 0.92f + midi * 0.08f
                lastVoiced = frame
            }
        }
        if (start >= 0) close(lastVoiced + 1)
        return notes
    }

    private fun buildNote(
        track: AiPerformanceTrack,
        start: Int,
        endExclusive: Int,
        minimumConfidence: Float,
    ): AiPerformanceNote? {
        val voiced = (start until endExclusive).filter { frame ->
            track.f0Hz[frame] > 0f && track.voicingConfidence[frame] >= minimumConfidence
        }
        if (voiced.isEmpty()) return null
        val midiValues = voiced.map { hzToMidi(track.f0Hz[it]) }.sorted()
        val medianMidi = midiValues[midiValues.size / 2]
        val midiNote = medianMidi.roundToInt().coerceIn(0, 127)
        val referenceHz = midiToHz(midiNote.toFloat())
        var confidence = 0f
        var loudness = 0f
        val pitchCurve = FloatArray(endExclusive - start)
        for (frame in start until endExclusive) {
            val output = frame - start
            val hz = track.f0Hz[frame]
            if (hz > 0f) {
                pitchCurve[output] = (1200.0 * ln(hz / referenceHz) / LN_2).toFloat()
            }
            confidence += track.voicingConfidence[frame]
            loudness += track.loudnessRms[frame]
        }
        val count = (endExclusive - start).coerceAtLeast(1)
        val meanRms = loudness / count
        return AiPerformanceNote(
            startFrame = start,
            endFrameExclusive = endExclusive,
            midiNote = midiNote,
            referenceF0Hz = referenceHz,
            meanConfidence = (confidence / count).coerceIn(0f, 1f),
            velocity = rmsToVelocity(meanRms),
            pitchCurveCents = pitchCurve,
        )
    }

    private fun rmsToVelocity(rms: Float): Float {
        if (rms <= 1.0e-6f) return 0f
        // -48 dBFS maps near zero, -6 dBFS maps near full scale. Do not amplify the signal itself.
        val db = (20.0 * kotlin.math.log10(rms.toDouble().coerceAtLeast(1.0e-9))).toFloat()
        return ((db + 48f) / 42f).coerceIn(0f, 1f)
    }

    private fun hzToMidi(hz: Float): Float =
        (69.0 + 12.0 * ln(hz / 440.0) / LN_2).toFloat()

    private fun midiToHz(midi: Float): Float =
        (440.0 * 2.0.pow((midi - 69.0) / 12.0)).toFloat()

    private const val LN_2 = 0.6931471805599453
}
