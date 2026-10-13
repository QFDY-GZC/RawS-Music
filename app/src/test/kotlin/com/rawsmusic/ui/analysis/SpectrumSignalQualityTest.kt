package com.rawsmusic.ui.analysis

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import org.junit.Assert.*
import org.junit.Test

class SpectrumSignalQualityTest {
    private val song = AudioFile(format = "FLAC", sampleRate = 48000, bitsPerSample = 24, channelCount = 2)
    private fun analysis() = AudioSpectrumAnalysis(48000, 2, 1024, 20000, 20000f, 0.8f,
        -2f, -2f, -18f, -18f, FloatArray(32) { -60f }, 20, 32,
        FloatArray(20) { -18f }, FloatArray(20) { -18f }, ByteArray(640), ByteArray(640),
        decodedFrameCount = 960000, qualityFlags = 15, integratedLufs = -16f,
        loudnessRangeLu = 8f, truePeakDb = -1.5f, leftDcOffset = 0f, rightDcOffset = 0f,
        stereoCorrelation = 0.5f, midRmsDb = -19f, sideRmsDb = -22f)
    private fun grade(a: AudioSpectrumAnalysis = analysis(), s: AudioFile = song,
        reference: SpectrumReferenceResult = SpectrumReferenceResult.NotChecked): SpectrumSignalQuality =
        assessSpectrumSignal(s, a, evaluateSpectrumQuality(a, s.sampleRate, s.channelCount, false), reference)

    @Test fun unknownAndLossyCodecsStillReceiveMeasuredGrade() {
        assertEquals(SpectrumSignalGrade.High, grade(s = song.copy(format = "AAC", bitRate = 320000)).grade)
        assertEquals(SpectrumSignalGrade.High, grade(s = song.copy(format = "unlisted")).grade)
    }
    @Test fun clippingWinsOverLosslessLabelAndReference() {
        val a = analysis().copy(nearRailFrameCount = 50000, sustainedRailFrameCount = 30000,
            sustainedRailRunCount = 30, leftPeakDb = 0f, rightPeakDb = 0f)
        assertEquals(SpectrumSignalGrade.Poor, grade(a, reference = SpectrumReferenceResult.Match).grade)
    }
    @Test fun loudnessAndSmallRangeAloneDoNotImplyBadMastering() {
        assertEquals(SpectrumSignalGrade.High,
            grade(analysis().copy(integratedLufs = -6f, loudnessRangeLu = 0.2f)).grade)
    }
    @Test fun truePeakOverAndStrongDcAreExplicitReasons() {
        assertTrue(SpectrumSignalIssue.TruePeakOver in grade(analysis().copy(truePeakDb = 1.2f)).issues)
        assertEquals(SpectrumSignalGrade.Poor, grade(analysis().copy(leftDcOffset = 0.1f)).grade)
    }
    @Test fun stereoCancellationIsReportedButMonoIsNotPunished() {
        val a = analysis().copy(stereoCorrelation = -1f, midRmsDb = -120f)
        assertTrue(SpectrumSignalIssue.MonoCancellation in grade(a).issues)
        assertEquals(SpectrumSignalGrade.High, grade(a.copy(channels = 1), song.copy(channelCount = 1)).grade)
    }
    @Test fun silenceMissingDataAndTruncatedDecodeAreNotCalledHealthy() {
        assertEquals(SpectrumSignalGrade.Unrated, grade(analysis().copy(qualityFlags = 0)).grade)
        assertEquals(SpectrumSignalGrade.Unrated,
            grade(analysis().copy(silentFrameCount = 960000, longestSilentFrameCount = 960000)).grade)
        assertEquals(SpectrumSignalGrade.Unrated, grade(analysis().copy(decodedFrameCount = 200000)).grade)
    }
    @Test fun incompleteOptionalMetricsDoNotEraseMeasuredSignalGrade() {
        assertEquals(SpectrumSignalGrade.High,
            grade(analysis().copy(qualityFlags = 1, integratedLufs = Float.NaN,
                loudnessRangeLu = Float.NaN, truePeakDb = Float.NaN)).grade)
    }
    @Test fun lowRateSpeechHasBandwidthReasonWithoutFalseUpsamplingAccusation() {
        val q = grade(analysis().copy(sampleRate = 16000, decodedFrameCount = 320000), song.copy(sampleRate = 16000))
        assertEquals(SpectrumSignalGrade.Fair, q.grade)
        assertTrue(SpectrumSignalIssue.LimitedBandwidth in q.issues)
    }
    @Test fun verifiedReferenceHasDistinctGradeOnlyWhenMeasuredChecksPass() {
        assertEquals(SpectrumSignalGrade.ReferenceVerified, grade(reference = SpectrumReferenceResult.Match).grade)
        assertEquals(SpectrumSignalGrade.Fair,
            grade(analysis().copy(truePeakDb = 1.2f), reference = SpectrumReferenceResult.Match).grade)
    }

    @Test fun absentChannelMetadataDoesNotHideMeasuredImbalance() {
        assertTrue(SpectrumSignalIssue.ChannelImbalance in
            grade(analysis().copy(rightRmsDb = -28f), song.copy(channelCount = 0)).issues)
    }

    @Test fun nativeMonoWithoutRightDcStillReceivesMeasuredGrade() {
        assertEquals(SpectrumSignalGrade.High, grade(analysis().copy(channels = 1,
            rightDcOffset = Float.NaN, stereoCorrelation = Float.NaN), song.copy(channelCount = 1)).grade)
    }

    @Test fun persistentLowPassInLosslessIsSourceReviewNotFakeMasterVerdict() {
        val a = analysis()
        val report = evaluateSpectrumQuality(a, 48000, 2, false).copy(bandEdge =
            SpectrumBandEdge(16000f, 50f, 20, 20, SpectrumEvidence.Moderate, false))
        assertEquals(SpectrumSignalGrade.HighNeedsReview,
            assessSpectrumSignal(song, a, report, SpectrumReferenceResult.NotChecked).grade)
        assertEquals(SpectrumSignalGrade.High,
            assessSpectrumSignal(song.copy(format = "AAC"), a, report, SpectrumReferenceResult.NotChecked).grade)
    }
}
