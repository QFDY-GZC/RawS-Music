package com.rawsmusic.ui.analysis

import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class SpectrumAssessment { Insufficient, SustainedRail, Review, BandLimited, PrecisionLimited, LimitedScope, NoFlags }
internal enum class SpectrumEvidence { Weak, Moderate }
internal enum class SpectrumPrecisionEvidence { None, SixteenBitGrid, TwentyFourBitGrid }
internal enum class SpectrumUpsamplingRisk { None, BandEdgeOnly, CombinedEvidence }

internal data class SpectrumBandEdge(
    val frequencyHz: Float,
    val dropDb: Float,
    val matchingFrames: Int,
    val eligibleFrames: Int,
    val evidence: SpectrumEvidence,
    val possibleUpsampling: Boolean
)

internal data class SpectrumQualityReport(
    val assessment: SpectrumAssessment,
    val peakDb: Float?,
    val rmsDb: Float?,
    val crestDb: Float?,
    val balanceDb: Float?,
    val nearFullScale: Boolean,
    val lowCrest: Boolean,
    val channelImbalance: Boolean,
    val inspectedFrames: Int,
    val activeFrames: Int,
    val bandEdge: SpectrumBandEdge?,
    val coarseFrequencyGrid: Boolean,
    val resampled: Boolean,
    val dsd: Boolean,
    val completeData: Boolean,
    val severeRailRisk: Boolean = false,
    val precisionEvidence: SpectrumPrecisionEvidence = SpectrumPrecisionEvidence.None,
    val upsamplingRisk: SpectrumUpsamplingRisk = SpectrumUpsamplingRisk.None,
    val highBandLevelDb: Float? = null
) {
    companion object { const val VERSION = 3 }
}

/** Conservative cache-based observations, not a classifier of master provenance.
 * Thresholds are heuristic screening rules. Evidence never means probability of authenticity.
 */
