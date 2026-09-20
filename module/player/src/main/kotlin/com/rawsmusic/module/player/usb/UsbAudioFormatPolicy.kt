package com.rawsmusic.module.player.usb

import com.rawsmusic.module.player.AudioOutputManager

/**
 * Pure USB wire-format policy. Device descriptor probing remains native; this class only
 * translates the source format and user output mode into a native auto-selection request.
 */
internal object UsbAudioFormatPolicy {
    fun preferredSubslotFor(
        sourceBits: Int,
        requestedTargetBits: Int,
        pcmMode: UsbPcmOutputMode,
        strictBitPerfect: Boolean,
        pcmDsdActive: Boolean
    ): Int {
        if (sourceBits <= 16) return 2
        if (pcmMode == UsbPcmOutputMode.PCM_24_PACKED) return 3
        if (pcmMode == UsbPcmOutputMode.PCM_24_IN_32 || pcmMode == UsbPcmOutputMode.PCM_32) return 4
        if (sourceBits == 24 &&
            (strictBitPerfect || pcmDsdActive || requestedTargetBits <= 0 ||
                requestedTargetBits == AudioOutputManager.BIT_DEPTH_24)
        ) {
            return 3
        }
        return 4
    }

    fun selectConfigForFormat(
        sampleRate: Int,
        bits: Int,
        subslot: Int,
        channels: Int,
        sourceBits: Int = bits
    ): UsbAudioConfig? {
        if (channels != 2) return null
        return when {
            bits == 16 && subslot == 2 -> autoConfig(sampleRate, 16, channels, 2, sourceBits)
            bits == 24 && subslot == 3 -> autoConfig(sampleRate, 24, channels, 3, sourceBits)
            bits == 24 && subslot == 4 -> autoConfig(sampleRate, 24, channels, 4, sourceBits)
            bits == 32 && subslot == 4 -> autoConfig(sampleRate, 32, channels, 4, sourceBits)
            else -> null
        }
    }

    private fun autoConfig(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        subslot: Int,
        sourceBits: Int
    ): UsbAudioConfig = UsbAudioConfig(
        iface = 0,
        alt = 0,
        outEp = 0,
        fbEp = 0,
        sampleRate = sampleRate,
        bits = bits,
        channels = channels,
        subslot = subslot,
        sourceBits = sourceBits
    )
}
