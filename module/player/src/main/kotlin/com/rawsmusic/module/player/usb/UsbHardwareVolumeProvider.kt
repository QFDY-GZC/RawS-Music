package com.rawsmusic.module.player.usb

import androidx.media.VolumeProviderCompat
import com.rawsmusic.core.common.utils.AppLogger

/**
 * MediaSession 音量控制器。
 * 系统音量键 → MediaSession → onAdjustVolume/onSetVolumeTo → USB Feature Unit SET_CUR
 */
class UsbHardwareVolumeProvider(
    private val getCurrentStep: () -> Int,
    private val onSetStep: (Int, String) -> Unit,
    private val onAdjustStep: (Int, String) -> Unit
) : VolumeProviderCompat(
    VOLUME_CONTROL_ABSOLUTE,
    UsbHardwareVolumeModel.MAX_STEPS,
    getCurrentStep()
) {

    override fun onAdjustVolume(direction: Int) {
        AppLogger.i(TAG, "onAdjustVolume direction=$direction current=${currentVolume}")
        onAdjustStep(direction, "media_session_onAdjustVolume")
        setCurrentVolume(getCurrentStep())
    }

    override fun onSetVolumeTo(volume: Int) {
        val bounded = volume.coerceIn(0, UsbHardwareVolumeModel.MAX_STEPS)
        AppLogger.i(TAG, "onSetVolumeTo volume=$volume bounded=$bounded")
        onSetStep(bounded, "media_session_onSetVolumeTo")
        setCurrentVolume(getCurrentStep())
    }

    fun syncFromController() {
        setCurrentVolume(getCurrentStep())
    }

    companion object {
        private const val TAG = "UsbHardwareVolumeProvider"
    }
}
