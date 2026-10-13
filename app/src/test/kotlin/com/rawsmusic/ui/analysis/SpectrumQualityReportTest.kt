package com.rawsmusic.ui.analysis

import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class SpectrumQualityReportTest {
    private fun recording(sampleRate: Int = 48000, duration: Long = 60000, peak: Float = -3f,
        rms: Float = -18f, spectrum: (Float) -> Float = { -45f },
        frameSpectrum: ((Int, Float) -> Float)? = null): AudioSpectrumAnalysis {
        val bins = 768
        val frames = 180
        val payload = ByteArray(frames * bins) { index ->
            val hz = index % bins * (sampleRate / 2f) / (bins - 1)
            val db = frameSpectrum?.invoke(index / bins, hz) ?: spectrum(hz)
            ((db.coerceIn(-120f, 0f) + 120f) / 120f * 255).roundToInt().toByte()
        }
        return AudioSpectrumAnalysis(sampleRate, 2, 16384, duration, 0f, 1f, peak, peak, rms, rms,
            FloatArray(1024) { spectrum(it * (sampleRate / 2f) / 1023) }, frames, bins,
            FloatArray(frames) { rms }, FloatArray(frames) { rms }, payload, payload.copyOf())
    }

    @Test fun silenceDoesNotPassAsGoodQuality() {
        val a = recording(peak = -120f, rms = -120f, spectrum = { -120f })
        val r = evaluateSpectrumQuality(a, 48000, 2, false)
        assertEquals(SpectrumAssessment.Insufficient, r.assessment)
        assertNull(r.crestDb)
        assertNull(r.bandEdge)
    }

    @Test fun shortRecordingDoesNotGetSourceClassification() {
        val a = recording(duration = 500, spectrum = { if (it < 16000) -40f else -115f })
        assertEquals(SpectrumAssessment.Insufficient, evaluateSpectrumQuality(a, 48000, 2, false).assessment)
    }

    @Test fun stableLowPassIsOnlyBandLimitedNotFakeLossless() {
        val a = recording(spectrum = { if (it < 16000) -40f else -115f })
        val r = evaluateSpectrumQuality(a, 48000, 2, false)
        assertEquals(SpectrumAssessment.BandLimited, r.assessment)
        assertEquals(SpectrumEvidence.Moderate, r.bandEdge?.evidence)
        assertEquals(16000f, r.bandEdge!!.frequencyHz, 500f)
        assertFalse(r.bandEdge.possibleUpsampling)
    }

    @Test fun inconsistentTimeEvidenceCannotProduceBandLimitedConclusion() {
        val a = recording(spectrum = { if (it < 16000) -40f else -115f },
            frameSpectrum = { frame, hz -> if (frame < 30 && hz > 16000) -115f else -40f })
        val r = evaluateSpectrumQuality(a, 48000, 2, false)
        assertEquals(SpectrumEvidence.Weak, r.bandEdge?.evidence)
        assertNotEquals(SpectrumAssessment.BandLimited, r.assessment)
    }

    @Test fun smoothRollOffDoesNotLookLikeSharpLowPass() {
        val a = recording(spectrum = { -35f - it / 24000 * 65f })
        assertNull(evaluateSpectrumQuality(a, 48000, 2, false).bandEdge)
    }

    @Test fun dsdAndResamplingDisableSourceClues() {
        val a = recording(sampleRate = 96000, spectrum = { if (it < 22050) -40f else -115f })
        assertNull(evaluateSpectrumQuality(a, 2822400, 2, true).bandEdge)
        assertNull(evaluateSpectrumQuality(a, 44100, 2, false).bandEdge)
        assertEquals(SpectrumAssessment.LimitedScope, evaluateSpectrumQuality(a, 2822400, 2, true).assessment)
        assertTrue(evaluateSpectrumQuality(a, 96000, 2, false).bandEdge!!.possibleUpsampling)
    }

    @Test fun fullScalePeakRequiresReviewWithoutClaimingClipping() {
        val r = evaluateSpectrumQuality(recording(peak = 0f), 48000, 2, false)
        assertEquals(SpectrumAssessment.Review, r.assessment)
        assertTrue(r.nearFullScale)
    }

    @Test fun stereoRmsUsesEnergyNotArithmeticDbAverage() {
        val a = recording().copy(leftRmsDb = -10f, rightRmsDb = -30f)
        val r = evaluateSpectrumQuality(a, 48000, 2, false)
        assertEquals(-12.967f, r.rmsDb!!, 0.01f)
        assertTrue(r.channelImbalance)
    }

    @Test fun malformedCacheIsNotNormal() {
        val a = recording().copy(leftWaterfall = byteArrayOf(), averageSpectrumDb = floatArrayOf(Float.NaN))
        val r = evaluateSpectrumQuality(a, 48000, 2, false)
        assertFalse(r.completeData)
        assertEquals(SpectrumAssessment.Insufficient, r.assessment)
    }

    @Test fun monoDoesNotDependOnRightChannelPayload() {
        val a = recording().copy(channels = 1, rightWaterfall = byteArrayOf(), rightLevelsDb = floatArrayOf(), rightRmsDb = Float.NaN)
        val r = evaluateSpectrumQuality(a, 48000, 1, false)
        assertTrue(r.completeData)
        assertNull(r.balanceDb)
    }

    @Test fun manySustainedRailSamplesProduceASevereRisk() {
        val a = recording(peak = 0f).copy(decodedFrameCount = 2_880_000,
            nearRailFrameCount = 90_000, sustainedRailFrameCount = 40_000,
            sustainedRailRunCount = 200)
        val r = evaluateSpectrumQuality(a, 48000, 2, false)
        assertEquals(SpectrumAssessment.SustainedRail, r.assessment)
        assertTrue(r.severeRailRisk)
    }

    @Test fun isolatedFullScaleSamplesDoNotGetSevereGrade() {
        val a = recording(peak = 0f).copy(decodedFrameCount = 2_880_000,
            nearRailFrameCount = 90_000, sustainedRailFrameCount = 0,
            sustainedRailRunCount = 0)
        val r = evaluateSpectrumQuality(a, 48000, 2, false)
        assertEquals(SpectrumAssessment.Review, r.assessment)
        assertFalse(r.severeRailRisk)
    }

    @Test fun resampledOrDsdDataCannotTriggerSevereRailGrade() {
        val a = recording(peak = 0f).copy(decodedFrameCount = 2_880_000,
            nearRailFrameCount = 90_000, sustainedRailFrameCount = 40_000,
            sustainedRailRunCount = 200)
        assertFalse(evaluateSpectrumQuality(a, 44100, 2, false).severeRailRisk)
        assertFalse(evaluateSpectrumQuality(a, 48000, 2, true).severeRailRisk)
    }

    @Test fun downmixedOrUnknownSourceCannotTriggerSevereRailGrade() {
        val a = recording(peak = 0f).copy(decodedFrameCount = 2_880_000,
            nearRailFrameCount = 90_000, sustainedRailFrameCount = 40_000,
            sustainedRailRunCount = 200)
        assertFalse(evaluateSpectrumQuality(a, 48000, 6, false).severeRailRisk)
        assertFalse(evaluateSpectrumQuality(a, 0, 2, false).severeRailRisk)
    }

    @Test fun repeatedHighRateBoundaryAndSixteenBitGridProduceCombinedRisk() {
        val a = recording(sampleRate = 96000,
            spectrum = { if (it < 22050f) -40f else -115f }).copy(
            decodedFrameCount = 5_760_000, nonzeroSampleCount = 10_000_000,
            aligned16SampleCount = 9_999_500, aligned24SampleCount = 9_999_900)
        val report = evaluateSpectrumQuality(a, 96000, 2, false, 24)
        assertEquals(SpectrumPrecisionEvidence.SixteenBitGrid, report.precisionEvidence)
        assertEquals(SpectrumUpsamplingRisk.CombinedEvidence, report.upsamplingRisk)
        assertEquals(-115f, report.highBandLevelDb!!, 1.5f)
    }

    @Test fun bandEdgeWithoutQuantizationEvidenceIsNotCombinedRisk() {
        val a = recording(sampleRate = 96000,
            spectrum = { if (it < 22050f) -40f else -115f }).copy(
            decodedFrameCount = 5_760_000, nonzeroSampleCount = 10_000_000,
            aligned16SampleCount = 1_000_000, aligned24SampleCount = 9_999_900)
        val report = evaluateSpectrumQuality(a, 96000, 2, false, 24)
        assertEquals(SpectrumPrecisionEvidence.None, report.precisionEvidence)
        assertEquals(SpectrumUpsamplingRisk.BandEdgeOnly, report.upsamplingRisk)
    }

    @Test fun highRateWithPersistentSixteenKhzBoundaryGetsOnlySourceClue() {
        val a = recording(sampleRate = 96000, spectrum = { if (it < 16000f) -40f else -115f })
        val report = evaluateSpectrumQuality(a, 96000, 2, false, 24)
        assertEquals(SpectrumUpsamplingRisk.BandEdgeOnly, report.upsamplingRisk)
        assertEquals(SpectrumEvidence.Moderate, report.bandEdge?.evidence)
    }

    @Test fun precisionGridAloneDoesNotClaimUpsampling() {
        val a = recording(sampleRate = 96000).copy(
            decodedFrameCount = 5_760_000, nonzeroSampleCount = 10_000_000,
            aligned16SampleCount = 10_000_000, aligned24SampleCount = 10_000_000)
        val report = evaluateSpectrumQuality(a, 96000, 2, false, 24)
        assertEquals(SpectrumPrecisionEvidence.SixteenBitGrid, report.precisionEvidence)
        assertEquals(SpectrumUpsamplingRisk.None, report.upsamplingRisk)
        assertEquals(SpectrumAssessment.PrecisionLimited, report.assessment)
    }

    @Test fun shortOrSparseAudioCannotGetPrecisionHint() {
        val a = recording(sampleRate = 96000, duration = 1000).copy(
            decodedFrameCount = 96_000, nonzeroSampleCount = 100,
            aligned16SampleCount = 100, aligned24SampleCount = 100)
        val report = evaluateSpectrumQuality(a, 96000, 2, false, 24)
        assertEquals(SpectrumPrecisionEvidence.None, report.precisionEvidence)
        assertEquals(SpectrumUpsamplingRisk.None, report.upsamplingRisk)
    }

    @Test fun resampledDsdAndDownmixedSignalsDoNotGetProvenanceHints() {
        val a = recording(sampleRate = 96000,
            spectrum = { if (it < 22050f) -40f else -115f }).copy(
            decodedFrameCount = 5_760_000, nonzeroSampleCount = 10_000_000,
            aligned16SampleCount = 10_000_000, aligned24SampleCount = 10_000_000)
        assertEquals(SpectrumUpsamplingRisk.None, evaluateSpectrumQuality(a, 44100, 2, false, 24).upsamplingRisk)
        assertEquals(SpectrumUpsamplingRisk.None, evaluateSpectrumQuality(a, 96000, 2, true, 24).upsamplingRisk)
        assertEquals(SpectrumUpsamplingRisk.None, evaluateSpectrumQuality(a, 96000, 6, false, 24).upsamplingRisk)
        assertEquals(SpectrumPrecisionEvidence.None, evaluateSpectrumQuality(a, 96000, 6, false, 24).precisionEvidence)
    }

    @Test fun thirtyTwoBitContainerWithTwentyFourBitGridGetsOnlyPrecisionHint() {
        val a = recording(sampleRate = 96000).copy(
            decodedFrameCount = 5_760_000, nonzeroSampleCount = 10_000_000,
            aligned16SampleCount = 1_000_000, aligned24SampleCount = 9_999_900)
        val report = evaluateSpectrumQuality(a, 96000, 2, false, 32)
        assertEquals(SpectrumPrecisionEvidence.TwentyFourBitGrid, report.precisionEvidence)
        assertEquals(SpectrumUpsamplingRisk.None, report.upsamplingRisk)
    }

    @Test fun monoSourceDecodedAsStereoDoesNotLosePrecisionEvidence() {
        val a = recording(sampleRate = 96000).copy(
            decodedFrameCount = 5_760_000, nonzeroSampleCount = 10_000_000,
            aligned16SampleCount = 10_000_000, aligned24SampleCount = 10_000_000)
        assertEquals(SpectrumPrecisionEvidence.SixteenBitGrid,
            evaluateSpectrumQuality(a, 96000, 1, false, 24).precisionEvidence)
    }
}
