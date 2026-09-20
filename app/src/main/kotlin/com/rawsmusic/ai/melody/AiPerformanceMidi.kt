package com.rawsmusic.ai.melody

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact MIDI view of an [AiPerformanceTrack].
 *
 * The continuous RMVPE contour remains the canonical analysis result.  MIDI is a playback/cache
 * representation: note boundaries become NOTE_ON/OFF and the retained intra-note contour becomes
 * pitch-bend events.  This keeps the realtime sampler event-driven instead of rendering a whole
 * lead WAV before playback can start.
 */
internal data class AiPerformanceMidiSequence(
    val events: List<AiPerformanceMidiEvent>,
    val durationUs: Long,
) {
    init {
        require(durationUs >= 0L)
        require(events.zipWithNext().all { (a, b) -> a.timeUs <= b.timeUs }) {
            "MIDI events must be time ordered"
        }
    }
}

internal data class AiPerformanceMidiEvent(
    val timeUs: Long,
    val type: Type,
    val note: Int = 0,
    val velocity: Int = 0,
    val pitchBendCents: Float = 0f,
) {
    enum class Type { NOTE_OFF, NOTE_ON, PITCH_BEND }

    init {
        require(timeUs >= 0L)
        require(note in 0..127)
        require(velocity in 0..127)
        require(pitchBendCents.isFinite())
    }
}

internal object AiPerformanceMidiBuilder {
    const val VERSION = "performance-midi-v2"
    private const val BEND_CHANGE_THRESHOLD_CENTS = 4f
    private const val MAX_BEND_CENTS = 200f

    fun build(track: AiPerformanceTrack): AiPerformanceMidiSequence {
        val events = ArrayList<AiPerformanceMidiEvent>()
        track.notes().forEach { note ->
            val startUs = track.frameTimeUs(note.startFrame)
            val endUs = track.frameTimeUs(note.endFrameExclusive)
            val velocity = (note.velocity * 126f + 1f).roundToInt().coerceIn(1, 127)
            events += AiPerformanceMidiEvent(
                timeUs = startUs,
                type = AiPerformanceMidiEvent.Type.NOTE_ON,
                note = note.midiNote,
                velocity = velocity,
            )
            var lastBend = Float.NaN
            note.pitchCurveCents.forEachIndexed { index, centsRaw ->
                val cents = centsRaw.coerceIn(-MAX_BEND_CENTS, MAX_BEND_CENTS)
                if (lastBend.isNaN() || abs(cents - lastBend) >= BEND_CHANGE_THRESHOLD_CENTS) {
                    events += AiPerformanceMidiEvent(
                        timeUs = track.frameTimeUs(note.startFrame + index).coerceAtMost(endUs),
                        type = AiPerformanceMidiEvent.Type.PITCH_BEND,
                        note = note.midiNote,
                        pitchBendCents = cents,
                    )
                    lastBend = cents
                }
            }
            events += AiPerformanceMidiEvent(
                timeUs = endUs,
                type = AiPerformanceMidiEvent.Type.NOTE_OFF,
                note = note.midiNote,
            )
        }
        val ordered = events.sortedWith(
            compareBy<AiPerformanceMidiEvent> { it.timeUs }
                .thenBy { eventPriority(it.type) }
                .thenBy { it.note }
        )
        return AiPerformanceMidiSequence(
            events = ordered,
            durationUs = track.durationUs,
        )
    }

    private fun eventPriority(type: AiPerformanceMidiEvent.Type): Int = when (type) {
        AiPerformanceMidiEvent.Type.NOTE_OFF -> 0
        AiPerformanceMidiEvent.Type.NOTE_ON -> 1
        AiPerformanceMidiEvent.Type.PITCH_BEND -> 2
    }
}

internal data class AiPerformanceMidiArtifact(
    val file: File,
    val sequence: AiPerformanceMidiSequence,
    val cacheKey: String,
)

