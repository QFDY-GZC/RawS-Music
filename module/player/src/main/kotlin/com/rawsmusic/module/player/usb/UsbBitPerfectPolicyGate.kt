package com.rawsmusic.module.player.usb

import com.rawsmusic.core.common.utils.AppLogger

/** Keeps strict bit-perfect decisions and one-shot diagnostic logging together. */
internal class UsbBitPerfectPolicyGate(
    private val isUsbExclusive: () -> Boolean,
    private val isBitPerfectEnabled: () -> Boolean,
    private val isStrictForCurrentTrack: () -> Boolean,
    private val tag: String,
) {
    @Volatile
    private var bypassMask = 0L

    fun isStrictPath(): Boolean =
        isUsbExclusive() && isBitPerfectEnabled() && isStrictForCurrentTrack()

    fun logBypassOnce(bit: Long, reason: String) {
        if ((bypassMask and bit) != 0L) return
        bypassMask = bypassMask or bit
        AppLogger.i(tag, "USB bit-perfect policy: bypass PCM mutator '$reason'")
    }

    fun resetLog() {
        bypassMask = 0L
    }
}
