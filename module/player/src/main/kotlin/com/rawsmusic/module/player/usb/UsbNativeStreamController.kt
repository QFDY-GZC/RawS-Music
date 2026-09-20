package com.rawsmusic.module.player.usb

import android.util.Log
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Kotlin boundary for the native USB stream session.
 *
 * This class does not own the handle or the native transport. It only keeps
 * the ordering and error policy for operations that act on an already opened
 * session. Descriptor selection, volume, DSD and the write loop deliberately
 * stay outside this module.
 */
internal class UsbNativeStreamController(
    private val currentHandle: () -> Long,
    private val isInitialized: () -> Boolean,
    private val isSessionBroken: () -> Boolean,
    private val setSessionBroken: (Boolean) -> Unit,
    private val refreshRuntimeSnapshot: () -> Unit,
    private val getStreamSessionId: () -> Long,
    private val nativeStart: (Long) -> Boolean,
    private val nativePause: (Long) -> Unit,
    private val nativeStopAndFlush: (Long) -> Unit,
    private val nativeFlushForNextTrack: (Long) -> Boolean,
    private val nativeRestartIsoTransfersSameProfile: (Long) -> Boolean,
    private val nativeResetSessionForPlayback: (Long) -> Unit,
    private val nativeCloseStreamForReconfigure: (Long) -> Unit,
    private val nativeEnterStandby: (Long) -> Unit,
    private val nativeResumeFromStandby: (Long) -> Boolean,
    private val nativeIsSessionBroken: (Long) -> Boolean,
    private val nativeIsActive: () -> Boolean,
) {
    @Volatile
    private var lastNextTrackFlushMs: Long = 0L

    @Volatile
    private var lastNextTrackFlushReason: String = ""

    fun start(): Boolean {
        val handle = currentHandle()
        if (handle == 0L || !isInitialized()) {
            AppLogger.e(
                TAG,
                "Cannot start: not initialized, handle=0x${java.lang.Long.toUnsignedString(handle, 16)} " +
                    "initialized=${isInitialized()}"
            )
            return false
        }
        if (isSessionBroken()) {
            AppLogger.e(TAG, "start denied: native session broken, full reopen required")
            return false
        }
        val result = nativeStart(handle)
        if (!result) {
            AppLogger.e(TAG, "nativeStart failed, marking session broken")
            setSessionBroken(true)
        } else {
            refreshRuntimeSnapshot()
            AppLogger.i(TAG, "Streaming started")
        }
        return result
    }

    fun pause() {
        val handle = currentHandle()
        if (handle == 0L || !isInitialized()) {
            AppLogger.i(
                TAG,
                "pause() ignored: handle=0x${java.lang.Long.toUnsignedString(handle, 16)} " +
                    "initialized=${isInitialized()}"
            )
            return
        }
        AppLogger.i(TAG, "pause() calling nativePause, handle=0x${java.lang.Long.toUnsignedString(handle, 16)}")
        nativePause(handle)
        AppLogger.i(TAG, "Streaming paused (buffer preserved)")
    }

    fun isActive(): Boolean {
        if (!isInitialized()) return false
        return nativeIsActive()
    }

    fun restartIsoTransfersSameProfile(reason: String): Boolean {
        val handle = currentHandle()
        if (handle == 0L || !isInitialized()) {
            AppLogger.w(
                TAG,
                "restartIsoTransfersSameProfile skipped: reason=$reason handle=0x${handle.toString(16)} " +
                    "initialized=${isInitialized()}"
            )
            return false
        }
        AppLogger.w(TAG, "restartIsoTransfersSameProfile: reason=$reason handle=0x${handle.toString(16)}")
        return try {
            val ok = nativeRestartIsoTransfersSameProfile(handle)
            if (ok) {
                setSessionBroken(false)
                refreshRuntimeSnapshot()
                AppLogger.i(TAG, "restartIsoTransfersSameProfile ok: reason=$reason session=${getStreamSessionId()}")
            } else {
                AppLogger.w(TAG, "restartIsoTransfersSameProfile native returned false: reason=$reason")
            }
            ok
        } catch (t: Throwable) {
            AppLogger.w(TAG, "restartIsoTransfersSameProfile failed: reason=$reason", t)
            false
        }
    }

    fun flushForNextTrack(reason: String): Boolean {
        val handle = currentHandle()
        val now = android.os.SystemClock.elapsedRealtime()
        if (reason == "prepareForPlayback_fast_reuse_same_config" &&
            now - lastNextTrackFlushMs in 0..650 &&
            lastNextTrackFlushReason.contains("manual", ignoreCase = true)
        ) {
            val message =
                "flushForNextTrack skipped duplicate fast-reuse flush: reason=$reason " +
                    "lastReason=$lastNextTrackFlushReason age=${now - lastNextTrackFlushMs}ms"
            AppLogger.w(TAG, message)
            Log.w(TAG, message)
            return true
        }
        lastNextTrackFlushMs = now
        lastNextTrackFlushReason = reason
        AppLogger.i(TAG, "flushForNextTrack: reason=$reason handle=0x${handle.toString(16)}")
        if (handle == 0L) return false
        return runCatching { nativeFlushForNextTrack(handle) }
            .onFailure { AppLogger.e(TAG, "flushForNextTrack JNI failed: reason=$reason", it) }
            .getOrDefault(false)
    }

    fun resetSessionForPlayback(reason: String) {
        val handle = currentHandle()
        AppLogger.i(TAG, "resetSessionForPlayback: reason=$reason handle=0x${handle.toString(16)}")
        if (handle != 0L) nativeResetSessionForPlayback(handle)
    }

    fun closeStreamForReconfigure(reason: String) {
        val handle = currentHandle()
        AppLogger.i(TAG, "closeStreamForReconfigure(legacy-recovery): reason=$reason handle=0x${handle.toString(16)}")
        if (handle != 0L) nativeCloseStreamForReconfigure(handle)
    }

    fun enterStandby(reason: String) {
        val handle = currentHandle()
        AppLogger.i(TAG, "enterStandby: reason=$reason handle=0x${handle.toString(16)}")
        if (handle != 0L) nativeEnterStandby(handle)
    }

    fun resumeFromStandby(reason: String): Boolean {
        val handle = currentHandle()
        AppLogger.i(TAG, "resumeFromStandby: reason=$reason handle=0x${handle.toString(16)}")
        if (handle == 0L) return false
        return nativeResumeFromStandby(handle)
    }

    fun hardStopUsb(reason: String) {
        val handle = currentHandle()
        AppLogger.w(
            TAG,
            "hardStopUsb: reason=$reason handle=0x${handle.toString(16)}",
            Throwable("hardStopUsb call stack")
        )
        if (handle != 0L) nativeStopAndFlush(handle)
    }

    fun isNativeSessionBroken(): Boolean {
        val handle = currentHandle()
        if (handle == 0L) return true
        return nativeIsSessionBroken(handle)
    }

    private companion object {
        const val TAG = "UsbNativeStreamController"
    }
}
