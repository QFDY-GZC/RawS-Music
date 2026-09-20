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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

object FFmpegBridge {
    private const val TAG = "FFmpegBridge"
    private const val MAX_DEBUG_ENTRIES = 240
    private var loaded = false
    private val debugDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val debugLock = Any()
    private val recentDebugEntries = ArrayDeque<String>()
    private val decoderSpeedStates = ConcurrentHashMap<Long, DecoderSpeedState>()

    private data class RawFdInput(val fd: Int, val size: Long)

    private class DecoderSpeedState(
        val requestedSpeed: Float,
        val requestedBits: Int,
        val changePitch: Boolean,
    ) {
        @Volatile var effectiveSpeed: Float = if (requestedBits <= 1) 1f else requestedSpeed
        @Volatile var processor: SpeedProcessor? = null
        @Volatile var formatResolved = false
    }

    private interface SpeedProcessor {
        val playbackSpeed: Float
        fun reset()
        fun decode(
            destination: ByteArray,
            offset: Int,
            maxBytes: Int,
            nativeRead: (ByteArray, Int) -> Int,
        ): Int
    }

    private class PitchChangingProcessor(
        speed: Float,
        channels: Int,
        bitsPerSample: Int,
    ) : SpeedProcessor {
        private val delegate = PcmPlaybackSpeedDecoder(speed, channels, bitsPerSample)
        override val playbackSpeed: Float get() = delegate.playbackSpeed
        override fun reset() = delegate.reset()
        override fun decode(
            destination: ByteArray,
            offset: Int,
            maxBytes: Int,
            nativeRead: (ByteArray, Int) -> Int,
        ): Int = delegate.decode(destination, offset, maxBytes, nativeRead)
    }

    private class PitchPreservingProcessor(
        speed: Float,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) : SpeedProcessor {
        private val delegate = PcmTimeStretchDecoder(speed, sampleRate, channels, bitsPerSample)
        override val playbackSpeed: Float get() = delegate.playbackSpeed
        override fun reset() = delegate.reset()
        override fun decode(
            destination: ByteArray,
            offset: Int,
            maxBytes: Int,
            nativeRead: (ByteArray, Int) -> Int,
        ): Int = delegate.decode(destination, offset, maxBytes, nativeRead)
    }

    init {
        try {
            // The large codec payload remains in the prebuilt librawsmusic_ffmpeg.so, while the
            // JNI surface is compiled from the current ffmpeg_bridge.cpp on every app build.
            // Loading the live bridge also loads its rawsmusic_ffmpeg dependency automatically.
            System.loadLibrary("rawsmusic_ffmpeg_live")
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
        rawFdInput(path)?.let { return nativeProbeDurationFd(it.fd, it.size) }
        return nativeProbeDuration(path)
    }

    fun probeDuration(path: String, headers: Map<String, String>, userAgent: String?): Long {
        if (!loaded) return 0L
        val startedAt = SystemClock.elapsedRealtime()
        rawFdInput(path)?.let { input ->
            val result = nativeProbeDurationFd(input.fd, input.size)
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} PROBE_FD kind=duration result=$result " +
                    "fd=${input.fd} size=${input.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return result
        }
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
        rawFdInput(path)?.let { return nativeProbeSampleRateFd(it.fd, it.size) }
        val result = nativeProbeSampleRate(path)
        appendDebug("probeSampleRate ${shortPath(path)} -> $result")
        return result
    }

    fun probeSampleRate(path: String, headers: Map<String, String>, userAgent: String?): Int {
        if (!loaded) return 0
        val startedAt = SystemClock.elapsedRealtime()
        rawFdInput(path)?.let { input ->
            val result = nativeProbeSampleRateFd(input.fd, input.size)
            appendDebug("probeSampleRate fd=${input.fd} -> $result")
            return result
        }
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
        rawFdInput(path)?.let { return nativeProbeBitsPerSampleFd(it.fd, it.size) }
        val result = nativeProbeBitsPerSample(path)
        appendDebug("probeBitsPerSample ${shortPath(path)} -> $result")
        return result
    }

