package com.rawsmusic.ai.variant

import com.rawsmusic.ai.instrument.AiInstrumentLeadArtifact
import com.rawsmusic.separation.AiStemDependency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class AiMediaVariantPolicyTest {
    @Test
    fun resolverRejectsStaleStemDependencyAndChoosesNewestMatchingPack() {
        val config = AiMediaVariantMixConfig.defaultFor(AiMediaVariantMode.INSTRUMENT_PERFORMANCE)
        val old = artifact("old", "dep-current", "piano", "1", 10, config)
        val newest = artifact("new", "dep-current", "piano", "1", 20, config)
        val stale = artifact("stale", "dep-old", "piano", "1", 30, config)
        val selected = AiMediaVariantResolutionPolicy.select(
            candidates = listOf(old, newest, stale),
            currentStemDependencyFingerprint = "dep-current",
            selection = AiMediaVariantSelection(AiMediaVariantMode.INSTRUMENT_PERFORMANCE, "piano", "1"),
        )
        assertEquals("new", selected?.fingerprint)
        assertNull(
            AiMediaVariantResolutionPolicy.select(
                candidates = listOf(stale),
                currentStemDependencyFingerprint = "dep-current",
                selection = AiMediaVariantSelection(AiMediaVariantMode.INSTRUMENT_PERFORMANCE),
            )
        )
    }

    @Test
    fun fingerprintChangesWhenLeadFileOrMixChanges() {
        val root = kotlin.io.path.createTempDirectory("rawsmusic-ai-variant-fingerprint").toFile()
        try {
            val vocals = File(root, "vocals.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val instrumental = File(root, "instrumental.flac").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val leadFile = File(root, "lead.wav").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val dependency = AiStemDependency(
                dependencyFingerprint = "dep",
                separationResultId = "sep",
                sourceFingerprint = "source",
                sourceName = "song",
                modelId = "stem",
                modelVersion = "1",
                sampleRate = 44_100,
                totalFrames = 1_000,
                createdAtEpochMs = 1,
                outputFormat = "flac",
                vocalsFile = vocals,
                instrumentalFile = instrumental,
            )
            val lead = AiInstrumentLeadArtifact(
                fingerprint = "lead",
                performanceDependencyFingerprint = "dep",
                performanceContentFingerprint = "perf",
                melodyModelId = "melody",
                melodyModelVersion = "1",
                extractorVersion = "1",
                packId = "piano",
                packVersion = "1",
                packContentFingerprint = "pack",
                rendererId = "sampler",
                rendererVersion = "1",
                audioFormat = "wav_pcm_s16le",
                sampleRate = 48_000,
                channels = 2,
                frameCount = 1_000,
                renderedNotes = 3,
                audioFile = leadFile,
            )
            val baseConfig = AiMediaVariantMixConfig.defaultFor(AiMediaVariantMode.INSTRUMENT_PERFORMANCE)
            val first = AiMediaVariantFingerprint.build(
                dependency, lead, AiMediaVariantMode.INSTRUMENT_PERFORMANCE, baseConfig, "flac"
            )
            val changedMix = AiMediaVariantFingerprint.build(
                dependency, lead, AiMediaVariantMode.INSTRUMENT_PERFORMANCE,
                baseConfig.copy(leadGainDb = baseConfig.leadGainDb - 1f), "flac"
            )
            assertNotEquals(first, changedMix)
            Thread.sleep(2)
            leadFile.appendBytes(byteArrayOf(10))
            val changedFile = AiMediaVariantFingerprint.build(
                dependency, lead, AiMediaVariantMode.INSTRUMENT_PERFORMANCE, baseConfig, "flac"
            )
            assertNotEquals(first, changedFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun artifact(
        fingerprint: String,
        dependency: String,
        packId: String,
        packVersion: String,
        created: Long,
        config: AiMediaVariantMixConfig,
    ) = AiMediaVariantArtifact(
        fingerprint = fingerprint,
        sourceFingerprint = "source",
        stemDependencyFingerprint = dependency,
        leadFingerprint = "lead-$fingerprint",
        mode = AiMediaVariantMode.INSTRUMENT_PERFORMANCE,
        packId = packId,
        packVersion = packVersion,
        rendererId = "renderer",
        rendererVersion = "1",
        mixConfig = config,
        mixerVersion = AiPcm16StereoMixer.MIXER_VERSION,
        audioFormat = "flac",
        sampleRate = 44_100,
        channels = 2,
        frameCount = 100,
        peakBeforeNormalization = 0.5f,
        normalizationGainDb = 0f,
        createdAtEpochMs = created,
        audioFile = File("/tmp/$fingerprint.flac"),
    )
}
