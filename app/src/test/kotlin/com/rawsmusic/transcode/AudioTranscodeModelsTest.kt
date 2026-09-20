package com.rawsmusic.transcode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTranscodeModelsTest {
    @Test
    fun expandedOutputFormats_keepStableCapabilityIdsAndContainerExtensions() {
        assertTrue(AudioTranscodeFormat.OGG_FLAC.capabilityId == "ogg_flac")
        assertTrue(AudioTranscodeFormat.OGG_FLAC.extension == "oga")
        assertTrue(AudioTranscodeFormat.TTA.capabilityId == "tta")
        assertTrue(AudioTranscodeFormat.TTA.extension == "tta")
        assertTrue(AudioTranscodeFormat.MP2.isLossy)
        assertTrue(AudioTranscodeFormat.MP2.extension == "mp2")
        assertTrue(AudioTranscodeFormat.WMA.isLossy)
        assertTrue(AudioTranscodeFormat.WMA.acceptedExtensions == setOf("wma", "asf"))
        // MP3 intentionally remains in the model so a future libmp3lame build can expose it
        // without a persisted-request schema change. Runtime capabilities still decide visibility.
        assertTrue(AudioTranscodeFormat.MP3.capabilityId == "mp3")
    }

    @Test
    fun capability_supportsOnlyRuntimeOpenedProfiles_notCartesianProduct() {
        val capability = AudioTranscodeCapability(
            id = "example",
            container = "example",
            lossless = true,
            profiles = setOf(
                AudioTranscodeProfile(44_100, 16, 1),
                AudioTranscodeProfile(96_000, 24, 2),
            ),
            compressionRange = null,
        )

        assertTrue(capability.supports(44_100, 16))
        assertTrue(capability.supports(96_000, 24))
        assertFalse(capability.supports(44_100, 24))
        assertFalse(capability.supports(96_000, 16))
    }

    @Test
    fun lossyCapability_supportsOnlyRuntimeOpenedRateBitratePairs() {
        val capability = AudioTranscodeCapability(
            id = "opus",
            container = "opus",
            lossless = false,
            profiles = setOf(
                AudioTranscodeProfile(48_000, 0, 86076, 128),
                AudioTranscodeProfile(48_000, 0, 86076, 160),
                AudioTranscodeProfile(96_000, 0, 86076, 128),
            ),
            compressionRange = null,
            defaultBitRateKbps = 160,
        )

        assertTrue(capability.supportsLossy(48_000, 160))
        assertTrue(capability.supportsLossy(96_000, 128))
        assertFalse(capability.supportsLossy(96_000, 160))
        assertTrue(capability.recommendedBitRateKbps(48_000) == 160)
        assertTrue(capability.recommendedBitRateKbps(96_000) == 128)
    }

    @Test
    fun dsdCapability_exposesOnlyDeclaredMultipliers() {
        val capability = AudioTranscodeCapability(
            id = "dsf",
            container = "dsf",
            lossless = true,
            profiles = emptySet(),
            compressionRange = null,
            dsdRates = setOf(
                AudioTranscodeDsdRate.DSD64,
                AudioTranscodeDsdRate.DSD256,
                AudioTranscodeDsdRate.DSD1024,
            ),
        )

        assertTrue(capability.supportsDsd(AudioTranscodeDsdRate.DSD64))
        assertTrue(capability.supportsDsd(AudioTranscodeDsdRate.DSD1024))
        assertFalse(capability.supportsDsd(AudioTranscodeDsdRate.DSD128))
    }

    @Test
    fun dsdProbe_keepsLogicalDsdRateSeparateFromDecoderByteClock() {
        val source = AudioTranscodeProbe(
            sampleRateHz = 352_800,
            bitDepth = 1,
            channels = 2,
            durationMs = 10_000,
            bitRate = 5_644_800,
            codecId = 0,
            isDsdSource = true,
            dsdSampleRateHz = 2_822_400,
            bitDepthMeaningful = true,
            hasVideoStream = false,
        )

        assertTrue(source.isDsdSource)
        assertTrue(source.sampleRateHz * 8 == source.dsdSampleRateHz)
        assertTrue(source.bitDepthMeaningful)
        assertFalse(source.hasVideoStream)
    }

    @Test
    fun preview_canStartOnlyWithoutBlockingIssues() {
        val source = AudioTranscodeProbe(96_000, 24, 2, 10_000, 0, 0)
        val capability = AudioTranscodeCapability(
            id = "flac",
            container = "flac",
            lossless = true,
            profiles = setOf(AudioTranscodeProfile(96_000, 24, 1)),
            compressionRange = 0..12,
        )
        val warningOnly = AudioTranscodePreview(
            source = source,
            capability = capability,
            resolvedSampleRateHz = 96_000,
            resolvedBitDepth = 24,
            autoDitherWillApply = false,
            estimatedOutputBytesMin = 1,
            estimatedOutputBytesMax = 2,
            issues = listOf(
                AudioTranscodePreviewIssue(
                    AudioTranscodePreviewIssueCode.UPSAMPLING,
                    AudioTranscodePreviewIssueSeverity.WARNING,
                    "warning",
                ),
            ),
        )
        assertTrue(warningOnly.canStart)

        val blocked = warningOnly.copy(
            issues = warningOnly.issues + AudioTranscodePreviewIssue(
                AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                AudioTranscodePreviewIssueSeverity.BLOCKING,
                "blocked",
            ),
        )
        assertFalse(blocked.canStart)
    }

    @Test
    fun verification_failsOnlyWhenRequiredPcmHashWasComparedAndMismatched() {
        val fast = AudioTranscodeVerification(
            sampleRateMatches = true,
            bitDepthMatches = true,
            channelsMatch = true,
            durationMatches = true,
            codecMatches = true,
        )
        assertTrue(fast.passed)

        val matching = fast.copy(
            pcmHashMatches = true,
            sourcePcmSha256 = "a".repeat(64),
            outputPcmSha256 = "a".repeat(64),
        )
        assertTrue(matching.passed)

        val mismatching = matching.copy(
            pcmHashMatches = false,
            outputPcmSha256 = "b".repeat(64),
        )
        assertFalse(mismatching.passed)
    }

    @Test
    fun batchPreview_summarizesReadyBlockedAndBackendCounts() {
        val source = AudioTranscodeProbe(48_000, 24, 2, 10_000, 0, 0)
        val capability = AudioTranscodeCapability(
            id = "aac",
            container = "m4a",
            lossless = false,
            profiles = setOf(AudioTranscodeProfile(48_000, 0, 1, 256)),
            compressionRange = null,
        )
        val request = AudioTranscodeRequest("in.flac", "out.m4a", AudioTranscodeFormat.AAC)
        val ready = AudioTranscodePreview(
            source = source,
            capability = capability,
            resolvedSampleRateHz = 48_000,
            resolvedBitDepth = null,
            resolvedBitRateKbps = 256,
            backend = AudioTranscodeBackendInfo(
                AudioTranscodeBackendKind.HARDWARE,
                "codec.hardware.aac",
                hardwareRequested = true,
            ),
            autoDitherWillApply = false,
            estimatedOutputBytesMin = 100,
            estimatedOutputBytesMax = 200,
            issues = emptyList(),
        )
        val blocked = ready.copy(
            backend = AudioTranscodeBackendInfo(
                AudioTranscodeBackendKind.SOFTWARE,
                "FFmpeg AAC",
                hardwareRequested = true,
                fallbackReason = "unsupported",
            ),
            issues = listOf(
                AudioTranscodePreviewIssue(
                    AudioTranscodePreviewIssueCode.TARGET_PROFILE_UNAVAILABLE,
                    AudioTranscodePreviewIssueSeverity.BLOCKING,
                    "blocked",
                )
            ),
        )
        val batch = AudioTranscodeBatchPreview(
            listOf(
                AudioTranscodeBatchPreviewItem(request, ready),
                AudioTranscodeBatchPreviewItem(request.copy(outputPath = "out2.m4a"), blocked),
            )
        )
        assertTrue(batch.totalCount == 2)
        assertTrue(batch.readyCount == 1)
        assertTrue(batch.blockedCount == 1)
        assertTrue(batch.hardwareCount == 1)
        assertTrue(batch.softwareCount == 1)
        assertTrue(batch.estimatedOutputBytesMin == 200L)
        assertTrue(batch.estimatedOutputBytesMax == 400L)
    }
}