internal fun evaluateSpectrumQuality(
    analysis: AudioSpectrumAnalysis,
    sourceSampleRate: Int,
    sourceChannels: Int,
    dsd: Boolean,
    sourceBitDepth: Int = 0
): SpectrumQualityReport {
    val mono = sourceChannels == 1 || analysis.channels == 1
    val rmsValues = (if (mono) listOf(analysis.leftRmsDb) else listOf(analysis.leftRmsDb, analysis.rightRmsDb))
    val validRms = rmsValues.all { it.isFinite() && it in -120f..6f }
    val rms = if (validRms) (10 * log10(rmsValues.sumOf { 10.0.pow(it / 10.0) } / rmsValues.size)).toFloat()
        .takeIf { it > -119f } else null
    val peakValues = if (mono) listOf(analysis.leftPeakDb) else listOf(analysis.leftPeakDb, analysis.rightPeakDb)
    val peak = peakValues.takeIf { it.all { value -> value.isFinite() && value in -120f..6f } }
        ?.maxOrNull()?.takeIf { it > -119f }
    val crest = if (peak != null && rms != null && peak >= rms - 0.1f) (peak - rms).coerceAtLeast(0f) else null
    val balance = if (!mono && sourceChannels in 1..2 && validRms && rms != null)
        analysis.leftRmsDb - analysis.rightRmsDb else null
    val required = analysis.waterfallFrames.toLong() * analysis.waterfallBins
    val complete = analysis.sampleRate > 0 && analysis.durationMs > 0 &&
        analysis.waterfallFrames > 0 && analysis.waterfallBins >= 16 &&
        required <= analysis.leftWaterfall.size && (mono || required <= analysis.rightWaterfall.size) &&
        analysis.leftLevelsDb.size >= analysis.waterfallFrames &&
        (mono || analysis.rightLevelsDb.size >= analysis.waterfallFrames) &&
        analysis.averageSpectrumDb.size >= 16 && analysis.averageSpectrumDb.all { it.isFinite() }
    val frames = if (complete) (0 until analysis.waterfallFrames step
        ceil(analysis.waterfallFrames / 192.0).toInt().coerceAtLeast(1)).toList() else emptyList()
    fun level(frame: Int): Float = if (mono) analysis.leftLevelsDb[frame]
        else max(analysis.leftLevelsDb[frame], analysis.rightLevelsDb[frame])
    val loudest = frames.map { level(it) }.filter { it.isFinite() }.maxOrNull() ?: -120f
    val activeThreshold = max(-70f, loudest - 35f)
    val active = frames.filter { level(it).isFinite() && level(it) > activeThreshold }
    val resampled = sourceSampleRate > 0 && sourceSampleRate != analysis.sampleRate
    val coarse = analysis.maxFrequencyHz / (analysis.waterfallBins - 1).coerceAtLeast(1) > 150f
    val enough = complete && analysis.durationMs >= 10_000 && active.size >= 12 && rms != null && rms > -70f
    val edge = if (enough && !dsd && !resampled && !coarse)
        findBandEdge(analysis, active, mono, sourceSampleRate) else null
    val precisionEligible = enough && !dsd && !resampled && sourceSampleRate > 0 &&
        sourceChannels in 1..2 && sourceBitDepth in 24..32 &&
        analysis.nonzeroSampleCount >= analysis.sampleRate.toLong() * 3L &&
        analysis.decodedFrameCount <= Long.MAX_VALUE / 2 &&
        analysis.nonzeroSampleCount <= analysis.decodedFrameCount * analysis.channels.coerceIn(1, 2) &&
        analysis.aligned16SampleCount in 0L..analysis.nonzeroSampleCount &&
        analysis.aligned24SampleCount in analysis.aligned16SampleCount..analysis.nonzeroSampleCount
    val precisionEvidence = if (!precisionEligible) SpectrumPrecisionEvidence.None else when {
        analysis.aligned16SampleCount.toDouble() / analysis.nonzeroSampleCount >= 0.9999 ->
            SpectrumPrecisionEvidence.SixteenBitGrid
        sourceBitDepth == 32 &&
            analysis.aligned24SampleCount.toDouble() / analysis.nonzeroSampleCount >= 0.9999 ->
            SpectrumPrecisionEvidence.TwentyFourBitGrid
        else -> SpectrumPrecisionEvidence.None
    }
    val upsamplingRisk = when {
        !enough || dsd || resampled || sourceChannels !in 1..2 ||
            edge?.possibleUpsampling != true -> SpectrumUpsamplingRisk.None
        precisionEvidence == SpectrumPrecisionEvidence.SixteenBitGrid -> SpectrumUpsamplingRisk.CombinedEvidence
        else -> SpectrumUpsamplingRisk.BandEdgeOnly
    }
    // This is the level of the highest-frequency tail, not a calibrated microphone/noise floor.
    val highBandLevelDb = if (complete) {
        val values = analysis.averageSpectrumDb
        values.slice(((values.lastIndex * 0.88).toInt())..((values.lastIndex * 0.98).toInt()))
            .filter { it.isFinite() }.sorted().let { it.getOrNull(it.size / 2) }
    } else null
    val nearFullScale = enough && peak != null && peak >= -0.1f
    val lowCrest = enough && crest != null && crest < 6f
    val imbalance = enough && balance != null && abs(balance) >= 6f
    // A near-0 dB sample or small crest alone is not clipping. Require many consecutive
    // decoded frames at the integer rail, across multiple runs, and skip converted DSD/resampling.
    val severeRailRisk = enough && !dsd && !resampled && sourceSampleRate > 0 && sourceChannels in 1..2 &&
        analysis.decodedFrameCount >= analysis.sampleRate.toLong() * 10L &&
        analysis.nearRailFrameCount >= analysis.decodedFrameCount * 0.02 &&
        analysis.sustainedRailFrameCount >= analysis.decodedFrameCount * 0.01 &&
        analysis.sustainedRailRunCount >= 20L
    val assessment = when {
        !enough -> SpectrumAssessment.Insufficient
        severeRailRisk -> SpectrumAssessment.SustainedRail
        nearFullScale || lowCrest || imbalance -> SpectrumAssessment.Review
        edge?.evidence == SpectrumEvidence.Moderate -> SpectrumAssessment.BandLimited
        precisionEvidence == SpectrumPrecisionEvidence.SixteenBitGrid -> SpectrumAssessment.PrecisionLimited
        dsd || resampled || coarse || sourceChannels > 2 -> SpectrumAssessment.LimitedScope
        else -> SpectrumAssessment.NoFlags
    }
    return SpectrumQualityReport(assessment, peak, rms, crest, balance, nearFullScale, lowCrest,
        imbalance, frames.size, active.size, edge, coarse, resampled, dsd, complete, severeRailRisk,
        precisionEvidence, upsamplingRisk, highBandLevelDb)
}

