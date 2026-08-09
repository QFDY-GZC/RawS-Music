package com.rawsmusic.core.common.ffmpeg

import android.util.Log
import android.view.Surface
import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object FFmpegBridge {
    private const val TAG = "FFmpegBridge"
    private const val MAX_DEBUG_ENTRIES = 240
    private var loaded = false
    private val debugDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val debugLock = Any()
    private val recentDebugEntries = ArrayDeque<String>()

    init {
        try {
            // rawsmusic_ffmpeg is linked against split FFmpeg libs (avcodec, avformat, avutil, swresample)
            // The dynamic linker loads them automatically — no need to load libffmpeg.so separately.
            System.loadLibrary("rawsmusic_ffmpeg")
            loaded = true
            Log.d(TAG, "FFmpeg native libraries loaded")
            appendDebug("libraries loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load FFmpeg native libraries", e)
            appendDebug("load failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun isLoaded(): Boolean = loaded

    fun resetDebugLog(reason: String) {
        synchronized(debugLock) {
            recentDebugEntries.clear()
        }
        appendDebug("reset: $reason")
    }

    fun getRecentDebugLog(): String? {
        return synchronized(debugLock) {
            if (recentDebugEntries.isEmpty()) null else recentDebugEntries.joinToString("\n")
        }
    }

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
     * 24 -> s32le, 每采样 4 字节
     * 32 -> s32le, 每采样 4 字节
     */
    fun convertToRawPcm(
        inputPath: String,
        outputPath: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int
    ): Int {
        if (!loaded) {
            appendDebug("convertToRawPcm skipped: bridge not loaded")
            return -1
        }
        appendDebug(
            "convertToRawPcm in=${shortPath(inputPath)} out=${shortPath(outputPath)} " +
                "targetSr=$targetSampleRate bits=$bitsPerSample ch=$channels"
        )
        val result = nativeConvertToRawPcm(
            inputPath,
            outputPath,
            targetSampleRate,
            bitsPerSample,
            channels
        )
        appendDebug("convertToRawPcm result=$result")
        return result
    }

    fun probeDuration(path: String): Long {
        if (!loaded) return 0L
        return nativeProbeDuration(path)
    }

    fun probeDuration(path: String, headers: Map<String, String>, userAgent: String?): Long {
        if (!loaded) return 0L
        val startedAt = SystemClock.elapsedRealtime()
        val options = serializeHttpOptions(headers, userAgent)
        val result = nativeProbeDurationWithOptions(path, options.first, options.second)
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} PROBE kind=duration result=$result " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(headers)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(path)} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return result
    }

    fun probeSampleRate(path: String): Int {
        if (!loaded) {
            appendDebug("probeSampleRate skipped: bridge not loaded")
            return 0
        }
        val result = nativeProbeSampleRate(path)
        appendDebug("probeSampleRate ${shortPath(path)} -> $result")
        return result
    }

    fun probeSampleRate(path: String, headers: Map<String, String>, userAgent: String?): Int {
        if (!loaded) return 0
        val startedAt = SystemClock.elapsedRealtime()
        val options = serializeHttpOptions(headers, userAgent)
        val result = nativeProbeSampleRateWithOptions(path, options.first, options.second)
        appendDebug("probeSampleRate http ${shortPath(path)} headers=${headers.size} -> $result")
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} PROBE kind=sample_rate result=$result " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(headers)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(path)} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return result
    }

    fun probeBitsPerSample(path: String): Int {
        if (!loaded) {
            appendDebug("probeBitsPerSample skipped: bridge not loaded")
            return 0
        }
        val result = nativeProbeBitsPerSample(path)
        appendDebug("probeBitsPerSample ${shortPath(path)} -> $result")
        return result
    }

    fun probeBitsPerSample(path: String, headers: Map<String, String>, userAgent: String?): Int {
        if (!loaded) return 0
        val startedAt = SystemClock.elapsedRealtime()
        val options = serializeHttpOptions(headers, userAgent)
        val result = nativeProbeBitsPerSampleWithOptions(path, options.first, options.second)
        appendDebug("probeBitsPerSample http ${shortPath(path)} headers=${headers.size} -> $result")
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} PROBE kind=bits result=$result " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(headers)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(path)} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return result
    }

    fun probeChannelCount(path: String): Int {
        if (!loaded) {
            appendDebug("probeChannelCount skipped: bridge not loaded")
            return 0
        }
        val result = nativeProbeChannelCount(path)
        appendDebug("probeChannelCount ${shortPath(path)} -> $result")
        return result
    }

    fun probeChannelCount(path: String, headers: Map<String, String>, userAgent: String?): Int {
        if (!loaded) return 0
        val startedAt = SystemClock.elapsedRealtime()
        val options = serializeHttpOptions(headers, userAgent)
        val result = nativeProbeChannelCountWithOptions(path, options.first, options.second)
        appendDebug("probeChannelCount http ${shortPath(path)} headers=${headers.size} -> $result")
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} PROBE kind=channels result=$result " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(headers)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(path)} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return result
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

    /**
     * Offline waveform scan.
     * Returns normalized PCM waveform bars in 0..1, or an empty array on failure.
     * startMs/endMs are used for CUE tracks; native scans the segment sequentially into time buckets.
     */
    fun scanWaveform(path: String, startMs: Long, endMs: Long, sampleCount: Int): FloatArray {
        if (!loaded || path.isBlank() || sampleCount <= 0) {
            appendDebug("scanWaveform skipped: loaded=$loaded pathBlank=${path.isBlank()} samples=$sampleCount")
            return FloatArray(0)
        }
        val boundedSamples = sampleCount.coerceIn(32, 21_600)
        val result = nativeScanWaveform(path, startMs.coerceAtLeast(0L), endMs.coerceAtLeast(0L), boundedSamples)
            ?: FloatArray(0)
        appendDebug(
            "scanWaveform ${shortPath(path)} start=$startMs end=$endMs samples=$boundedSamples -> ${result.size}"
        )
        return result
    }

    // ========== Streaming Decoder API (zero-disk playback) ==========

    /**
     * 打开流式解码器，返回解码器句柄（0 表示失败）。
     * @param targetSampleRate 目标采样率，0 保持原始
     * @param bitsPerSample    输出比特深度：16 / 24 / 32
     * @param channels         输出声道数
     */
    fun openDecoder(path: String, targetSampleRate: Int, bitsPerSample: Int, channels: Int): Long =
        openDecoder(
            path = path,
            targetSampleRate = targetSampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            headers = emptyMap(),
            userAgent = null,
        )

    /** Opens a streaming decoder with HTTP headers/options for resolved online sources. */
    fun openDecoder(
        path: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
        headers: Map<String, String>,
        userAgent: String?,
    ): Long {
        if (!loaded) {
            appendDebug("openDecoder skipped: bridge not loaded")
            return 0L
        }
        val startedAt = SystemClock.elapsedRealtime()
        val (headerBlock, safeUserAgent) = serializeHttpOptions(headers, userAgent)
        appendDebug(
            "openDecoder path=${shortPath(path)} targetSr=$targetSampleRate bits=$bitsPerSample " +
                "ch=$channels httpHeaders=${headers.size} userAgent=${safeUserAgent.isNotBlank()}"
        )
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} BRIDGE_OPEN_START target=${targetSampleRate}Hz/${bitsPerSample}bit/${channels}ch " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(headers)} ua=${safeUserAgent.isNotBlank()} " +
                "${OnlinePlaybackDiagnostics.urlShape(path)} url=${OnlinePlaybackDiagnostics.safeUrl(path)}"
        )
        val handle = nativeOpenDecoder(
            path,
            targetSampleRate,
            bitsPerSample,
            channels,
            headerBlock,
            safeUserAgent,
        )
        appendDebug("openDecoder result=0x${handle.toString(16)}")
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} BRIDGE_OPEN_END handle=0x${handle.toString(16)} " +
                "success=${handle != 0L} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return handle
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
        if (!loaded) {
            appendDebug("seekDecoder skipped: bridge not loaded")
            return false
        }
        val result = nativeSeekDecoder(handle, positionMs)
        appendDebug("seekDecoder handle=0x${handle.toString(16)} posMs=$positionMs result=$result")
        return result
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
        appendDebug("closeDecoder handle=0x${handle.toString(16)}")
        nativeCloseDecoder(handle)
    }

    fun createVideoCoverSession(fileDescriptor: Int, surface: Surface): Long {
        if (!loaded || fileDescriptor < 0 || !surface.isValid) return 0L
        return nativeCreateVideoCoverSession(fileDescriptor, surface)
    }

    fun createVideoCoverUrlSession(source: String, surface: Surface): Long {
        if (!loaded || source.isBlank() || !surface.isValid) return 0L
        return nativeCreateVideoCoverUrlSession(source, surface)
    }

    fun setVideoCoverSessionActive(handle: Long, active: Boolean) {
        if (!loaded || handle == 0L) return
        nativeSetVideoCoverSessionActive(handle, active)
    }

    fun releaseVideoCoverSession(handle: Long) {
        if (!loaded || handle == 0L) return
        nativeReleaseVideoCoverSession(handle)
    }

    private fun appendDebug(message: String) {
        val line = "[${debugDateFormat.format(Date())}] $message"
        synchronized(debugLock) {
            while (recentDebugEntries.size >= MAX_DEBUG_ENTRIES) {
                recentDebugEntries.removeFirst()
            }
            recentDebugEntries.addLast(line)
        }
    }

    private fun shortPath(path: String?): String {
        if (path.isNullOrBlank()) return "-"
        val normalized = path.replace('\\', '/')
        val name = normalized.substringAfterLast('/', normalized)
        return if (name.isNotBlank()) name else normalized
    }

    private fun serializeHttpOptions(
        headers: Map<String, String>,
        userAgent: String?,
    ): Pair<String, String> {
        val headerBlock = headers.entries.joinToString(separator = "") { (name, value) ->
            val safeName = name.replace("\r", "").replace("\n", "").trim()
            val safeValue = value.replace("\r", "").replace("\n", "").trim()
            if (safeName.isBlank()) "" else "$safeName: $safeValue\r\n"
        }
        val safeUserAgent = userAgent
            ?.replace("\r", "")
            ?.replace("\n", "")
            ?.trim()
            .orEmpty()
        return headerBlock to safeUserAgent
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
    private external fun nativeProbeDurationWithOptions(path: String, headersBlock: String, userAgent: String): Long
    private external fun nativeProbeSampleRateWithOptions(path: String, headersBlock: String, userAgent: String): Int
    private external fun nativeProbeBitsPerSampleWithOptions(path: String, headersBlock: String, userAgent: String): Int
    private external fun nativeProbeChannelCountWithOptions(path: String, headersBlock: String, userAgent: String): Int
    private external fun nativeExtractCover(inputPath: String, outputPath: String): Int
    private external fun nativeGetMediaInfo(filePath: String): Map<String, String>?
    private external fun nativeWriteMetadata(filePath: String, metadata: Map<String, String>, cacheDir: String): Int
    private external fun nativeScanWaveform(path: String, startMs: Long, endMs: Long, sampleCount: Int): FloatArray?

    // Streaming decoder native methods
    private external fun nativeOpenDecoder(
        path: String,
        targetRate: Int,
        targetBits: Int,
        channels: Int,
        headersBlock: String,
        userAgent: String,
    ): Long
    private external fun nativeDecodeChunk(handle: Long, buffer: ByteArray, offset: Int, maxBytes: Int): Int
    private external fun nativeSeekDecoder(handle: Long, positionMs: Long): Boolean
    private external fun nativeGetDecoderSampleRate(handle: Long): Int
    private external fun nativeGetDecoderChannels(handle: Long): Int
    private external fun nativeGetDecoderBitsPerSample(handle: Long): Int
    private external fun nativeGetDecoderDuration(handle: Long): Long
    private external fun nativeCloseDecoder(handle: Long)
    private external fun nativeCreateVideoCoverSession(fileDescriptor: Int, surface: Surface): Long
    private external fun nativeCreateVideoCoverUrlSession(source: String, surface: Surface): Long
    private external fun nativeSetVideoCoverSessionActive(handle: Long, active: Boolean)
    private external fun nativeReleaseVideoCoverSession(handle: Long)
}