    fun probeBitsPerSample(path: String, headers: Map<String, String>, userAgent: String?): Int {
        if (!loaded) return 0
        val startedAt = SystemClock.elapsedRealtime()
        rawFdInput(path)?.let { input ->
            val result = nativeProbeBitsPerSampleFd(input.fd, input.size)
            appendDebug("probeBitsPerSample fd=${input.fd} -> $result")
            return result
        }
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
        rawFdInput(path)?.let { return nativeProbeChannelCountFd(it.fd, it.size) }
        val result = nativeProbeChannelCount(path)
        appendDebug("probeChannelCount ${shortPath(path)} -> $result")
        return result
    }

    fun probeChannelCount(path: String, headers: Map<String, String>, userAgent: String?): Int {
        if (!loaded) return 0
        val startedAt = SystemClock.elapsedRealtime()
        rawFdInput(path)?.let { input ->
            val result = nativeProbeChannelCountFd(input.fd, input.size)
            appendDebug("probeChannelCount fd=${input.fd} -> $result")
            return result
        }
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
        rawFdInput(inputPath)?.let { return nativeExtractCoverFd(it.fd, it.size, outputPath) }
        return nativeExtractCover(inputPath, outputPath, "", "")
    }

    fun extractCover(
        inputPath: String,
        outputPath: String,
        headers: Map<String, String>,
        userAgent: String?,
    ): Int {
        if (!loaded) return -1
        rawFdInput(inputPath)?.let { return nativeExtractCoverFd(it.fd, it.size, outputPath) }
        val options = serializeHttpOptions(headers, userAgent)
        return nativeExtractCover(inputPath, outputPath, options.first, options.second)
    }

    fun getMediaInfo(filePath: String): Map<String, String>? {
        if (!loaded) return null
        rawFdInput(filePath)?.let { return nativeGetMediaInfoFd(it.fd, it.size) }
        return nativeGetMediaInfo(filePath, "", "")
    }

