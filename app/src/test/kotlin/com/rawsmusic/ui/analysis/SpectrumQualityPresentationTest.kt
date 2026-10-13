package com.rawsmusic.ui.analysis

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumQualityPresentationTest {
    private fun report(assessment: SpectrumAssessment) = SpectrumQualityReport(
        assessment = assessment, peakDb = -3f, rmsDb = -18f, crestDb = 15f,
        balanceDb = 0f, nearFullScale = false, lowCrest = false,
        channelImbalance = false, inspectedFrames = 80, activeFrames = 60,
        bandEdge = null, coarseFrequencyGrid = false, resampled = false,
        dsd = false, completeData = true
    )

    @Test fun missingAnalysisNeverReceivesPositiveGrade() {
        assertEquals(SpectrumQualityDisplay.Unavailable,
            spectrumQualityDisplay(AudioFile(format = "FLAC"), report(SpectrumAssessment.Insufficient)))
    }

    @Test fun knownLossyCodecReceivesFormatSummaryOnly() {
        assertEquals(SpectrumQualityDisplay.Lossy,
            spectrumQualityDisplay(AudioFile(format = "M4A", encodingFormat = "AAC", bitRate = 320000),
                report(SpectrumAssessment.NoFlags)))
    }

    @Test fun losslessContainerWithoutWarningIsNotCalledPerfect() {
        assertEquals(SpectrumQualityDisplay.NoFlags,
            spectrumQualityDisplay(AudioFile(format = "FLAC", encodingFormat = "FLAC"),
                report(SpectrumAssessment.NoFlags)))
    }

    @Test fun repeatedBandEdgeWinsOverContainerLabel() {
        assertEquals(SpectrumQualityDisplay.Review,
            spectrumQualityDisplay(AudioFile(format = "FLAC"), report(SpectrumAssessment.BandLimited)))
    }

    @Test fun unknownEncodingStaysUnrated() {
        assertEquals(SpectrumQualityDisplay.Unavailable,
            spectrumQualityDisplay(AudioFile(format = "M4A"), report(SpectrumAssessment.NoFlags)))
    }

    @Test fun limitedAnalysisStaysUnratedEvenForLosslessContainer() {
        assertEquals(SpectrumQualityDisplay.Unavailable,
            spectrumQualityDisplay(AudioFile(format = "FLAC"), report(SpectrumAssessment.LimitedScope)))
    }

    @Test fun severeSampleEvidenceOverridesLossyFormatEmoji() {
        assertEquals(SpectrumQualityDisplay.ClippingRisk,
            spectrumQualityDisplay(AudioFile(format = "MP3"), report(SpectrumAssessment.SustainedRail)))
    }

    @Test fun repeatableLowRateBoundaryInHighRateLosslessGetsSpecificReview() {
        val edge = SpectrumBandEdge(22050f, 24f, 60, 70, SpectrumEvidence.Moderate, true)
        assertEquals(SpectrumQualityDisplay.HighSpecReview,
            spectrumQualityDisplay(AudioFile(format = "FLAC", sampleRate = 96000, channelCount = 2),
                report(SpectrumAssessment.BandLimited).copy(bandEdge = edge)))
    }

    @Test fun highRateMetadataAloneNeverImpliesSuspiciousUpsampling() {
        assertEquals(SpectrumQualityDisplay.NoFlags,
            spectrumQualityDisplay(AudioFile(format = "FLAC", sampleRate = 96000, channelCount = 2),
                report(SpectrumAssessment.NoFlags)))
    }

    @Test fun weakOrNonHighRateBoundaryRemainsOrdinaryReview() {
        val supported = SpectrumBandEdge(22050f, 24f, 60, 70, SpectrumEvidence.Moderate, true)
        val weak = supported.copy(evidence = SpectrumEvidence.Weak)
        assertEquals(SpectrumQualityDisplay.Review,
            spectrumQualityDisplay(AudioFile(format = "FLAC", sampleRate = 96000, channelCount = 2),
                report(SpectrumAssessment.BandLimited).copy(bandEdge = weak)))
        assertEquals(SpectrumQualityDisplay.Review,
            spectrumQualityDisplay(AudioFile(format = "FLAC", sampleRate = 48000, channelCount = 2),
                report(SpectrumAssessment.BandLimited).copy(bandEdge = supported)))
    }

    @Test fun knownLossyHighRateFileDoesNotGetHighSpecLosslessReview() {
        val edge = SpectrumBandEdge(22050f, 24f, 60, 70, SpectrumEvidence.Moderate, true)
        assertEquals(SpectrumQualityDisplay.Review,
            spectrumQualityDisplay(AudioFile(format = "M4A", encodingFormat = "AAC", sampleRate = 96000, channelCount = 2),
                report(SpectrumAssessment.BandLimited).copy(bandEdge = edge)))
    }

    @Test fun limitedOrSevereResultKeepsItsHigherPriority() {
        val edge = SpectrumBandEdge(22050f, 24f, 60, 70, SpectrumEvidence.Moderate, true)
        val song = AudioFile(format = "FLAC", sampleRate = 96000, channelCount = 2)
        assertEquals(SpectrumQualityDisplay.Unavailable,
            spectrumQualityDisplay(song, report(SpectrumAssessment.LimitedScope).copy(bandEdge = edge)))
        assertEquals(SpectrumQualityDisplay.ClippingRisk,
            spectrumQualityDisplay(song, report(SpectrumAssessment.SustainedRail).copy(bandEdge = edge)))
    }

    @Test fun downmixedHighRateFileDoesNotGetSourceHint() {
        val edge = SpectrumBandEdge(22050f, 24f, 60, 70, SpectrumEvidence.Moderate, true)
        assertEquals(SpectrumQualityDisplay.Review,
            spectrumQualityDisplay(AudioFile(format = "FLAC", sampleRate = 96000, channelCount = 6),
                report(SpectrumAssessment.BandLimited).copy(bandEdge = edge)))
    }

    @Test fun verifiedReferenceOnlyReceivesSpecialEmojiWhenNoMeasuredWarning() {
        val song = AudioFile(format = "FLAC", sampleRate = 96000, channelCount = 2)
        assertEquals(SpectrumQualityDisplay.ReferenceMatch,
            spectrumQualityDisplay(song, report(SpectrumAssessment.NoFlags), SpectrumReferenceResult.Match))
        assertEquals(SpectrumQualityDisplay.Review,
            spectrumQualityDisplay(song, report(SpectrumAssessment.Review), SpectrumReferenceResult.Match))
        assertEquals(SpectrumQualityDisplay.ClippingRisk,
            spectrumQualityDisplay(song, report(SpectrumAssessment.SustainedRail), SpectrumReferenceResult.Match))
    }

    @Test fun missingOrFailedReferenceDoesNotReceiveSpecialEmoji() {
        val song = AudioFile(format = "FLAC", sampleRate = 96000, channelCount = 2)
        assertEquals(SpectrumQualityDisplay.NoFlags,
            spectrumQualityDisplay(song, report(SpectrumAssessment.NoFlags), SpectrumReferenceResult.Different))
        assertEquals(SpectrumQualityDisplay.NoFlags,
            spectrumQualityDisplay(song, report(SpectrumAssessment.NoFlags), SpectrumReferenceResult.Unsupported))
    }

    @Test fun precisionGridWithoutBandEdgeStillRequestsReview() {
        val song = AudioFile(format = "FLAC", sampleRate = 96000, bitsPerSample = 24, channelCount = 2)
        assertEquals(SpectrumQualityDisplay.PrecisionReview,
            spectrumQualityDisplay(song, report(SpectrumAssessment.PrecisionLimited)))
    }

    @Test fun decodedPrecisionDoesNotGradeLossySourceAsFakeMaster() {
        val song = AudioFile(format = "M4A", encodingFormat = "AAC", sampleRate = 96000,
            bitsPerSample = 24, channelCount = 2)
        assertEquals(SpectrumQualityDisplay.Review,
            spectrumQualityDisplay(song, report(SpectrumAssessment.PrecisionLimited)))
        assertEquals(false, isKnownLosslessSpectrumSource(song))
    }

    @Test fun pcmContainersRemainEligibleForPrecisionReview() {
        for (format in listOf("WAV", "WAVE", "AIFF")) {
            val song = AudioFile(format = format, sampleRate = 96000, bitsPerSample = 24,
                channelCount = 2)
            assertEquals(true, isKnownLosslessSpectrumSource(song))
            assertEquals(SpectrumQualityDisplay.PrecisionReview,
                spectrumQualityDisplay(song, report(SpectrumAssessment.PrecisionLimited)))
        }
    }
}
