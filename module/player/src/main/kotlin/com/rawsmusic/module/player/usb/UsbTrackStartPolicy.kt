package com.rawsmusic.module.player.usb

/** Pure format request used by the same-profile track-start fast path. */
internal object UsbTrackStartPolicy {
    fun selectConfig(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        requestedTargetBits: Int,
        pcmMode: UsbPcmOutputMode,
        strictBitPerfect: Boolean,
        pcmDsdActive: Boolean,
    ): UsbAudioConfig? {
        val sourceBits = bits.coerceAtMost(32)
        val subslot = UsbAudioFormatPolicy.preferredSubslotFor(
            sourceBits = sourceBits,
            requestedTargetBits = requestedTargetBits,
            pcmMode = pcmMode,
            strictBitPerfect = strictBitPerfect,
            pcmDsdActive = pcmDsdActive,
        )
        return UsbAudioFormatPolicy.selectConfigForFormat(
            sampleRate = sampleRate,
            bits = sourceBits,
            subslot = subslot,
            channels = channels,
            sourceBits = sourceBits,
        )
    }
}
