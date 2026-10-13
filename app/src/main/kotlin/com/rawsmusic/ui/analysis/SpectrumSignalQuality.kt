package com.rawsmusic.ui.analysis

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

internal enum class SpectrumSignalGrade { Unrated, Poor, Fair, High, HighNeedsReview, ReferenceVerified }
internal enum class SpectrumSignalIssue {
    MissingMeasurement, Silence, IncompleteDecode, SustainedRail, StrongDc, DcOffset,
    TruePeakOver, MonoCancellation, ChannelImbalance, StrongCompression, LimitedBandwidth,
    SourceResolutionReview
}
internal data class SpectrumSignalQuality(val grade: SpectrumSignalGrade, val issues: List<SpectrumSignalIssue>)

/** Rule-based signal screening. Thresholds describe measured defects/limits, not listening preference. */
internal fun assessSpectrumSignal(song: AudioFile, a: AudioSpectrumAnalysis, report: SpectrumQualityReport,
    reference: SpectrumReferenceResult): SpectrumSignalQuality {
    fun unavailable(issue: SpectrumSignalIssue) = SpectrumSignalQuality(SpectrumSignalGrade.Unrated, listOf(issue))
    if (a.qualityFlags and AudioSpectrumAnalysis.SIGNAL_MEASURED == 0 || a.sampleRate <= 0 ||
        a.decodedFrameCount < a.sampleRate || !a.leftDcOffset.isFinite() ||
        (a.channels != 1 && !a.rightDcOffset.isFinite()))
        return unavailable(SpectrumSignalIssue.MissingMeasurement)
    val decodedMs = a.decodedFrameCount.toDouble() * 1000 / a.sampleRate
    if (a.durationMs > 0 && abs(decodedMs - a.durationMs) > max(1000.0, a.durationMs * 0.02))
        return unavailable(SpectrumSignalIssue.IncompleteDecode)
    if (a.silentFrameCount >= a.decodedFrameCount * 0.99 || report.rmsDb == null || report.rmsDb < -90f)
        return unavailable(SpectrumSignalIssue.Silence)
    val issues = mutableListOf<SpectrumSignalIssue>()
    val sustained = decodedMs >= 10000 && !report.dsd &&
        a.nearRailFrameCount >= a.decodedFrameCount * 0.02 &&
        a.sustainedRailFrameCount >= a.decodedFrameCount * 0.01 && a.sustainedRailRunCount >= 20
    if (sustained) issues += SpectrumSignalIssue.SustainedRail
    val dc = if (a.channels == 1) abs(a.leftDcOffset) else max(abs(a.leftDcOffset), abs(a.rightDcOffset))
    val rmsLinear = 10.0.pow(report.rmsDb / 20.0)
    if (dc >= 0.05 && dc >= rmsLinear * 0.25) issues += SpectrumSignalIssue.StrongDc
    else if (dc >= 0.005 && dc >= rmsLinear * 0.05) issues += SpectrumSignalIssue.DcOffset
    if (a.qualityFlags and AudioSpectrumAnalysis.TRUE_PEAK_MEASURED != 0 && a.truePeakDb.isFinite() && a.truePeakDb > 0.3f)
        issues += SpectrumSignalIssue.TruePeakOver
    if (a.channels == 2 && song.channelCount != 1 && a.stereoCorrelation.isFinite() &&
        a.stereoCorrelation < -0.5f && a.midRmsDb.isFinite() && a.midRmsDb < report.rmsDb - 6f)
        issues += SpectrumSignalIssue.MonoCancellation
    if (a.channels == 2 && song.channelCount != 1 && a.leftRmsDb.isFinite() && a.rightRmsDb.isFinite() &&
        abs(a.leftRmsDb - a.rightRmsDb) >= 6f) issues += SpectrumSignalIssue.ChannelImbalance
    if (report.crestDb != null && (report.crestDb < 4f ||
        (report.crestDb < 6f && a.qualityFlags and AudioSpectrumAnalysis.LOUDNESS_MEASURED != 0 && a.integratedLufs > -9f)))
        issues += SpectrumSignalIssue.StrongCompression
    if (a.sampleRate < 32000 || (report.bandEdge?.let { it.evidence == SpectrumEvidence.Moderate && it.frequencyHz < 14000 } == true))
        issues += SpectrumSignalIssue.LimitedBandwidth
    val sourceReview = isKnownLosslessSpectrumSource(song) && (report.precisionEvidence == SpectrumPrecisionEvidence.SixteenBitGrid ||
        report.upsamplingRisk != SpectrumUpsamplingRisk.None ||
        report.bandEdge?.let { it.evidence == SpectrumEvidence.Moderate &&
            it.frequencyHz < 18000f && it.frequencyHz < a.maxFrequencyHz * 0.8f } == true)
    if (sourceReview) issues += SpectrumSignalIssue.SourceResolutionReview
    val grade = when {
        sustained || SpectrumSignalIssue.StrongDc in issues -> SpectrumSignalGrade.Poor
        issues.any { it != SpectrumSignalIssue.SourceResolutionReview } -> SpectrumSignalGrade.Fair
        sourceReview -> SpectrumSignalGrade.HighNeedsReview
        reference == SpectrumReferenceResult.Match -> SpectrumSignalGrade.ReferenceVerified
        else -> SpectrumSignalGrade.High
    }
    return SpectrumSignalQuality(grade, issues)
}
