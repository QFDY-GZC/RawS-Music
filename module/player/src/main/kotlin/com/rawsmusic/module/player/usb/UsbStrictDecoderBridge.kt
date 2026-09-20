package com.rawsmusic.module.player.usb

import com.rawsmusic.core.common.utils.AppLogger

/**
 * JNI bridge for the integer-only USB strict decoder hosted by rawscoreservice.
 *
 * This bridge intentionally exposes only decoder primitives. Playback policy and
 * handle routing live one layer above it; Android USB lifecycle never leaks into
 * the decoder implementation.
 */
internal object UsbStrictDecoderBridge {
    private const val TAG = "UsbStrictDecoder"

    private val libraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("rawscoreservice")
            true
        }.getOrElse {
            AppLogger.e(TAG, "Unable to load rawscoreservice for strict USB decoder", it)
            false
        }
    }

    fun open(
        path: String,
        sampleRate: Int,
        validBits: Int,
        channels: Int,
        headersBlock: String,
        userAgent: String,
    ): Long {
        if (!libraryLoaded) return 0L
        return runCatching {
            nativeOpen(path, sampleRate, validBits, channels, headersBlock, userAgent)
        }.getOrElse {
            AppLogger.e(TAG, "native strict decoder open failed", it)
            0L
        }
    }

    fun decode(handle: Long, buffer: ByteArray, offset: Int, maxBytes: Int): Int =
        if (!libraryLoaded || handle == 0L) -2
        else nativeDecode(handle, buffer, offset, maxBytes)

    fun seek(handle: Long, positionMs: Long): Boolean =
        libraryLoaded && handle != 0L && nativeSeek(handle, positionMs)

    fun sampleRate(handle: Long): Int = if (libraryLoaded) nativeGetSampleRate(handle) else 0
    fun channels(handle: Long): Int = if (libraryLoaded) nativeGetChannels(handle) else 0
    fun validBits(handle: Long): Int = if (libraryLoaded) nativeGetValidBits(handle) else 0
    fun durationMs(handle: Long): Long = if (libraryLoaded) nativeGetDurationMs(handle) else 0L

    fun close(handle: Long) {
        if (!libraryLoaded || handle == 0L) return
        nativeClose(handle)
    }

    @JvmStatic private external fun nativeOpen(
        path: String,
        sampleRate: Int,
        validBits: Int,
        channels: Int,
        headersBlock: String,
        userAgent: String,
    ): Long
    @JvmStatic private external fun nativeDecode(handle: Long, buffer: ByteArray, offset: Int, maxBytes: Int): Int
    @JvmStatic private external fun nativeSeek(handle: Long, positionMs: Long): Boolean
    @JvmStatic private external fun nativeGetSampleRate(handle: Long): Int
    @JvmStatic private external fun nativeGetChannels(handle: Long): Int
    @JvmStatic private external fun nativeGetValidBits(handle: Long): Int
    @JvmStatic private external fun nativeGetDurationMs(handle: Long): Long
    @JvmStatic private external fun nativeClose(handle: Long)
}
