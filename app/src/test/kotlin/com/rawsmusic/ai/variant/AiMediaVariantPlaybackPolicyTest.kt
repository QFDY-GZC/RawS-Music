package com.rawsmusic.ai.variant

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiMediaVariantPlaybackPolicyTest {
    @Test
    fun onlyWholeSongTimelineIsEligibleForStep5Switching() {
        assertTrue(AiMediaVariantPlaybackPolicy.supportsSong(AudioFile(path = "/music/a.flac")))
        assertFalse(
            AiMediaVariantPlaybackPolicy.supportsSong(
                AudioFile(path = "/music/a.flac", cueOffsetMs = 10_000L, cueTrackIndex = 2)
            )
        )
    }

    @Test
    fun sourceTagUsesModeAndBoundedFingerprint() {
        val artifact = AiMediaVariantArtifact(
            fingerprint = "1234567890abcdef",
            sourceFingerprint = "source",
            stemDependencyFingerprint = "stem",
            leadFingerprint = "lead",
            mode = AiMediaVariantMode.VOCAL_ENSEMBLE,
            packId = "piano",
            packVersion = "1",
            rendererId = "sampler",
            rendererVersion = "1",
            mixConfig = AiMediaVariantMixConfig.defaultFor(AiMediaVariantMode.VOCAL_ENSEMBLE),
            mixerVersion = "1",
            audioFormat = "flac",
            sampleRate = 44_100,
            channels = 2,
            frameCount = 44_100L,
            peakBeforeNormalization = 0.8f,
            normalizationGainDb = 0f,
            createdAtEpochMs = 1L,
            audioFile = File("variant.flac"),
        )
        val tag = AiMediaVariantPlaybackPolicy.sourceTag(artifact)
        assertTrue(tag.startsWith("ai_variant:vocal_ensemble:"))
        assertTrue(tag.endsWith("1234567890ab"))
    }
}
