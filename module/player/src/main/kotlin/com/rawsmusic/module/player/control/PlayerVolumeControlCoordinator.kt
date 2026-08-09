package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.player.PlaybackVolumePlanner
import com.rawsmusic.module.player.usb.UsbHardwareVolumeModel
import com.rawsmusic.module.player.usb.UsbOutputProfile
import com.rawsmusic.module.player.usb.UsbVolumePlan

/**
 * Owns replay-gain state and composed Android/USB volume routing.
 *
 * Feature Unit writes are deliberately outside this automatic coordinator. It only composes
 * PCM/session gain; device hardware volume changes are owned by the explicit user-command lane.
 */
internal class PlayerVolumeControlCoordinator(
    private val callbacks: Callbacks,
) {
    data class ReplayGainSettings(
        val normalizationEnabled: Boolean,
        val replayGainEnabled: Boolean,
        val replayGainMode: Int,
    )

    data class Callbacks(
        val isReleased: () -> Boolean,
        val transportTransitioning: () -> Boolean,
        val currentUsbProfile: () -> UsbOutputProfile?,
        val userVolume: () -> Float,
        val duckFactor: () -> Float,
        val setAndroidSoftwareGain: (Float) -> Unit,
        val setUsbPcmGain: (Float) -> Unit,
        val logDebug: (String) -> Unit,
        val logWarning: (String) -> Unit,
    )

    private var replayGainModifier = 1f

    fun applyReplayGain(song: AudioFile, settings: ReplayGainSettings) {
        val decision = PlaybackVolumePlanner.replayGain(
            song = song,
            normalizationEnabled = settings.normalizationEnabled,
            replayGainEnabled = settings.replayGainEnabled,
            replayGainMode = settings.replayGainMode,
        )
        replayGainModifier = decision.linearGain
        if (decision.active) {
            callbacks.logDebug(
                "ReplayGain: mode=${settings.replayGainMode}, gainDB=${decision.gainDb}, " +
                    "peak=${decision.peak}, linear=${decision.linearGain}"
            )
        }
        applyComposedVolume()
    }

    fun applyUsbVolume(profile: UsbOutputProfile, reason: String): UsbVolumePlan {
        val uiVolume = callbacks.userVolume().coerceIn(0f, 1f)
        val plan = PlaybackVolumePlanner.usbVolumePlan(
            profile = profile,
            userVolume = uiVolume,
            replayGain = replayGainModifier,
            duck = callbacks.duckFactor(),
            reason = reason,
        )
        val displayedHardwareDb = UsbHardwareVolumeModel.uiVolumeToHardwareDb(uiVolume)
        callbacks.logWarning(
            "APPLY_USB_VOLUME volume=$uiVolume hwDb=$displayedHardwareDb " +
                "planPcm=${plan.pcmGain} planHwDb=${plan.hardwareDb} " +
                "useHw=${plan.useHardwareVolume} reason=$reason"
        )

        if (!callbacks.isReleased()) {
            callbacks.setUsbPcmGain(plan.pcmGain.coerceIn(0f, 1f))
            if (plan.useHardwareVolume) {
                // Exclusive-device ownership: automatic playback boundaries never write Feature Unit.
                // The hardware target is initialized once per physical DAC attachment session and is changed only
                // by an explicit user-volume command.
                callbacks.logDebug(
                    "applyUsbVolume: keep hardware Feature Unit unchanged reason=$reason " +
                        "targetDb=${plan.hardwareDb}"
                )
            }
        }
        return plan
    }

    fun applyComposedVolume() {
        if (callbacks.transportTransitioning()) {
            callbacks.logWarning("applyComposedVolume skipped during transport transition")
            return
        }

        val usbProfile = callbacks.currentUsbProfile()
        if (usbProfile != null) {
            applyUsbVolume(usbProfile, "composed")
            return
        }

        val composed = PlaybackVolumePlanner.androidSoftwareGain(
            replayGain = replayGainModifier,
            duck = callbacks.duckFactor(),
        )
        if (!callbacks.isReleased()) callbacks.setAndroidSoftwareGain(composed)
    }

    internal fun replayGainModifierForTest(): Float = replayGainModifier
}
