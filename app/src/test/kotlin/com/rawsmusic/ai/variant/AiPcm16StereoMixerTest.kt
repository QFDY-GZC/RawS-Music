package com.rawsmusic.ai.variant

import com.rawsmusic.ai.instrument.Pcm16WaveReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class AiPcm16StereoMixerTest {
    @Test
    fun globalNormalizationPreservesTimelineAndCeiling() {
        withTempRoot { root ->
            val a = File(root, "a.pcm")
            val b = File(root, "b.pcm")
            writeConstantStereo(a, frames = 100, value = 0.8f)
            writeConstantStereo(b, frames = 50, value = 0.8f)
            val out = File(root, "mix.wav")
            val result = AiPcm16StereoMixer.mix(
                inputs = listOf(AiRawMixInput(a, 0f), AiRawMixInput(b, 0f)),
                outputWav = out,
                sampleRate = 48_000,
                frameCount = 100,
                outputCeilingDb = -1f,
            )
            assertEquals(100L, result.frameCount)
            assertTrue(result.peakBeforeNormalization > 1.5f)
            assertTrue(result.normalizationGainDb < -4f)
            val wave = Pcm16WaveReader.read(out, 48_000, 2)
            assertEquals(100, wave.frameCount)
            val firstPeak = maxOf(abs(wave.frames[0]), abs(wave.frames[1]))
            assertTrue(firstPeak in 0.88f..0.90f)
            val tail = wave.frames[90 * 2]
            assertTrue(tail > 0.4f && tail < firstPeak)
        }
    }

    @Test
    fun quietMixDoesNotApplyMakeupGain() {
        withTempRoot { root ->
            val a = File(root, "a.pcm")
            writeConstantStereo(a, frames = 32, value = 0.1f)
            val result = AiPcm16StereoMixer.mix(
                inputs = listOf(AiRawMixInput(a, -6f)),
                outputWav = File(root, "mix.wav"),
                sampleRate = 44_100,
                frameCount = 32,
                outputCeilingDb = -1f,
            )
            assertEquals(0f, result.normalizationGainDb, 0.0001f)
        }
    }

    private fun writeConstantStereo(file: File, frames: Int, value: Float) {
        val sample = (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort().toInt()
        FileOutputStream(file).use { output ->
            repeat(frames * 2) {
                output.write(sample and 0xff)
                output.write((sample ushr 8) and 0xff)
            }
        }
    }

    private inline fun withTempRoot(block: (File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("rawsmusic-ai-variant-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
