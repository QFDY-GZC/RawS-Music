package com.rawsmusic.core.common.spectrum

/** JNI bridge for the offline file-spectrum FFT in rawscoreservice. */
object AudioSpectrumNative {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("rawscoreservice")
        true
    }.getOrDefault(false)

    fun isAvailable(): Boolean = loaded

    fun create(sampleRate: Int, channels: Int, durationMs: Long, fftSize: Int): Long {
        if (!loaded) return 0L
        return nativeCreate(sampleRate, channels, durationMs, fftSize)
    }

    fun processS32Le(handle: Long, data: ByteArray, offset: Int, length: Int): FloatArray? {
        if (!loaded || length <= 0) return null
        return nativeProcessS32Le(handle, data, offset, length)
    }

    fun finish(handle: Long): ByteArray? {
        if (!loaded || handle == 0L) return null
        return nativeFinish(handle)
    }

    fun release(handle: Long) {
        if (loaded && handle != 0L) nativeRelease(handle)
    }

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        durationMs: Long,
        fftSize: Int
    ): Long

    private external fun nativeProcessS32Le(
        handle: Long,
        data: ByteArray,
        offset: Int,
        length: Int
    ): FloatArray?

    private external fun nativeFinish(handle: Long): ByteArray?

    private external fun nativeRelease(handle: Long)
}
