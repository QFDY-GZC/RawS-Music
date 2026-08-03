package com.rawsmusic.module.player

import android.hardware.usb.UsbDevice
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbHardwareVolumeStore
import com.rawsmusic.module.player.usb.UsbPolicyRestartSource

/** Rebuilds an exclusive USB policy without coupling policy changes to transport controls. */
internal class PlayerUsbPolicyRestartCoordinator(
    private val tag: String,
    private val currentDevice: () -> UsbDevice?,
    private val refreshRuntimeSnapshot: () -> Unit,
    private val resolveRestartSource: () -> UsbPolicyRestartSource,
    private val stopPlayback: (timeoutMs: Long) -> Boolean,
    private val transitionStopped: () -> Unit,
    private val stopStreaming: () -> Unit,
    private val setNativeExclusive: (Boolean) -> Unit,
    private val setNativePolicy: (exclusive: Boolean, bitPerfect: Boolean, hardwareVolume: Boolean) -> Unit,
    private val setFfmpegBitPerfect: (Boolean) -> Unit,
    private val releaseUsb: () -> Unit,
    private val prepareForPlayback: (sampleRate: Int, bits: Int, channels: Int, sourcePath: String?) -> Boolean,
    private val initializeHardwareVolume: (UsbDevice, String) -> Boolean,
    private val applyVolumeRoute: () -> Unit,
) {
    fun restart(
        exclusive: Boolean,
        bitPerfect: Boolean,
        hardwareVolumeRequested: Boolean,
    ): Boolean {
        if (currentDevice() == null) {
            AppLogger.e(tag, "restartUsbWithPolicy: no USB device")
            return false
        }
        if (exclusive && hardwareVolumeRequested) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = true
        }

        refreshRuntimeSnapshot()
        val source = resolveRestartSource()

        // Drain the real renderer before touching the native USB lifecycle.
        if (!stopPlayback(5_000L)) {
            AppLogger.e(tag, "restartUsbWithPolicy aborted: playback Runnable did not exit")
            return false
        }
        if (exclusive && hardwareVolumeRequested) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = true
            setNativePolicy(exclusive, bitPerfect, true)
            AppLogger.i(tag, "restartUsbWithPolicy: hardware-volume request re-asserted after player stop")
        }
        transitionStopped()
        stopStreaming()

        setNativeExclusive(exclusive)
        setNativePolicy(exclusive, bitPerfect, hardwareVolumeRequested)
        setFfmpegBitPerfect(bitPerfect)
        releaseUsb()
        if (exclusive && hardwareVolumeRequested) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = true
            setNativePolicy(exclusive, bitPerfect, true)
            AppLogger.i(tag, "restartUsbWithPolicy: hardware-volume request re-asserted before prepareForPlayback")
        }

        val prepared = prepareForPlayback(
            source.sampleRate,
            source.bitsPerSample,
            source.channels,
            source.sourcePath,
        )
        if (exclusive && hardwareVolumeRequested) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = true
            setNativePolicy(exclusive, bitPerfect, true)
            AppLogger.i(
                tag,
                "restartUsbWithPolicy: hardware-volume request re-asserted after prepareForPlayback prepared=$prepared",
            )
        }

        val volumeReady = if (prepared && exclusive && hardwareVolumeRequested) {
            currentDevice()?.let { initializeHardwareVolume(it, "restart_usb_with_policy_pre_iso") } ?: false
        } else {
            true
        }
        val ok = prepared && volumeReady
        if (ok) {
            AppLogger.i(
                tag,
                "restartUsbWithPolicy OK: exclusive=$exclusive bitPerfect=$bitPerfect hwVol=$hardwareVolumeRequested",
            )
            applyVolumeRoute()
        } else if (prepared && !volumeReady) {
            AppLogger.e(tag, "restartUsbWithPolicy refused ISO start because hardware-volume initialization failed")
            val releasedCleanly = runCatching {
                releaseUsb()
                true
            }.getOrDefault(false)
            if (releasedCleanly) {
                UsbHardwareVolumeStore.markSessionClean("hardware_volume_init_failed_released")
            }
            AppPreferences.Player.hardwareFeatureUnitEnabled = false
            AppPreferences.Player.usbVolumeMode = 0
            setNativePolicy(exclusive, bitPerfect, false)
        }
        return ok
    }
}
