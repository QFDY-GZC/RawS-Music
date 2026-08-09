package com.rawsmusic.module.player.usb

/** Source audio format before the USB transport is selected. */
data class UsbAudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int
)

/** Native-facing USB stream selection. Zero endpoint fields mean auto-select in native. */
data class UsbAudioConfig(
    val iface: Int,
    val alt: Int,
    val outEp: Int,
    val fbEp: Int,
    val sampleRate: Int,
    val bits: Int,
    val channels: Int,
    val subslot: Int,
    val sourceBits: Int = bits
) {
    val frameSize: Int get() = channels * subslot
}
