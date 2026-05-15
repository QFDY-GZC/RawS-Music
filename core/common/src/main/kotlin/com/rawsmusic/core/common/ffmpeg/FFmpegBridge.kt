package com.rawsmusic.core.common.ffmpeg

import android.util.Log

object FFmpegBridge {
    private const val TAG = "FFmpegBridge"
    private var loaded = false

    init {
        try {
            System.loadLibrary("avutil")
            System.loadLibrary("swresample")
            System.loadLibrary("avcodec")
            System.loadLibrary("avformat")
            System.loadLibrary("rawsmusic_ffmpeg")
            loaded = true
            Log.d(TAG, "FFmpeg native libraries loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load FFmpeg native libraries", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    /**
     * 兼容旧调用：默认 16bit / stereo 输出。
     */
    fun convertToWav(inputPath: String, outputPath: String, targetSampleRate: Int): Int {
        return convertToWav(inputPath, outputPath, targetSampleRate, 16, 2)
    }

    /**
     * 将音频转为 WAV 文件，使用 FFmpeg swresample 进行高质量重采样。
     *
     * @param targetSampleRate 目标采样率，0 或负值保持原始采样率
     * @param bitsPerSample    输出比特深度：16 / 24 / 32
     * @param channels         输出声道数，2=立体声
     */
    fun convertToWav(
        inputPath: String,
        outputPath: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int
    ): Int {
        if (!loaded) return -1
        return nativeConvertToWav(inputPath, outputPath, targetSampleRate, bitsPerSample, channels)
    }

    /**
     * 兼容旧调用：默认输出 16bit / stereo / raw PCM。
     */
    fun convertToRawPcm(inputPath: String, outputPath: String, targetSampleRate: Int): Int {
        return convertToRawPcm(
            inputPath = inputPath,
            outputPath = outputPath,
            targetSampleRate = targetSampleRate,
            bitsPerSample = 16,
            channels = 2
        )
    }

    /**
     * 转换音频为裸 PCM，不写 WAV 头。
     *
     * bitsPerSample:
     * 16 -> s16le, 每采样 2 字节
     * 24 -> s24le packed, 每采样 3 字节
     * 32 -> s32le, 每采样 4 字节
     */
    fun convertToRawPcm(
        inputPath: String,
        outputPath: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int
    ): Int {
        if (!loaded) return -1
        return nativeConvertToRawPcm(
            inputPath,
            outputPath,
            targetSampleRate,
            bitsPerSample,
            channels
        )
    }

    fun probeDuration(path: String): Long {
        if (!loaded) return 0L
        return nativeProbeDuration(path)
    }

    fun probeSampleRate(path: String): Int {
        if (!loaded) return 0
        return nativeProbeSampleRate(path)
    }

    fun probeBitsPerSample(path: String): Int {
        if (!loaded) return 0
        return nativeProbeBitsPerSample(path)
    }

    fun probeChannelCount(path: String): Int {
        if (!loaded) return 0
        return nativeProbeChannelCount(path)
    }

    fun extractCover(inputPath: String, outputPath: String): Int {
        if (!loaded) return -1
        return nativeExtractCover(inputPath, outputPath)
    }

    fun getMediaInfo(filePath: String): Map<String, String>? {
        if (!loaded) return null
        return nativeGetMediaInfo(filePath)
    }

    /**
     * 通过 FFmpeg 直接写入音频文件的元数据标签。
     * 绕过 MediaStore contentResolver，在 Android 11+ 上可靠工作。
     *
     * @param filePath 音频文件的绝对路径
     * @param metadata 键值对，键为 FFmpeg 标签名（如 "title", "artist", "album", "date", "track", "genre"）
     * @param cacheDir 应用缓存目录路径，用于写入临时文件
     * @return 0 成功，负值失败
     */
    fun writeMetadata(filePath: String, metadata: Map<String, String>, cacheDir: String): Int {
        if (!loaded) return -1
        return nativeWriteMetadata(filePath, metadata, cacheDir)
    }

    // ========== Streaming Decoder API (zero-disk playback) ==========

    /**
     * 打开流式解码器，返回解码器句柄（0 表示失败）。
     * @param targetSampleRate 目标采样率，0 保持原始
     * @param bitsPerSample    输出比特深度：16 / 24 / 32
     * @param channels         输出声道数
     */
    fun openDecoder(path: String, targetSampleRate: Int, bitsPerSample: Int, channels: Int): Long {
        if (!loaded) return 0L
        return nativeOpenDecoder(path, targetSampleRate, bitsPerSample, channels)
    }

    /**
     * 从流式解码器读取下一个 PCM 块。
     * @return 写入字节数，-1=EOF，-2=错误
     */
    fun decodeChunk(handle: Long, buffer: ByteArray, offset: Int, maxBytes: Int): Int {
        if (!loaded) return -2
        return nativeDecodeChunk(handle, buffer, offset, maxBytes)
    }

    /**
     * Seek 到指定位置（毫秒）。
     */
    fun seekDecoder(handle: Long, positionMs: Long): Boolean {
        if (!loaded) return false
        return nativeSeekDecoder(handle, positionMs)
    }

    fun getDecoderSampleRate(handle: Long): Int {
        if (!loaded) return 0
        return nativeGetDecoderSampleRate(handle)
    }

    fun getDecoderChannels(handle: Long): Int {
        if (!loaded) return 0
        return nativeGetDecoderChannels(handle)
    }

    fun getDecoderBitsPerSample(handle: Long): Int {
        if (!loaded) return 0
        return nativeGetDecoderBitsPerSample(handle)
    }

    fun getDecoderDuration(handle: Long): Long {
        if (!loaded) return 0L
        return nativeGetDecoderDuration(handle)
    }

    fun closeDecoder(handle: Long) {
        if (!loaded || handle == 0L) return
        nativeCloseDecoder(handle)
    }

    private external fun nativeConvertToWav(
        inputPath: String,
        outputPath: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int
    ): Int

    private external fun nativeConvertToRawPcm(
        inputPath: String,
        outputPath: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int
    ): Int

    private external fun nativeProbeDuration(path: String): Long
    private external fun nativeProbeSampleRate(path: String): Int
    private external fun nativeProbeBitsPerSample(path: String): Int
    private external fun nativeProbeChannelCount(path: String): Int
    private external fun nativeExtractCover(inputPath: String, outputPath: String): Int
    private external fun nativeGetMediaInfo(filePath: String): Map<String, String>?
    private external fun nativeWriteMetadata(filePath: String, metadata: Map<String, String>, cacheDir: String): Int

    // Streaming decoder native methods
    private external fun nativeOpenDecoder(path: String, targetRate: Int, targetBits: Int, channels: Int): Long
    private external fun nativeDecodeChunk(handle: Long, buffer: ByteArray, offset: Int, maxBytes: Int): Int
    private external fun nativeSeekDecoder(handle: Long, positionMs: Long): Boolean
    private external fun nativeGetDecoderSampleRate(handle: Long): Int
    private external fun nativeGetDecoderChannels(handle: Long): Int
    private external fun nativeGetDecoderBitsPerSample(handle: Long): Int
    private external fun nativeGetDecoderDuration(handle: Long): Long
    private external fun nativeCloseDecoder(handle: Long)
}