private fun findBandEdge(a: AudioSpectrumAnalysis, active: List<Int>, mono: Boolean, sourceRate: Int): SpectrumBandEdge? {
    val nyquist = a.maxFrequencyHz
    val averageStep = nyquist / a.averageSpectrumDb.lastIndex
    val waterfallStep = nyquist / (a.waterfallBins - 1)
    val width = max(450f, waterfallStep * 5f)
    val gap = max(averageStep * 2f, waterfallStep)
    val powers = DoubleArray(a.averageSpectrumDb.size) { 10.0.pow(a.averageSpectrumDb[it].coerceIn(-120f, 6f) / 10.0) }
    fun averageBand(from: Float, to: Float): Float {
        val low = ceil(from / averageStep).toInt().coerceIn(0, powers.lastIndex)
        val high = (to / averageStep).toInt().coerceIn(low, powers.lastIndex)
        return (10 * log10((low..high).sumOf { powers[it] } / (high - low + 1))).toFloat()
    }
    var candidate: Float? = null
    var bestDrop = 0f
    // Exclude the Nyquist roll-off itself and require energy across a band, not a lone tone.
    val upper = minOf(30_000f, nyquist - width * 2)
    val lowerBin = ceil(6_000f / averageStep).toInt()
    val upperBin = (upper / averageStep).toInt()
    for (bin in lowerBin..upperBin) {
        val hz = bin * averageStep
        val before = averageBand(hz - width, hz - gap)
        val after = averageBand(hz + gap, hz + width)
        val tail = averageBand(hz + width, hz + width * 2)
        val drop = before - max(after, tail)
        val start = ((hz - width) / averageStep).toInt().coerceAtLeast(0)
        val end = ((hz - gap) / averageStep).toInt().coerceAtMost(powers.lastIndex)
        val occupied = (start..end).count { a.averageSpectrumDb[it] > before - 12f }
        if (before > -78f && after < -72f && tail < -72f && drop >= 18f && drop > bestDrop &&
            occupied >= (end - start + 1) * 0.6f) {
            candidate = hz
            bestDrop = drop
        }
    }
    val hz = candidate ?: return null
    val lookup = DoubleArray(256) { 10.0.pow((it * 120.0 / 255.0 - 120.0) / 10.0) }
    fun frameBand(frame: Int, from: Float, to: Float): Float {
        val low = ceil(from / waterfallStep).toInt().coerceIn(0, a.waterfallBins - 1)
        val high = (to / waterfallStep).toInt().coerceIn(low, a.waterfallBins - 1)
        var power = 0.0
        for (bin in low..high) {
            val index = frame * a.waterfallBins + bin
            val left = lookup[a.leftWaterfall[index].toInt() and 255]
            power += if (mono) left else (left + lookup[a.rightWaterfall[index].toInt() and 255]) / 2
        }
        return (10 * log10(power / (high - low + 1))).toFloat()
    }
    var eligible = 0
    var matching = 0
    active.forEach { frame ->
        val before = frameBand(frame, hz - width, hz - gap)
        if (before > -78f) {
            eligible++
            val after = frameBand(frame, hz + gap, hz + width * 2)
            if (after < -72f && before - after >= 18f) matching++
        }
    }
    val supported = eligible >= 12 && eligible >= active.size * 0.4f && matching >= eligible * 0.75f
    // Only report a compatibility clue near common lower-rate Nyquist boundaries.
    // It is never a positive identification of the original sample rate.
    val lowerRateBoundary = listOf(8_000f, 11_025f, 12_000f, 16_000f, 22_050f, 24_000f)
        .any { abs(hz - it) <= width }
    return SpectrumBandEdge(hz, bestDrop, matching, eligible,
        if (supported) SpectrumEvidence.Moderate else SpectrumEvidence.Weak,
        supported && sourceRate >= 88_200 && lowerRateBoundary)
}
