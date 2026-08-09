package com.rawsmusic.helper

import android.view.KeyEvent
import com.rawsmusic.module.player.PlayerController

class UsbVolumeKeyHandler(
    private val getPlayerController: () -> PlayerController?,
    private val showVolumeOverlay: (String) -> Unit
) {
    fun handleKeyDown(keyCode: Int, event: KeyEvent? = null): Boolean {
        val controller = getPlayerController()
        if (controller?.isUsbExclusiveActive() != true || !controller.canControlUsbVolume()) {
            return false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> controller.stepUsbVolume(+0.04f)
            KeyEvent.KEYCODE_VOLUME_DOWN -> controller.stepUsbVolume(-0.04f)
            KeyEvent.KEYCODE_VOLUME_MUTE -> controller.setUsbVolumeLinear(0f)
            else -> return false
        }

        // Repeat key-down events already arrive on the UI thread. Refresh immediately so a
        // held volume key keeps the displayed dB value in step with the native volume change.
        showVolume(controller)
        return true
    }

    private fun showVolume(controller: PlayerController) {
        showVolumeOverlay("DAC 音量: %.1f dB".format(controller.getUsbVolumeDb()))
    }
}
