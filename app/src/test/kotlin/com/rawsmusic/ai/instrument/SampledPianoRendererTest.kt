package com.rawsmusic.ai.instrument

import com.rawsmusic.ai.melody.AiPerformanceTrack
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SampledPianoRendererTest {
    @Test
    fun sampleSelectionPrefersVelocityThenPitch() {
        val renderer = SampledPianoRenderer()
        val lowC = sample("c4_low.wav", 60, 1, 80)
        val highC = sample("c4_high.wav", 60, 81, 127)
        val lowG = sample("g4_low.wav", 67, 1, 80)
        check(renderer.selectSample(listOf(lowC, highC, lowG), 62, 100) === highC)
        check(renderer.selectSample(listOf(lowC, highC, lowG), 66, 50) === lowG)
    }

    @Test
    fun rendersTransposedPitchAndWritesValidWave() {
        withTempRoot { root ->
            val packDir = File(root, "pack").apply { mkdirs() }
            val sampleFile = File(packDir, "c4.wav")
            writeSineWave(sampleFile, 48_000, 2, 261.625565, 2.0)
            File(packDir, "license.txt").writeText("test only")
            val manifest = AiInstrumentPackManifest(
                schemaVersion = 1,
                id = "test.piano",
                version = "1",
                displayName = "Test Piano",
                instrument = "piano",
                sampleRate = 48_000,
                channels = 2,
                licenseFile = "license.txt",
                samples = listOf(sample("c4.wav", 60, 1, 127)),
            )
            val track = AiPerformanceTrack(
                dependencyFingerprint = "dep",
                modelId = "model",
                modelVersion = "1",
                extractorVersion = "1",
                sampleRate = 16_000,
                hopLength = 160,
                f0Hz = FloatArray(100) { 440f },
                voicingConfidence = FloatArray(100) { 1f },
                loudnessRms = FloatArray(100) { 0.25f },
            )
            val output = File(root, "lead.wav")
            val result = SampledPianoRenderer().render(
                track,
                AiInstalledInstrumentPack(manifest, packDir),
                output,
                AiInstrumentRenderConfig(masterGainDb = -12f, releaseMs = 80f, maximumTailMs = 80),
            )
            assertTrue(result.renderedNotes >= 1)
            assertTrue(output.isFile && output.length() == 44L + result.frameCount * 2 * 2)
            val rendered = Pcm16WaveReader.read(output, 48_000, 2)
            assertTrue(rendered.frames.any { abs(it) > 1e-4f })
            val measured = estimateFrequency(rendered, fromFrame = 4_800, toFrame = 28_800)
            assertTrue("rendered pitch mismatch: $measured", abs(measured - 440.0) < 3.0)
        }
    }

    @Test
    fun sustainingVoiceUsesDeclaredSampleLoop() {
        withTempRoot { root ->
            val packDir = File(root, "loop-pack").apply { mkdirs() }
            val sampleFile = File(packDir, "loop.wav")
            val sourceFrames = 2400L
            Pcm16WaveWriter(sampleFile, 48_000, 2, sourceFrames).use { writer ->
                val block = FloatArray(512 * 2)
                var cursor = 0L
                while (cursor < sourceFrames) {
                    val count = minOf(512L, sourceFrames - cursor).toInt()
                    for (frame in 0 until count) {
                        val value = if (((cursor + frame) / 40L) % 2L == 0L) 0.35f else -0.35f
                        block[frame * 2] = value
                        block[frame * 2 + 1] = value
                    }
                    writer.writeInterleaved(block, count)
                    cursor += count
                }
            }
            File(packDir, "license.txt").writeText("test")
            val loopSample = AiInstrumentSampleManifest(
                path = "loop.wav", rootMidiNote = 69, velocityMin = 1, velocityMax = 127, gainDb = 0f,
                loopStartFrame = 400L, loopEndFrameExclusive = 1600L, sha256 = "0".repeat(64),
            )
            val manifest = AiInstrumentPackManifest(
                schemaVersion = 2, id = "test.loop", version = "1", displayName = "Loop",
                instrument = "piano", sampleRate = 48_000, channels = 2, licenseFile = "license.txt",
                samples = listOf(loopSample),
            )
            val track = AiPerformanceTrack(
                dependencyFingerprint = "dep", modelId = "model", modelVersion = "1", extractorVersion = "1",
                sampleRate = 16_000, hopLength = 160,
                f0Hz = FloatArray(80) { 440f },
                voicingConfidence = FloatArray(80) { 1f },
                loudnessRms = FloatArray(80) { 0.3f },
            )
            val output = File(root, "looped.wav")
            SampledPianoRenderer().render(
                track, AiInstalledInstrumentPack(manifest, packDir), output,
                AiInstrumentRenderConfig(masterGainDb = -12f, releaseMs = 30f, maximumTailMs = 30),
            )
            val rendered = Pcm16WaveReader.read(output, 48_000, 2)
            val lateStart = 24_000
            val lateEnd = minOf(rendered.frameCount, 32_000)
            assertTrue(lateEnd > lateStart)
            assertTrue(rendered.frames.sliceArray(lateStart * 2 until lateEnd * 2).any { abs(it) > 1e-3f })
        }
    }

    @Test
    fun packFingerprintChangesWithSampleIdentity() {
        val base = AiInstrumentPackManifest(
            schemaVersion = 1,
            id = "test.piano",
            version = "1",
            displayName = "Test",
            instrument = "piano",
            sampleRate = 48_000,
            channels = 2,
            licenseFile = "license.txt",
            samples = listOf(sample("c4.wav", 60, 1, 127)),
        )
        val changed = base.copy(samples = listOf(base.samples.single().copy(sha256 = "1".repeat(64))))
        val looped = base.copy(
            schemaVersion = 2,
            samples = listOf(base.samples.single().copy(loopStartFrame = 100L, loopEndFrameExclusive = 500L)),
        )
        assertNotEquals(base.contentFingerprint(), changed.contentFingerprint())
        assertNotEquals(base.contentFingerprint(), looped.contentFingerprint())
    }

    @Test
    fun rejectsUnsafePackPaths() {
        assertTrue(!AiInstrumentPackManifest.isSafeRelativePath("../sample.wav"))
        assertTrue(!AiInstrumentPackManifest.isSafeRelativePath("/sample.wav"))
        assertTrue(!AiInstrumentPackManifest.isSafeRelativePath("C:\\sample.wav"))
        assertTrue(AiInstrumentPackManifest.isSafeRelativePath("samples/C4.wav"))
    }

    private fun estimateFrequency(wave: Pcm16WaveData, fromFrame: Int, toFrame: Int): Double {
        var crossings = 0
        var previous = wave.frames[fromFrame * wave.channels]
        for (frame in fromFrame + 1 until minOf(toFrame, wave.frameCount)) {
            val current = wave.frames[frame * wave.channels]
            if (previous <= 0f && current > 0f) crossings++
            previous = current
        }
        val seconds = (minOf(toFrame, wave.frameCount) - fromFrame).toDouble() / wave.sampleRate
        return crossings / seconds
    }

    private fun sample(path: String, root: Int, min: Int, max: Int) = AiInstrumentSampleManifest(
        path = path,
        rootMidiNote = root,
        velocityMin = min,
        velocityMax = max,
        gainDb = 0f,
        sha256 = "0".repeat(64),
    )

    private fun writeSineWave(file: File, sampleRate: Int, channels: Int, hz: Double, seconds: Double) {
        val frames = (sampleRate * seconds).toLong()
        Pcm16WaveWriter(file, sampleRate, channels, frames).use { writer ->
            val block = FloatArray(1024 * channels)
            var cursor = 0L
            while (cursor < frames) {
                val count = minOf(1024L, frames - cursor).toInt()
                for (frame in 0 until count) {
                    val value = (sin(2.0 * PI * hz * (cursor + frame) / sampleRate) * 0.4).toFloat()
                    repeat(channels) { ch -> block[frame * channels + ch] = value }
                }
                writer.writeInterleaved(block, count)
                cursor += count
            }
        }
    }

    private inline fun withTempRoot(block: (File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("rawsmusic-ai-instrument-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
