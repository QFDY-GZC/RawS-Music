package com.rawsmusic.module.player

import android.media.AudioManager
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbHardwareVolumeMath
import com.rawsmusic.module.player.usb.UsbHardwareVolumeModel

/** Keeps UI and MediaSession volume changes on the same output route. */
internal class PlayerMediaVolumeCoordinator(
    private val resolveRoute: () -> PlayerUsbVolumeRouteCoordinator.VolumeRoute,
    private val forceFixedVolume: (String) -> Unit,
    private val enqueueHardwareAdjustment: (Int, String) -> Int,
    private val currentHardwareStep: () -> Int,
    private val setHardwareStep: (Int, String) -> Int,
    private val isUsbExclusiveSoftwareVolumeActive: () -> Boolean,
    private val applyUsbExclusiveSoftwareUserVolume: (Float, String) -> Unit,
    private val dvcIsActive: (Boolean) -> Boolean,
    private val dvcAdjust: (Int, String) -> Unit,
    private val dvcSetLogicalVolume: (Float, String) -> Unit,
    private val dvcLogicalVolume: () -> Float,
    private val systemVolumeController: () -> AndroidSystemVolumeController,
    private val getSystemMusicVolumeLinear: () -> Float,
    private val setSystemMusicVolumeLinear: (Float) -> Unit,
    private val applyVolumeRoute: (String) -> Unit,
    private val setUserVolume: (Float) -> Unit,
    private val usbVolumeDb: () -> Float,
) {
    fun adjustFromUiButton(deltaStep: Int) {
        if (deltaStep == 0) return
        when (resolveRoute()) {
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.USB_FIXED -> {
                forceFixedVolume("adjustVolumeFromUiButton_ignored")
            }
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.USB_HARDWARE -> {
                enqueueHardwareAdjustment(
                    if (deltaStep > 0) 1 else -1,
                    "ui_button delta=$deltaStep",
                )
            }
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.SYSTEM -> {
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    val old = AppPreferences.Player.volume.coerceIn(0f, 1f)
                    val delta = if (deltaStep > 0) {
                        UsbHardwareVolumeModel.DEFAULT_LINEAR_STEP
                    } else {
                        -UsbHardwareVolumeModel.DEFAULT_LINEAR_STEP
                    }
                    applyUsbExclusiveSoftwareUserVolume(old + delta, "ui_button_system delta=$deltaStep")
                } else if (dvcIsActive(false)) {
                    dvcAdjust(deltaStep, "ui_button_dvc")
                } else {
                    val direction = if (deltaStep > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    systemVolumeController().adjustMusicVolume(direction, AudioManager.FLAG_SHOW_UI)
                    applyVolumeRoute("ui_button_system delta=$deltaStep")
                }
            }
        }
    }

    fun step(delta: Float) = adjustFromUiButton(if (delta >= 0f) 1 else -1)

    fun volumeDb(): Float = usbVolumeDb()

    fun setLinear(linear: Float) = setUserVolume(linear.coerceIn(0f, 1f))

    fun setMediaSessionStep(step: Int, reason: String) {
        when (resolveRoute()) {
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.USB_FIXED -> {
                forceFixedVolume("media_session_set_fixed:$reason")
            }
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.USB_HARDWARE -> {
                val mappedStep = UsbHardwareVolumeMath.uiToStep(
                    UsbHardwareVolumeModel.stepToUiVolume(step),
                )
                setHardwareStep(mappedStep, "media_session_set:$reason rawStep=$step mappedStep=$mappedStep")
            }
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.SYSTEM -> {
                val volume = UsbHardwareVolumeModel.stepToUiVolume(step)
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    applyUsbExclusiveSoftwareUserVolume(volume, "media_session_set_system:$reason")
                } else if (dvcIsActive(false)) {
                    dvcSetLogicalVolume(volume, "media_session_set_dvc:$reason")
                } else {
                    setSystemMusicVolumeLinear(volume)
                    applyVolumeRoute("media_session_set_system:$reason")
                }
            }
        }
    }

    fun adjustMediaSession(direction: Int, reason: String) {
        if (direction == 0) return
        when (resolveRoute()) {
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.USB_FIXED -> {
                forceFixedVolume("media_session_adjust_fixed:$reason")
            }
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.USB_HARDWARE -> {
                enqueueHardwareAdjustment(
                    if (direction > 0) 1 else -1,
                    "$reason direction=$direction",
                )
            }
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.SYSTEM -> {
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    val old = AppPreferences.Player.volume.coerceIn(0f, 1f)
                    val delta = if (direction > 0) {
                        UsbHardwareVolumeModel.DEFAULT_LINEAR_STEP
                    } else {
                        -UsbHardwareVolumeModel.DEFAULT_LINEAR_STEP
                    }
                    applyUsbExclusiveSoftwareUserVolume(old + delta, "$reason system direction=$direction")
                } else if (dvcIsActive(false)) {
                    dvcAdjust(direction, "media_session_adjust_dvc:$reason")
                } else {
                    val adjust = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    systemVolumeController().adjustMusicVolume(adjust, AudioManager.FLAG_SHOW_UI)
                    applyVolumeRoute("$reason system direction=$direction")
                }
            }
        }
    }

    fun mediaSessionStep(): Int {
        return when (resolveRoute()) {
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.USB_FIXED ->
                UsbHardwareVolumeModel.uiVolumeToStep(1.0f)
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.USB_HARDWARE ->
                UsbHardwareVolumeModel.uiVolumeToStep(
                    UsbHardwareVolumeMath.stepToUi(currentHardwareStep()),
                )
            PlayerUsbVolumeRouteCoordinator.VolumeRoute.SYSTEM ->
                UsbHardwareVolumeModel.uiVolumeToStep(
                    if (isUsbExclusiveSoftwareVolumeActive()) {
                        AppPreferences.Player.volume.coerceIn(0f, 1f)
                    } else if (dvcIsActive(false)) {
                        dvcLogicalVolume()
                    } else {
                        getSystemMusicVolumeLinear()
                    },
                )
        }
    }
}
