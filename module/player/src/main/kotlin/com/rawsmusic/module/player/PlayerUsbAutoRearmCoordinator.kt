package com.rawsmusic.module.player

import android.hardware.usb.UsbDevice
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbHardwareVolumeStore

/** Decides whether an already-authorized USB route may be re-armed before playback. */
internal class PlayerUsbAutoRearmCoordinator(
    private val tag: String,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val isExclusiveActive: () -> Boolean,
        val isEngineExclusive: () -> Boolean,
        val isPcmToDsdSafetyBlocked: () -> Boolean,
        val isPcmToDsdRequested: () -> Boolean,
        val isRenderSwitching: () -> Boolean,
        val isRecovering: () -> Boolean,
        val isHardwareRecoveryBlocked: () -> Boolean,
        val setHardwareRecoveryBlocked: (Boolean) -> Unit,
        val currentDevice: () -> UsbDevice?,
        val findDevice: () -> UsbDevice?,
        val prefetchedDeviceId: () -> Int,
        val requested: () -> Boolean,
        val lastExclusiveActive: () -> Boolean,
        val hasPermission: (UsbDevice) -> Boolean,
        val setCurrentDevice: (UsbDevice) -> Unit,
        val rememberDevice: (UsbDevice, String) -> Unit,
        val prepareColdActivation: (String) -> Unit,
        val activate: (UsbDevice) -> Boolean,
        val clearPrefetchedDevice: () -> Unit,
    )

    fun tryRearm(song: AudioFile): Boolean {
        if (callbacks.isExclusiveActive() || callbacks.isEngineExclusive()) return false
        if (callbacks.isPcmToDsdSafetyBlocked() && callbacks.isPcmToDsdRequested()) {
            AppLogger.e(tag, "USB auto-rearm blocked by PCM_TO_DSD safety fuse; disable PCM→DSD before retry")
            return false
        }
        if (callbacks.isRenderSwitching() || callbacks.isRecovering()) return false
        if (callbacks.isHardwareRecoveryBlocked() || UsbHardwareVolumeStore.isRecoveryBlocked()) {
            callbacks.setHardwareRecoveryBlocked(true)
            AppLogger.e(
                tag,
                "USB exclusive auto-rearm blocked by persistent hardware-volume safety interlock; " +
                    "user must explicitly enable USB exclusive",
            )
            return false
        }

        val device = callbacks.currentDevice() ?: callbacks.findDevice() ?: return false
        val prefetched = callbacks.prefetchedDeviceId() == device.deviceId
        if (!callbacks.requested() && !prefetched) {
            AppLogger.i(
                tag,
                "USB exclusive auto-rearm skipped: requested=false lastExclusive=${callbacks.lastExclusiveActive()} " +
                    "prefetchDeviceId=${callbacks.prefetchedDeviceId()} device=${device.deviceName} song=${song.title}",
            )
            return false
        }
        if (!callbacks.hasPermission(device)) {
            AppLogger.w(tag, "USB exclusive auto-rearm skipped: no permission device=${device.deviceName} song=${song.title}")
            return false
        }

        return runCatching {
            AppLogger.i(tag, "USB exclusive auto-rearm before playback: device=${device.productName} song=${song.title}")
            callbacks.setCurrentDevice(device)
            callbacks.rememberDevice(device, "play_request_auto_rearm")
            callbacks.prepareColdActivation("play_request_auto_rearm:${song.path}")
            callbacks.activate(device)
                .also { active ->
                    if (prefetched && active) callbacks.clearPrefetchedDevice()
                }
        }.getOrElse { error ->
            AppLogger.w(tag, "USB exclusive auto-rearm failed; continue with shared playback", error)
            false
        }
    }
}
