package com.rawsmusic.module.player.dsp

/** Native mono FFT analyzer. The legacy object name is retained to avoid JNI/API churn. */
object NativeStereoSpectrumAnalyzer {
    const val BAND_COUNT = 112
    const val OUTPUT_SIZE = BAND_COUNT

    const val PCM_S16_LE = 1
    const val PCM_S24_PACKED_LE = 2
    const val PCM_S32_LE = 3
    const val PCM_FLOAT32_LE = 4

    init {
        System.loadLibrary("rawsmusic_dsp")
    }

    fun analyze(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        sampleEncoding: Int,
        validBitsPerSample: Int,
        output: FloatArray
    ): Boolean {
        if (output.size < OUTPUT_SIZE) return false
        return nativeAnalyze(
            buffer,
            read,
            channels,
            sampleRate,
            sampleEncoding,
            validBitsPerSample,
            output
        )
    }

    fun tick(paused: Boolean, output: FloatArray): Boolean {
        if (output.size < OUTPUT_SIZE) return false
        return nativeTick(paused, output)
    }

    fun reset() = nativeReset()

    private external fun nativeAnalyze(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        sampleEncoding: Int,
        validBitsPerSample: Int,
        output: FloatArray
    ): Boolean

    private external fun nativeTick(paused: Boolean, output: FloatArray): Boolean

    private external fun nativeReset()
}
