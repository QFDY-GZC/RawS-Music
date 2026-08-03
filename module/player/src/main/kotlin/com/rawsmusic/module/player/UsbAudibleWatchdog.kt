package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbAudibleState
import kotlin.concurrent.thread

/** Verifies that a newly started USB stream becomes audible without owning playback. */
internal class UsbAudibleWatchdog(
    private val isUsbExclusive: () -> Boolean,
    private val isReleased: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val isSerialCurrent: (Long) -> Boolean,
    private val onPlaybackDataFlowing: () -> Unit,
    private val tag: String,
) {
    fun arm(serial: Long, reason: String) {
        val checksMs = longArrayOf(180L, 420L, 900L, 1500L)
        thread(name = "UsbAudibleColdStartWatchdog", isDaemon = true) {
            var elapsed = 0L
            for (delayMs in checksMs) {
                val sleepMs = (delayMs - elapsed).coerceAtLeast(0L)
                if (sleepMs > 0L) Thread.sleep(sleepMs)
                elapsed = delayMs
                if (!isUsbExclusive() || isReleased() || !isPlaying() || !isSerialCurrent(serial)) {
                    AppLogger.i(tag, "USB audible watchdog exit: stale reason=$reason elapsed=${elapsed}ms serial=$serial")
                    return@thread
                }
                val handle = UsbAudioEngine.currentHandle
                if (handle == 0L || !UsbAudioEngine.isRunning()) {
                    AppLogger.w(tag, "USB audible watchdog skip: no running handle reason=$reason elapsed=${elapsed}ms")
                    continue
                }
                val state = runCatching { UsbAudioEngine.nativeGetAudibleStateString(handle) }.getOrDefault("")
                AppLogger.i(tag, "USB audible watchdog: reason=$reason elapsed=${elapsed}ms state=$state")
                if (UsbAudibleState.accepted(state)) return@thread

                // Native may already receive ISO payload while its audible gate is
                // waiting for the Kotlin-owned hardware-volume route to be restored.
                if (elapsed >= 420L || UsbAudibleState.needsVolumeRepair(state)) {
                    try {
                        AppLogger.w(tag, "USB audible watchdog repairing volume route: reason=$reason elapsed=${elapsed}ms state=$state")
                        onPlaybackDataFlowing()
                        Thread.sleep(80L)
                        if (!isUsbExclusive() || isReleased() || !isPlaying() ||
                            !isSerialCurrent(serial) || UsbAudioEngine.currentHandle != handle
                        ) {
                            AppLogger.i(tag, "USB audible watchdog repair result ignored: stale session reason=$reason serial=$serial")
                            return@thread
                        }
                        val after = runCatching { UsbAudioEngine.nativeGetAudibleStateString(handle) }.getOrDefault("")
                        AppLogger.i(tag, "USB audible watchdog after repair: reason=$reason state=$after")
                        if (UsbAudibleState.accepted(after)) return@thread
                    } catch (t: Throwable) {
                        AppLogger.w(tag, "USB audible watchdog repair failed", t)
                    }
                }
            }
        }
    }
}