    fun getMediaInfo(
        filePath: String,
        headers: Map<String, String>,
        userAgent: String?,
    ): Map<String, String>? {
        if (!loaded) return null
        rawFdInput(filePath)?.let { return nativeGetMediaInfoFd(it.fd, it.size) }
        val options = serializeHttpOptions(headers, userAgent)
        return nativeGetMediaInfo(filePath, options.first, options.second)
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
        openDecoder(path, targetSampleRate, bitsPerSample, channels, 1f)

    /** Opens a local streaming decoder with app-owned variable-speed PCM. */
    fun openDecoder(
        path: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
        playbackSpeed: Float,
        changePitch: Boolean = false,
    ): Long =
        openDecoder(
            path = path,
            targetSampleRate = targetSampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            headers = emptyMap(),
            userAgent = null,
            playbackSpeed = playbackSpeed,
            changePitch = changePitch,
        )

    /** Opens a streaming decoder with HTTP headers/options for resolved online sources. */
    fun openDecoder(
        path: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
        headers: Map<String, String>,
        userAgent: String?,
        playbackSpeed: Float = 1f,
        changePitch: Boolean = false,
    ): Long {
        if (!loaded) {
            appendDebug("openDecoder skipped: bridge not loaded")
            return 0L
        }
        val startedAt = SystemClock.elapsedRealtime()
        val (headerBlock, safeUserAgent) = serializeHttpOptions(headers, userAgent)
        val safePlaybackSpeed = if (playbackSpeed.isFinite()) playbackSpeed.coerceIn(0.25f, 3f) else 1f
        appendDebug(
            "openDecoder path=${shortPath(path)} targetSr=$targetSampleRate bits=$bitsPerSample " +
                "ch=$channels speed=${"%.2f".format(safePlaybackSpeed)} changePitch=$changePitch " +
                "httpHeaders=${headers.size} userAgent=${safeUserAgent.isNotBlank()}"
        )
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} BRIDGE_OPEN_START target=${targetSampleRate}Hz/${bitsPerSample}bit/${channels}ch " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(headers)} ua=${safeUserAgent.isNotBlank()} " +
                "${OnlinePlaybackDiagnostics.urlShape(path)} url=${OnlinePlaybackDiagnostics.safeUrl(path)}"
        )
        // Variable-rate PCM stays app-owned in this Kotlin bridge; native decoding remains at
        // 1.00x. Unlike the historical prebuilt JNI bridge, rawsmusic_ffmpeg_live is compiled
        // from the matching source in this build, so HTTP headers/User-Agent and the full JNI
        // signature are ABI-checked by the normal native build.
        val rawFd = rawFdInput(path)
        val handle = if (rawFd != null) {
            nativeOpenDecoderFd(
                rawFd.fd,
                rawFd.size,
                targetSampleRate,
                bitsPerSample,
                channels,
                1f,
            )
        } else {
            nativeOpenDecoder(
                path,
                targetSampleRate,
                bitsPerSample,
                channels,
                headerBlock,
                safeUserAgent,
                1f,
            )
        }
        if (handle != 0L) {
            decoderSpeedStates[handle] = DecoderSpeedState(
                requestedSpeed = safePlaybackSpeed,
                requestedBits = bitsPerSample,
                changePitch = changePitch,
            )
        }
        appendDebug(
            "openDecoder result=0x${handle.toString(16)} requestedSpeed=${"%.2f".format(safePlaybackSpeed)} " +
                "changePitch=$changePitch rateOwner=${if (kotlin.math.abs(safePlaybackSpeed - 1f) > 0.0001f) "bridge_pcm" else "identity"}"
        )
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
        val state = decoderSpeedStates[handle]
        if (state == null || kotlin.math.abs(state.effectiveSpeed - 1f) <= 0.0001f) {
            return nativeDecodeChunk(handle, buffer, offset, maxBytes)
        }
        val processor = ensureSpeedProcessor(handle, state)
            ?: return nativeDecodeChunk(handle, buffer, offset, maxBytes)
        return synchronized(state) {
            processor.decode(buffer, offset, maxBytes) { scratch, requested ->
                nativeDecodeChunk(handle, scratch, 0, requested)
            }
        }
    }

    /**
     * Seek 到指定位置（毫秒）。
     */
    fun seekDecoder(handle: Long, positionMs: Long): Boolean {
        if (!loaded) {
            appendDebug("seekDecoder skipped: bridge not loaded")
            return false
        }
        val state = decoderSpeedStates[handle]
        val effectiveSpeed = state?.effectiveSpeed ?: 1f
        val sourcePositionMs = if (kotlin.math.abs(effectiveSpeed - 1f) <= 0.0001f) {
            positionMs
        } else {
            (positionMs.coerceAtLeast(0L).toDouble() * effectiveSpeed.toDouble())
                .roundToLong()
                .coerceAtLeast(0L)
        }
        val result = if (state != null) {
            synchronized(state) {
                val ok = nativeSeekDecoder(handle, sourcePositionMs)
                if (ok) state.processor?.reset()
                ok
            }
        } else {
            nativeSeekDecoder(handle, sourcePositionMs)
        }
        appendDebug(
            "seekDecoder handle=0x${handle.toString(16)} playbackMs=$positionMs " +
                "sourceMs=$sourcePositionMs speed=${"%.2f".format(effectiveSpeed)} result=$result"
        )
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
        val bits = nativeGetDecoderBitsPerSample(handle)
        decoderSpeedStates[handle]?.let { state ->
            if (bits <= 1 && state.effectiveSpeed != 1f) {
                synchronized(state) {
                    state.effectiveSpeed = 1f
                    state.processor = null
                    state.formatResolved = true
                }
                appendDebug(
                    "playbackSpeed clamped handle=0x${handle.toString(16)} reason=raw_dsd requested=${state.requestedSpeed}"
                )
            }
        }
        return bits
    }

    fun getDecoderDuration(handle: Long): Long {
        if (!loaded) return 0L
        val nativeDurationMs = nativeGetDecoderDuration(handle)
        val speed = decoderSpeedStates[handle]?.effectiveSpeed ?: 1f
        return if (nativeDurationMs <= 0L || kotlin.math.abs(speed - 1f) <= 0.0001f) {
            nativeDurationMs
        } else {
            (nativeDurationMs.toDouble() / speed.toDouble()).roundToLong().coerceAtLeast(1L)
        }
    }

    fun getDecoderPlaybackSpeed(handle: Long): Float =
        decoderSpeedStates[handle]?.effectiveSpeed ?: 1f

    fun closeDecoder(handle: Long) {
        if (!loaded || handle == 0L) return
        decoderSpeedStates.remove(handle)
        appendDebug("closeDecoder handle=0x${handle.toString(16)}")
        nativeCloseDecoder(handle)
    }

    private fun ensureSpeedProcessor(handle: Long, state: DecoderSpeedState): SpeedProcessor? {
        state.processor?.let { return it }
        return synchronized(state) {
            state.processor?.let { return@synchronized it }
            if (kotlin.math.abs(state.effectiveSpeed - 1f) <= 0.0001f) return@synchronized null
            val channels = nativeGetDecoderChannels(handle).coerceAtLeast(1)
            val bits = nativeGetDecoderBitsPerSample(handle)
            if (bits <= 1) {
                state.effectiveSpeed = 1f
                state.formatResolved = true
                appendDebug(
                    "playbackSpeed clamped handle=0x${handle.toString(16)} reason=raw_dsd requested=${state.requestedSpeed}"
                )
                return@synchronized null
            }
            val created: SpeedProcessor = if (state.changePitch) {
                PitchChangingProcessor(
                    speed = state.effectiveSpeed,
                    channels = channels,
                    bitsPerSample = bits,
                )
            } else {
                PitchPreservingProcessor(
                    speed = state.effectiveSpeed,
                    sampleRate = nativeGetDecoderSampleRate(handle).coerceAtLeast(8_000),
                    channels = channels,
                    bitsPerSample = bits,
                )
            }
            state.processor = created
            state.formatResolved = true
            appendDebug(
                "playbackSpeed processor handle=0x${handle.toString(16)} speed=${"%.2f".format(created.playbackSpeed)} " +
                    "mode=${if (state.changePitch) "rate_pitch" else "wsola_pitch_preserving"} bits=$bits channels=$channels"
            )
            created
        }
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

    private fun rawFdInput(path: String): RawFdInput? {
        if (!path.startsWith("rawfd://", ignoreCase = true)) return null
        val fd = path.substringAfter("rawfd://")
            .substringBefore('?')
            .toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        val query = path.substringAfter('?', "")
        val size = query.split('&')
            .firstOrNull { it.startsWith("size=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
        return RawFdInput(fd = fd, size = size)
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
    private external fun nativeProbeDurationFd(fd: Int, size: Long): Long
    private external fun nativeProbeSampleRateFd(fd: Int, size: Long): Int
    private external fun nativeProbeBitsPerSampleFd(fd: Int, size: Long): Int
    private external fun nativeProbeChannelCountFd(fd: Int, size: Long): Int
    private external fun nativeExtractCover(
        inputPath: String,
        outputPath: String,
        headersBlock: String,
        userAgent: String,
    ): Int
    private external fun nativeExtractCoverFd(fd: Int, size: Long, outputPath: String): Int
    private external fun nativeGetMediaInfo(
        filePath: String,
        headersBlock: String,
        userAgent: String,
    ): Map<String, String>?
    private external fun nativeGetMediaInfoFd(fd: Int, size: Long): Map<String, String>?
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
        playbackSpeed: Float,
    ): Long
    private external fun nativeOpenDecoderFd(
        fd: Int,
        size: Long,
        targetRate: Int,
        targetBits: Int,
        channels: Int,
        playbackSpeed: Float,
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
