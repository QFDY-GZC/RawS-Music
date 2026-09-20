package com.rawsmusic.ai.melody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AiPerformanceMidiTest {
    @Test
    fun midiSequenceKeepsNoteBoundariesAndPitchBend() {
        val f0 = FloatArray(100) { frame ->
            val cents = if (frame < 50) 0.0 else 35.0
            (440.0 * 2.0.pow(cents / 1200.0)).toFloat()
        }
        val sequence = AiPerformanceMidiBuilder.build(track(f0))
        assertTrue(sequence.events.any {
            it.type == AiPerformanceMidiEvent.Type.NOTE_ON && it.note == 69
        })
        assertTrue(sequence.events.any {
            it.type == AiPerformanceMidiEvent.Type.PITCH_BEND && it.pitchBendCents > 20f
        })
        assertTrue(sequence.events.any { it.type == AiPerformanceMidiEvent.Type.NOTE_OFF })
    }

    @Test
    fun standardMidiFileUsesTypeZeroAndOneMillisecondTickContract() {
        val sequence = AiPerformanceMidiBuilder.build(track(FloatArray(60) { 440f }))
        val bytes = AiStandardMidiFileEncoder.encode(sequence)
        assertEquals("MThd", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(0, ((bytes[8].toInt() and 0xff) shl 8) or (bytes[9].toInt() and 0xff))
        assertEquals(1, ((bytes[10].toInt() and 0xff) shl 8) or (bytes[11].toInt() and 0xff))
        assertEquals(1000, ((bytes[12].toInt() and 0xff) shl 8) or (bytes[13].toInt() and 0xff))
        assertEquals("MTrk", bytes.copyOfRange(14, 18).toString(Charsets.US_ASCII))
    }

    private fun track(f0: FloatArray): AiPerformanceTrack = AiPerformanceTrack(
        dependencyFingerprint = "dep-midi",
        modelId = "rmvpe.q8",
        modelVersion = "1.0.0",
        extractorVersion = "test",
        sampleRate = 16_000,
        hopLength = 160,
        f0Hz = f0,
        voicingConfidence = FloatArray(f0.size) { 0.9f },
        loudnessRms = FloatArray(f0.size) { 0.12f },
    )
}
