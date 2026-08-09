package com.rawsmusic.module.player.usb

import com.rawsmusic.core.common.utils.AppLogger

/** Optional JNI bridge for the native USB background guardian. */
internal object UsbBackgroundNativeGuard {
    private const val TAG = "UsbAudioEngine"

    @Volatile
    private var available = true

    fun isAvailable(nativeLoaded: Boolean): Boolean = nativeLoaded && available

    fun markUnavailable() {
        available = false
    }

    fun setActive(
        nativeLoaded: Boolean,
        active: Boolean,
        reason: String,
        applyNative: () -> Unit,
    ): Boolean {
        if (!isAvailable(nativeLoaded)) return false
        return try {
            applyNative()
            true
        } catch (e: UnsatisfiedLinkError) {
            available = false
            AppLogger.w(
                TAG,
                "USB background native guard unavailable at setBackgroundPlaybackActive($active,$reason); " +
                    "native ColorOS/Hans guard disabled, Java foreground service/media identity/wakelock protection remains active",
                e,
            )
            false
        } catch (t: Throwable) {
            AppLogger.w(TAG, "nativeSetBackgroundPlaybackActive($active) failed: reason=$reason", t)
            false
        }
    }
}
