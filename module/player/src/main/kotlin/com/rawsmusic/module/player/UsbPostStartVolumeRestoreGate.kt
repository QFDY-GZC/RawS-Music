package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot gate for re-applying the USB hardware volume route after nativeStart.
 */
internal class UsbPostStartVolumeRestoreGate(
    private val tag: String,
    private val repairVolumeRoute: () -> Unit
) {
    private val pending = AtomicBoolean(false)

    fun arm(reason: String) {
        pending.set(true)
        AppLogger.i(tag, "armUsbPostStartVolumeRestore: reason=$reason")
    }

    fun maybeRestore(reason: String, nativeBuffered: Int) {
        if (!pending.compareAndSet(true, false)) return
        AppLogger.i(tag, "maybeRestoreUsbVolumeRoute: reason=$reason nativeBuffered=$nativeBuffered")
        try {
            repairVolumeRoute()
        } catch (t: Throwable) {
            AppLogger.w(tag, "maybeRestoreUsbVolumeRoute callback failed", t)
        }
    }

    fun clear(reason: String) {
        if (pending.getAndSet(false)) {
            AppLogger.i(tag, "clearUsbPostStartVolumeRestore: reason=$reason")
        }
    }
}
