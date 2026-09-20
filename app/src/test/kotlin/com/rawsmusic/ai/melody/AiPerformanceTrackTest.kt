package com.rawsmusic.ai.melody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.pow

class AiPerformanceTrackTest {
    @Test
    fun continuousVibratoRemainsOneNote() {
        val frames = 120
        val f0 = FloatArray(frames) { index ->
            val cents = 35.0 * sin(index * 2.0 * Math.PI / 18.0)
            (440.0 * 2.0.pow(cents / 1200.0)).toFloat()
        }
        val track = track(f0)
        val notes = track.notes()
        assertEquals(1, notes.size)
        assertEquals(69, notes.single().midiNote)
        assertTrue(notes.single().pitchCurveCents.any { kotlin.math.abs(it) > 20f })
    }

    @Test
    fun sustainedSemitoneChangeCreatesSecondNote() {
        val f0 = FloatArray(100) { if (it < 50) 440f else 493.8833f }
        val notes = track(f0).notes()
        assertEquals(2, notes.size)
        assertEquals(69, notes[0].midiNote)
        assertEquals(71, notes[1].midiNote)
    }

    @Test
    fun shortUnvoicedGapDoesNotSplitPhrase() {
        val f0 = FloatArray(100) { 440f }.apply {
            this[40] = 0f
            this[41] = 0f
        }
        val confidence = FloatArray(100) { 0.9f }.apply {
            this[40] = 0f
            this[41] = 0f
        }
        val notes = track(f0, confidence).notes()
        assertEquals(1, notes.size)
    }

    private fun track(
        f0: FloatArray,
        confidence: FloatArray = FloatArray(f0.size) { 0.9f },
    ): AiPerformanceTrack = AiPerformanceTrack(
        dependencyFingerprint = "dep",
        modelId = "rmvpe.q8",
        modelVersion = "1.0.0",
        extractorVersion = "test",
        sampleRate = 16_000,
        hopLength = 160,
        f0Hz = f0,
        voicingConfidence = confidence,
        loudnessRms = FloatArray(f0.size) { 0.1f },
    )
}
