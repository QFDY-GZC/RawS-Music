package com.rawsmusic.module.player.usb

import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.delay

/**
 * Thin Kotlin bridge for USB transition envelopes.
 *
 * Native playback mode + DSD transport are authoritative for gain ownership. Kotlin only asks for
 * a target/duration and optionally waits for an accepted envelope. This keeps strict bit-perfect,
 * hardware-volume, fixed-output and DSD rules out of the Android orchestration layer.
 */
internal class UsbTransitionGainCoordinator(
    private val tag: String,
    private val currentHandle: () -> Long,
    private val isSessionEnvelopeAllowed: (handle: Long) -> Boolean,
    private val applySessionTransition: (handle: Long, target: Float, fadeMs: Int, reason: String) -> Boolean,
) {
    fun canUseSessionEnvelope(): Boolean {
        val handle = currentHandle()
        return handle != 0L && isSessionEnvelopeAllowed(handle)
    }

    /** Arm mutable PCM at zero before nativeStart; native rejects unity-only/raw routes. */
    fun armBeforeNativeStart(reason: String): Boolean {
        val handle = currentHandle()
        if (handle == 0L) {
            AppLogger.w(tag, "USB_SESSION_GAIN_SKIP handle=0 reason=$reason")
            return false
        }
        val accepted = applySessionTransition(handle, 0.0f, 0, reason)
        AppLogger.i(tag, "USB_SESSION_GAIN_ARM accepted=$accepted reason=$reason")
        return accepted
    }

    /** First accepted PCM data fades the native session envelope to unity when allowed. */
    fun onFirstData(
        fadeMs: Int,
        reason: String,
    ): Boolean {
        val handle = currentHandle()
        if (handle == 0L) return false
        val accepted = applySessionTransition(handle, 1.0f, fadeMs.coerceAtLeast(0), reason)
        AppLogger.i(tag, "USB_SESSION_GAIN_FIRST_DATA accepted=$accepted fadeMs=$fadeMs reason=$reason")
        return accepted
    }

    suspend fun fadeTo(
        target: Float,
        fadeMs: Int,
        reason: String,
        waitForEnvelope: Boolean,
    ): Boolean {
        val bounded = fadeMs.coerceIn(0, 15_000)
        if (bounded <= 0) return false
        val handle = currentHandle()
        if (handle == 0L) {
            AppLogger.w(tag, "USB_SESSION_FADE_SKIP handle=0 reason=$reason")
            return false
        }
        val safeTarget = target.coerceIn(0f, 1f)
        val accepted = applySessionTransition(handle, safeTarget, bounded, reason)
        if (!accepted) {
            AppLogger.i(tag, "USB_SESSION_FADE_SKIP native_contract target=$safeTarget fadeMs=$bounded reason=$reason")
            return false
        }
        AppLogger.i(tag, "USB_SESSION_FADE target=$safeTarget fadeMs=$bounded reason=$reason")
        if (waitForEnvelope) delay(bounded.toLong())
        return true
    }

    fun clearToUnity(reason: String) {
        val handle = currentHandle()
        if (handle == 0L) return
        val accepted = applySessionTransition(handle, 1.0f, 0, reason)
        AppLogger.i(tag, "USB_SESSION_GAIN_CLEAR accepted=$accepted reason=$reason")
    }
}