/** Stores an actual Standard MIDI File (SMF type 0) beside the cached RMVPE performance. */
internal class AiPerformanceMidiStore private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ai_melody/midi").apply { mkdirs() }

    fun prepare(
        track: AiPerformanceTrack,
        instrument: String = "piano",
    ): AiPerformanceMidiArtifact {
        val profile = AiInstrumentMidiProfiles.resolve(instrument)
        val key = cacheKey(track, profile)
        val target = File(root, "${profile.id}-$key.mid")
        val sequence = AiPerformanceMidiBuilder.build(track)
        require(sequence.events.any { it.type == AiPerformanceMidiEvent.Type.NOTE_ON }) {
            "performance track contains no renderable MID notes"
        }
        if (!target.isFile || target.length() <= 22L) {
            val bytes = AiStandardMidiFileEncoder.encode(sequence, profile)
            val staging = File(root, ".$key.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(staging).use { it.write(bytes) }
                require(staging.length() == bytes.size.toLong()) { "MID cache write is incomplete" }
                if (target.exists()) target.delete()
                require(staging.renameTo(target)) { "cannot install MID cache" }
            } finally {
                staging.delete()
            }
        }
        return AiPerformanceMidiArtifact(target, sequence, key)
    }

    private fun cacheKey(track: AiPerformanceTrack, profile: AiInstrumentMidiProfile): String {
        val canonical = buildString {
            append(AiPerformanceMidiBuilder.VERSION).append('|')
            append(profile.id).append('|')
            append(track.dependencyFingerprint).append('|')
            append(track.modelId).append('|').append(track.modelVersion).append('|')
            append(track.extractorVersion).append('|')
            append(track.sampleRate).append('|').append(track.hopLength).append('|')
            append(track.frameCount)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile private var instance: AiPerformanceMidiStore? = null
        fun get(context: Context): AiPerformanceMidiStore = instance ?: synchronized(this) {
            instance ?: AiPerformanceMidiStore(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * SMF type-0 writer with 1 ms ticks (1000 PPQ + 1,000,000 us/quarter tempo).
 * Instrument profile chooses the SMF channel/program; melodic profiles use a +/-2 semitone bend range.
 */
internal object AiStandardMidiFileEncoder {
    private const val DIVISION = 1000
    private const val TEMPO_US_PER_QUARTER = 1_000_000
    private const val BEND_RANGE_CENTS = 200f

    fun encode(
        sequence: AiPerformanceMidiSequence,
        profile: AiInstrumentMidiProfile = AiInstrumentMidiProfiles.piano,
    ): ByteArray {
        val track = ByteArrayOutputStream()
        writeVarLen(track, 0)
        track.write(0xFF)
        track.write(0x51)
        track.write(0x03)
        track.write((TEMPO_US_PER_QUARTER ushr 16) and 0xFF)
        track.write((TEMPO_US_PER_QUARTER ushr 8) and 0xFF)
        track.write(TEMPO_US_PER_QUARTER and 0xFF)
        profile.program?.let { program ->
            writeVarLen(track, 0)
            track.write(0xC0 or profile.channel)
            track.write(program)
        }
        if (profile.pitchBendEnabled) {
            // RPN 0,0 -> pitch bend sensitivity = 2 semitones, then deselect RPN.
            val rpn = intArrayOf(101, 0, 100, 0, 6, 2, 38, 0, 101, 127, 100, 127)
            var rpnIndex = 0
            while (rpnIndex < rpn.size) {
                writeVarLen(track, 0)
                track.write(0xB0 or profile.channel)
                track.write(rpn[rpnIndex])
                track.write(rpn[rpnIndex + 1])
                rpnIndex += 2
            }
        }

        var previousTick = 0L
        sequence.events
            .asSequence()
            .filter { profile.pitchBendEnabled || it.type != AiPerformanceMidiEvent.Type.PITCH_BEND }
            .forEach { event ->
            val tick = (event.timeUs / 1000L).coerceAtLeast(previousTick)
            writeVarLen(track, tick - previousTick)
            previousTick = tick
            when (event.type) {
                AiPerformanceMidiEvent.Type.NOTE_ON -> {
                    track.write(0x90 or profile.channel)
                    track.write(event.note)
                    track.write(event.velocity.coerceIn(1, 127))
                }
                AiPerformanceMidiEvent.Type.NOTE_OFF -> {
                    track.write(0x80 or profile.channel)
                    track.write(event.note)
                    track.write(0)
                }
                AiPerformanceMidiEvent.Type.PITCH_BEND -> {
                    val bend = (8192f + event.pitchBendCents.coerceIn(-BEND_RANGE_CENTS, BEND_RANGE_CENTS) /
                        BEND_RANGE_CENTS * 8191f).roundToInt().coerceIn(0, 16383)
                    track.write(0xE0 or profile.channel)
                    track.write(bend and 0x7F)
                    track.write((bend ushr 7) and 0x7F)
                }
            }
        }
        writeVarLen(track, 0)
        track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        val trackBytes = track.toByteArray()

        return ByteArrayOutputStream(14 + 8 + trackBytes.size).apply {
            write("MThd".toByteArray(Charsets.US_ASCII))
            writeBeInt(this, 6)
            writeBeShort(this, 0)
            writeBeShort(this, 1)
            writeBeShort(this, DIVISION)
            write("MTrk".toByteArray(Charsets.US_ASCII))
            writeBeInt(this, trackBytes.size)
            write(trackBytes)
        }.toByteArray()
    }

    private fun writeVarLen(output: ByteArrayOutputStream, value: Long) {
        var v = value.coerceAtLeast(0L).coerceAtMost(0x0FFFFFFFL)
        var buffer = (v and 0x7F).toInt()
        while (v ushr 7 != 0L) {
            v = v ushr 7
            buffer = (buffer shl 8) or ((v and 0x7F).toInt() or 0x80)
        }
        while (true) {
            output.write(buffer and 0xFF)
            if (buffer and 0x80 != 0) buffer = buffer ushr 8 else break
        }
    }

    private fun writeBeShort(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun writeBeInt(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }
}
