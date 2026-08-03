package com.rawsmusic.core.common.spectrum

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
    val rightWaterfall: ByteArray
) {
    val maxFrequencyHz: Float
        get() = sampleRate * 0.5f

    val stereoPeakDb: Float
        get() = maxOf(leftPeakDb, rightPeakDb)

    val stereoAverageDb: Float
        get() = (leftRmsDb + rightRmsDb) * 0.5f
}

data class AudioSpectrumAnalysisProgress(
    val fraction: Float,
    val leftDb: Float,
    val rightDb: Float
)
