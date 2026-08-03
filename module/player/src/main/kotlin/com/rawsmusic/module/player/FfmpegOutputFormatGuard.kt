package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.usb.UsbAudioEngine

/** Keeps decoder/output format validation out of the playback state machine. */
internal class FfmpegOutputFormatGuard(
    private val tag: String,
    private val isUsbExclusive: () -> Boolean,
    private val isStrictBitPerfect: () -> Boolean,
) {
    fun needsKotlinPacked24(
        runtime: UsbAudioEngine.UsbRuntimeFormat,
        decoderBits: Int,
        decoderChannels: Int,
    ): Boolean {
        // Native owns the decoder-container to USB-container conversion. Kotlin must
        // never pack S32 into S24 before native receives the decoder frames.
        if (isUsbExclusive() &&
            decoderBits in 17..32 &&
            decoderChannels > 0 &&
            runtime.isValid &&
            runtime.channels == decoderChannels &&
            runtime.subslotBytes == 3
        ) {
            AppLogger.i(
                tag,
                "USB PCM container conversion delegated to native: " +
                    "decoder=${decoderBits}bit/${decoderChannels}ch -> " +
                    "device=${runtime.validBits}bit/subslot${runtime.subslotBytes}; " +
                    "Kotlin writes decoder frames unchanged",
            )
        }
        return false
    }

    fun verifyBitPerfectDecoderFormat(
        actualRate: Int,
        actualBits: Int,
        actualChannels: Int,
        targetRate: Int,
        targetBits: Int,
        targetChannels: Int,
    ): Boolean {
        if (!isStrictBitPerfect()) return true
        val ok = actualRate == targetRate && actualBits == targetBits && actualChannels == targetChannels
        if (!ok) {
            AppLogger.e(
                tag,
                "USB bit-perfect decoder format mismatch: actual=${actualRate}Hz/${actualBits}bit/${actualChannels}ch " +
                    "target=${targetRate}Hz/${targetBits}bit/${targetChannels}ch",
            )
        }
        return ok
    }
}
