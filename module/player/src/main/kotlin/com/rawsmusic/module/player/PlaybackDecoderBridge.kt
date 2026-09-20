package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.usb.UsbStrictDecoderBridge

/**
 * Single playback-decoder owner for the player module.
 *
 * Ordinary playback remains on the existing FFmpegBridge decoder. Effective USB
 * strict bit-perfect sessions receive a tagged handle backed by the native
 * integer-only decoder in rawscoreservice. Callers do not branch on decoder type.
 */
internal object PlaybackDecoderBridge {
    private const val TAG = "PlaybackDecoderBridge"
    private const val STRICT_HANDLE_TAG: Long = 1L shl 62
    private const val STRICT_HANDLE_MASK: Long = STRICT_HANDLE_TAG - 1L

    fun openDecoder(
        path: String,
        targetSampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
        playbackSpeed: Float = 1f,
        changePitch: Boolean = false,
        strictUsbBitPerfect: Boolean = false,
    ): Long {
        if (!strictUsbBitPerfect) {
            return FFmpegBridge.openDecoder(
                path = path,
                targetSampleRate = targetSampleRate,
                bitsPerSample = bitsPerSample,
                channels = channels,
                headers = headers,
                userAgent = userAgent,
                playbackSpeed = playbackSpeed,
                changePitch = changePitch,
            )
        }
        if (kotlin.math.abs(playbackSpeed - 1f) > 0.0001f) {
            AppLogger.e(TAG, "strict USB decoder refused non-unity playback speed=$playbackSpeed")
            return 0L
        }
        val nativeHandle = UsbStrictDecoderBridge.open(
            path = path,
            sampleRate = targetSampleRate,
            validBits = bitsPerSample,
            channels = channels,
            headersBlock = serializeHeaders(headers),
            userAgent = userAgent.orEmpty(),
        )
        if (nativeHandle <= 0L || nativeHandle > STRICT_HANDLE_MASK) {
            if (nativeHandle != 0L) {
                runCatching { UsbStrictDecoderBridge.close(nativeHandle) }
            }
            return 0L
        }
        val routedHandle = STRICT_HANDLE_TAG or nativeHandle
        AppLogger.i(
            TAG,
            "strict USB integer decoder opened handle=0x${routedHandle.toString(16)} " +
                "format=${targetSampleRate}Hz/${bitsPerSample}bit/${channels}ch",
        )
        return routedHandle
    }

    fun decodeChunk(handle: Long, buffer: ByteArray, offset: Int, maxBytes: Int): Int =
        if (isStrictHandle(handle)) {
            UsbStrictDecoderBridge.decode(strictNativeHandle(handle), buffer, offset, maxBytes)
        } else {
            FFmpegBridge.decodeChunk(handle, buffer, offset, maxBytes)
        }

    fun seekDecoder(handle: Long, positionMs: Long): Boolean =
        if (isStrictHandle(handle)) {
            UsbStrictDecoderBridge.seek(strictNativeHandle(handle), positionMs)
        } else {
            FFmpegBridge.seekDecoder(handle, positionMs)
        }

    fun getDecoderSampleRate(handle: Long): Int =
        if (isStrictHandle(handle)) UsbStrictDecoderBridge.sampleRate(strictNativeHandle(handle))
        else FFmpegBridge.getDecoderSampleRate(handle)

    fun getDecoderChannels(handle: Long): Int =
        if (isStrictHandle(handle)) UsbStrictDecoderBridge.channels(strictNativeHandle(handle))
        else FFmpegBridge.getDecoderChannels(handle)

    fun getDecoderBitsPerSample(handle: Long): Int =
        if (isStrictHandle(handle)) UsbStrictDecoderBridge.validBits(strictNativeHandle(handle))
        else FFmpegBridge.getDecoderBitsPerSample(handle)

    fun getDecoderDuration(handle: Long): Long =
        if (isStrictHandle(handle)) UsbStrictDecoderBridge.durationMs(strictNativeHandle(handle))
        else FFmpegBridge.getDecoderDuration(handle)

    fun getDecoderPlaybackSpeed(handle: Long): Float =
        if (isStrictHandle(handle)) 1f else FFmpegBridge.getDecoderPlaybackSpeed(handle)

    fun closeDecoder(handle: Long) {
        if (handle == 0L) return
        if (isStrictHandle(handle)) {
            UsbStrictDecoderBridge.close(strictNativeHandle(handle))
        } else {
            FFmpegBridge.closeDecoder(handle)
        }
    }

    internal fun isStrictHandle(handle: Long): Boolean =
        handle != 0L && (handle and STRICT_HANDLE_TAG) != 0L

    private fun strictNativeHandle(handle: Long): Long = handle and STRICT_HANDLE_MASK

    private fun serializeHeaders(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        return buildString {
            headers.forEach { (name, value) ->
                val safeName = name.replace("\r", "").replace("\n", "")
                val safeValue = value.replace("\r", "").replace("\n", "")
                append(safeName).append(": ").append(safeValue).append("\r\n")
            }
        }
    }
}
