package com.rawsmusic.module.player.usb

import com.rawsmusic.core.common.utils.AppLogger

/**
 * Optional USB HID lifecycle bridge. The native engine owns HID I/O; this class
 * only coordinates safe start/stop calls around the current USB session.
 */
internal object UsbHidSession {
    private const val TAG = "UsbExclusiveManager"

    fun start(): Boolean {
        return try {
            if (!UsbAudioEngine.hasHidInterface()) {
                AppLogger.d(TAG, "Device does not have HID interface")
                return false
            }
            AppLogger.i(TAG, "Device has HID interface, starting HID listening")
            val started = UsbAudioEngine.startHidListening()
            if (started) {
                AppLogger.i(TAG, "HID listening started successfully")
            } else {
                AppLogger.w(TAG, "Failed to start HID listening")
            }
            started
        } catch (t: Throwable) {
            AppLogger.w(TAG, "HID initialization failed", t)
            false
        }
    }

    fun stop() {
        try {
            if (UsbAudioEngine.isHidListening()) {
                AppLogger.i(TAG, "Stopping HID listening")
                UsbAudioEngine.stopHidListening()
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to stop HID listening", t)
        }
    }

    fun hasInterface(): Boolean = runCatching { UsbAudioEngine.hasHidInterface() }.getOrDefault(false)

    fun isListening(): Boolean = runCatching { UsbAudioEngine.isHidListening() }.getOrDefault(false)
}
