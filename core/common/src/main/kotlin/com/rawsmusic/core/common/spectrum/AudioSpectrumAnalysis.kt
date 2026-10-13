package com.rawsmusic.core.common.spectrum

import kotlin.math.log10
import kotlin.math.pow

data class AudioSpectrumAnalysis(
    val sampleRate: Int,
    val channels: Int,
    val fftSize: Int,
    val durationMs: Long,
    val cutoffHz: Float,
    val cutoffConfidence: Float,
    val leftPeakDb: Float,
    val rightPeakDb: Float,
    val leftRmsDb: Float,
    val rightRmsDb: Float,
    val averageSpectrumDb: FloatArray,
    val waterfallFrames: Int,
    val waterfallBins: Int,
    val leftLevelsDb: FloatArray,
    val rightLevelsDb: FloatArray,
    val leftWaterfall: ByteArray,
    val rightWaterfall: ByteArray,
    val decodedFrameCount: Long = 0,
    val nearRailFrameCount: Long = 0,
    val sustainedRailFrameCount: Long = 0,
    val sustainedRailRunCount: Long = 0,
    val nonzeroSampleCount: Long = 0,
    val aligned16SampleCount: Long = 0,
    val aligned24SampleCount: Long = 0,
    val qualityFlags: Int = 0,
    val integratedLufs: Float = Float.NaN,
    val loudnessRangeLu: Float = Float.NaN,
    val truePeakDb: Float = Float.NaN,
    val leftDcOffset: Float = Float.NaN,
    val rightDcOffset: Float = Float.NaN,
    val stereoCorrelation: Float = Float.NaN,
    val midRmsDb: Float = Float.NaN,
    val sideRmsDb: Float = Float.NaN,
    val silentFrameCount: Long = 0,
    val longestSilentFrameCount: Long = 0,
    val hardRailFrameCount: Long = 0
) {
    companion object {
        const val SIGNAL_MEASURED = 1
        const val LOUDNESS_MEASURED = 2
        const val LOUDNESS_RANGE_MEASURED = 4
        const val TRUE_PEAK_MEASURED = 8
    }
    val maxFrequencyHz: Float
        get() = sampleRate * 0.5f

    val stereoPeakDb: Float
        get() = maxOf(leftPeakDb, rightPeakDb)

    val stereoAverageDb: Float
        get() = if (channels == 1) leftRmsDb else
            (10.0 * log10((10.0.pow(leftRmsDb / 10.0) + 10.0.pow(rightRmsDb / 10.0)) / 2.0)).toFloat()
}

data class AudioSpectrumAnalysisProgress(
    val fraction: Float,
    val leftDb: Float,
    val rightDb: Float
)
