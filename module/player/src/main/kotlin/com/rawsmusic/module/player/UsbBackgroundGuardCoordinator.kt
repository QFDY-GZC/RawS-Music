package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.usb.UsbAudioEngine

/**
 * Decides when the native USB background carrier must remain alive.
 *
 * This keeps lifecycle/transport policy out of PlayerController while leaving the actual native
 * carrier operation on UsbAudioEngine.
 */
internal class UsbBackgroundGuardCoordinator(
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val engine: UsbAudioEngine,
        val player: FfmpegAudioPlayer,
        val playState: () -> PlayState,
        val exclusiveActive: () -> Boolean,
        val appInBackground: () -> Boolean,
        val isReleased: () -> Boolean,
    )

    fun sync(reason: String) {
        val nativeStreamStateName = nativeStreamStateName()
        val shouldRun =
            callbacks.exclusiveActive() &&
                callbacks.player.usbExclusiveMode &&
                callbacks.playState() == PlayState.PLAYING

        val holdDuringUsbTransient =
            shouldHoldDuringTransient(reason, nativeStreamStateName)

        if (shouldRun || holdDuringUsbTransient) {
            if (shouldAssert(reason, holdDuringUsbTransient)) {
                callbacks.engine.setBackgroundPlaybackActiveSafely(
                    true,
                    "syncUsbSystemAudioKeepAlive:$reason",
                )
            }
        } else if (shouldRelease()) {
            callbacks.engine.setBackgroundPlaybackActiveSafely(
                false,
                "syncUsbSystemAudioKeepAlive:$reason",
            )
        }
    }

    private fun shouldAssert(reason: String, transientHold: Boolean): Boolean {
        if (callbacks.isReleased() ||
            !callbacks.exclusiveActive() ||
            !callbacks.player.usbExclusiveMode
        ) {
            return false
        }
        if (transientHold) return true
        if (callbacks.appInBackground()) return true

        val normalizedReason = reason.lowercase()
        return normalizedReason.contains("background") ||
            normalizedReason.contains("guardian") ||
            normalizedReason.contains("progress_update") ||
            normalizedReason.contains("media_identity") ||
            normalizedReason.contains("recover")
    }

    private fun shouldRelease(): Boolean {
        if (callbacks.isReleased() ||
            !callbacks.exclusiveActive() ||
            !callbacks.player.usbExclusiveMode
        ) {
            return true
        }

        // Keep the guard during short state transitions while playback intent is still alive.
        val playerState = callbacks.player.state
        if (callbacks.playState() == PlayState.PLAYING ||
            callbacks.playState() == PlayState.PREPARING ||
            playerState == FfmpegAudioPlayer.State.PLAYING ||
            playerState == FfmpegAudioPlayer.State.PREPARING
        ) {
            return false
        }

        val nativeState = nativeStreamStateName()
        return nativeState != "STREAMING" && nativeState != "STARTING"
    }

    private fun shouldHoldDuringTransient(
        reason: String,
        nativeStreamStateName: String,
    ): Boolean {
        if (callbacks.isReleased() ||
            !callbacks.exclusiveActive() ||
            !callbacks.player.usbExclusiveMode
        ) {
            return false
        }

        val normalizedReason = reason.lowercase()
        val transientReason =
            normalizedReason.contains("recover") ||
                normalizedReason.contains("pause_warm") ||
                normalizedReason.contains("preparing") ||
                normalizedReason.contains("progress_update") ||
                normalizedReason.contains("media_identity") ||
                normalizedReason.contains("guardian")
        if (!transientReason) return false

        val playerState = callbacks.player.state
        return callbacks.appInBackground() ||
            callbacks.playState() == PlayState.PLAYING ||
            callbacks.playState() == PlayState.PREPARING ||
            playerState == FfmpegAudioPlayer.State.PLAYING ||
            playerState == FfmpegAudioPlayer.State.PREPARING ||
            nativeStreamStateName == "STREAMING" ||
            nativeStreamStateName == "STARTING"
    }

    private fun nativeStreamStateName(): String =
        runCatching { callbacks.engine.getNativeStreamState().name }.getOrNull().orEmpty()
}
