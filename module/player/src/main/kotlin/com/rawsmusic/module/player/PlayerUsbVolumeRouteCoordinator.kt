package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioManager

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbHardwareVolumeMath
import com.rawsmusic.module.player.usb.UsbOutputProfile
import com.rawsmusic.module.player.usb.UsbVolumePath

import kotlinx.coroutines.delay

/**
 * Owns the three USB/system volume routes used by PlayerController.
 *
 * Keeping this policy separate is important: Android STREAM_MUSIC, DVC, USB
 * software gain, and a DAC Feature Unit must not observe or overwrite each
 * other's state while a device is being attached or rebuilt.
 */
internal class PlayerUsbVolumeRouteCoordinator(
    context: Context,
    private val engine: UsbAudioEngine,
    private val callbacks: Callbacks,
) {
    companion object {
        private const val TAG = "PlayerController"
    }

    enum class VolumeRoute { SYSTEM, USB_HARDWARE, USB_FIXED }

    private val appContext = context.applicationContext

    data class Callbacks(
        val isUsbExclusiveActive: () -> Boolean,
        val buildUsbOutputProfile: (Boolean) -> UsbOutputProfile,
        val isUsbSeeking: () -> Boolean,
        val isRenderSwitching: () -> Boolean,
        val ffmpegState: () -> FfmpegAudioPlayer.State,
        val explicitSoftwareMute: () -> Boolean,
        val setExplicitSoftwareMute: (Boolean) -> Unit,
        val applyComposedVolume: () -> Unit,
        val applyUsbVolume: (UsbOutputProfile, String) -> Unit,
        val syncUsbRemoteVolumeRoute: (String) -> Unit,
        val setNativeDvc: (Boolean, Float, Float) -> Unit,
        val setHardwareVolumeStep: (Int, String) -> Int,
        val shouldUseUsbRemoteVolume: () -> Boolean,
    )

    private var androidSystemVolumeController: AndroidSystemVolumeController? = null

    val androidDvcController: AndroidDvcController by lazy {
        AndroidDvcController(
            callbacks = AndroidDvcController.Callbacks(
                systemVolume = ::getSystemMusicVolumeLinear,
                systemVolumeStep = { systemVolumeController().getMusicVolumeStep() },
                systemVolumeMaxStep = { systemVolumeController().getMusicVolumeMaxStep() },
                systemVolumeDbAt = { step -> systemVolumeController().getMusicVolumeDb(step) },
                setSystemVolumeStepSilently = { step, reason ->
                    systemVolumeController().setMusicVolumeStepIgnoringCallbacks(
                        step = step,
                        flags = 0,
                        ignoreWindowMs = 500L,
                        reason = reason,
                    )
                },
                setNativeDvc = callbacks.setNativeDvc,
            )
        )
    }

    fun resolveCurrentUsbOutputProfile(): UsbOutputProfile? {
        if (!callbacks.isUsbExclusiveActive()) return null
        return callbacks.buildUsbOutputProfile(true)
    }

    fun resolveVolumeRoute(): VolumeRoute {
        return when (resolveCurrentUsbOutputProfile()?.volumePath) {
            UsbVolumePath.HardwareUserVolume -> VolumeRoute.USB_HARDWARE
            UsbVolumePath.Fixed -> VolumeRoute.USB_FIXED
            else -> VolumeRoute.SYSTEM
        }
    }

    internal fun systemVolumeController(): AndroidSystemVolumeController {
        return androidSystemVolumeController ?: AndroidSystemVolumeController(
            context = appContext,
            onExternalVolumeChanged = ::handleSystemVolumeChanged,
        ).also { androidSystemVolumeController = it }
    }

    fun getSystemMusicVolumeLinear(): Float = systemVolumeController().getMusicVolumeLinear()

    fun setSystemMusicVolumeLinear(
        linear: Float,
        flags: Int = AudioManager.FLAG_SHOW_UI,
    ) {
        systemVolumeController().setMusicVolumeLinear(linear, flags)
    }

    fun suppressSystemVolumeObserver(windowMs: Long, reason: String) {
        systemVolumeController().suppressCallbacks(windowMs, reason)
    }

    fun keepUsbExclusiveSoftwareVolumeIsolated(reason: String) {
        if (!isUsbExclusiveSoftwareVolumeActive()) return
        AppLogger.i(
            TAG,
            "USB software volume kept isolated from STREAM_MUSIC: " +
                "app=${AppPreferences.Player.volume.coerceIn(0f, 1f)} reason=$reason"
        )
    }

    fun isUsbExclusiveSoftwareVolumeActive(): Boolean {
        if (!callbacks.isUsbExclusiveActive() || resolveVolumeRoute() != VolumeRoute.SYSTEM) return false
        return callbacks.buildUsbOutputProfile(true).volumePath == UsbVolumePath.Software
    }

    fun normalizeUsbExclusiveSoftwareEntryVolume(systemLinear: Float, reason: String): Float {
        val current = AppPreferences.Player.usbSoftwareVolume.coerceIn(0f, 1f)
        AppPreferences.Player.volume = current
        AppLogger.i(
            TAG,
            "Restoring independent USB software volume: ui=$current system=$systemLinear reason=$reason"
        )
        return current
    }

    fun applyUsbExclusiveSoftwareUserVolume(linear: Float, reason: String) {
        val target = linear.coerceIn(0f, 1f)
        val pcmGain = PlaybackVolumePlanner.usbSoftwarePcmGain(target)
        AppPreferences.Player.usbSoftwareVolume = target
        AppPreferences.Player.volume = target
        callbacks.setExplicitSoftwareMute(target <= 0.0001f)
        AppLogger.i(TAG, "applyUsbExclusiveSoftwareUserVolume: ui=$target pcmGain=$pcmGain reason=$reason")
        engine.nativeSetUsbSoftwareGain(pcmGain)
        callbacks.applyComposedVolume()
        keepUsbExclusiveSoftwareVolumeIsolated("applyUsbExclusiveSoftwareUserVolume:$reason")
    }

    fun forceUsbFixedVolume0Db(reason: String) {
        AppPreferences.Player.volume = 1.0f
        callbacks.setExplicitSoftwareMute(false)
        engine.nativeSetUsbSoftwareGain(1.0f)
        val handle = engine.currentHandle
        if (handle != 0L) {
            engine.setSessionVolumeScale(handle, 1.0f, 0)
        }
        AppLogger.w(TAG, "USB fixed digital 0dB volume enforced without changing STREAM_MUSIC: reason=$reason")
    }

    fun applyVolumeRoute(reason: String) {
        val exclusive = callbacks.isUsbExclusiveActive()
        androidDvcController.applyRoute(exclusive, reason)
        val systemLinear = getSystemMusicVolumeLinear()
        val profile = resolveCurrentUsbOutputProfile()
        val volumePath = profile?.volumePath
        val route = when (volumePath) {
            UsbVolumePath.HardwareUserVolume -> VolumeRoute.USB_HARDWARE
            UsbVolumePath.Fixed -> VolumeRoute.USB_FIXED
            else -> VolumeRoute.SYSTEM
        }

        AppLogger.i(
            TAG,
            "applyVolumeRoute: reason=$reason route=$route exclusive=$exclusive " +
                "systemLinear=$systemLinear hwPref=${AppPreferences.Player.hardwareFeatureUnitEnabled} " +
                "volumePath=$volumePath hwValidated=${profile?.hardwareVolumeValidated}"
        )

        when {
            !exclusive || profile == null -> {
                engine.nativeSetPolicy(exclusive = false, bitPerfect = false, hwVol = false)
                engine.nativeSetUsbSoftwareGain(1.0f)
                if (!androidDvcController.isActive(usbExclusive = false)) {
                    AppPreferences.Player.volume = systemLinear
                }
                if (systemLinear > 0.0001f) {
                    callbacks.setExplicitSoftwareMute(false)
                }
                callbacks.applyComposedVolume()
                AppLogger.i(TAG, "Non-exclusive playback uses Android system volume directly")
            }
            volumePath == UsbVolumePath.HardwareUserVolume -> {
                engine.nativeSetPolicy(
                    exclusive = true,
                    bitPerfect = profile.bitPerfect,
                    hwVol = true,
                )
                engine.nativeSetUsbSoftwareGain(1.0f)
                val handle = engine.currentHandle
                if (handle != 0L) {
                    engine.setSessionVolumeScale(handle, 1.0f, 0)
                }
            }
            volumePath == UsbVolumePath.Fixed -> {
                engine.nativeSetPolicy(exclusive = true, bitPerfect = true, hwVol = false)
                forceUsbFixedVolume0Db("applyVolumeRoute:$reason")
                AppLogger.i(TAG, "USB exclusive fixed-output path active; user volume locked at 0dB")
            }
            else -> {
                engine.nativeSetPolicy(
                    exclusive = true,
                    bitPerfect = profile.bitPerfect,
                    hwVol = AppPreferences.Player.usbVolumeMode == 1 &&
                        AppPreferences.Player.hardwareFeatureUnitEnabled && exclusive,
                )
                val userLinear = normalizeUsbExclusiveSoftwareEntryVolume(systemLinear, reason)
                val handle = engine.currentHandle
                if (handle != 0L) {
                    callbacks.applyUsbVolume(profile, "applyVolumeRoute:$reason")
                    engine.setSessionVolumeScale(handle, 1.0f, 0)
                } else {
                    engine.nativeSetUsbSoftwareGain(PlaybackVolumePlanner.usbSoftwarePcmGain(userLinear))
                }
                AppLogger.i(TAG, "USB exclusive software gain follows app volume: linear=$userLinear")
                keepUsbExclusiveSoftwareVolumeIsolated("applyVolumeRoute:$reason")
            }
        }

        syncSystemVolumeObserverForRoute("applyVolumeRoute:$reason")
        callbacks.syncUsbRemoteVolumeRoute("applyVolumeRoute:$reason")
    }

    fun setUserVolume(linear: Float) {
        val route = resolveVolumeRoute()
        val v = linear.coerceIn(0f, 1f)
        AppLogger.i(TAG, "setUserVolume: route=$route linear=$v")

        when (route) {
            VolumeRoute.USB_FIXED -> forceUsbFixedVolume0Db("setUserVolume_ignored")
            VolumeRoute.USB_HARDWARE -> {
                // Hardware step conversion remains in the owning controller.
                callbacks.setHardwareVolumeStep(UsbHardwareVolumeMath.uiToStep(v), "setUserVolume")
            }
            VolumeRoute.SYSTEM -> {
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    applyUsbExclusiveSoftwareUserVolume(v, "setUserVolume")
                } else if (androidDvcController.isActive(usbExclusive = false)) {
                    androidDvcController.setLogicalVolume(v, "setUserVolume")
                } else {
                    setSystemMusicVolumeLinear(v)
                    val actual = getSystemMusicVolumeLinear()
                    AppPreferences.Player.volume = actual
                    if (actual > 0.0001f) callbacks.setExplicitSoftwareMute(false)
                }
            }
        }
    }

    fun unregisterSystemVolumeObserver() {
        androidSystemVolumeController?.unregister()
    }

    fun syncSystemVolumeObserverForRoute(reason: String) {
        val shouldObserve =
            androidDvcController.isActive(usbExclusive = callbacks.isUsbExclusiveActive()) ||
                (
                    callbacks.isUsbExclusiveActive() &&
                        resolveVolumeRoute() == VolumeRoute.SYSTEM &&
                        !isUsbExclusiveSoftwareVolumeActive()
                    )
        systemVolumeController().syncObservation(shouldObserve, reason)
    }

    private fun handleSystemVolumeChanged(linear: Float) {
        val route = resolveVolumeRoute()
        AppLogger.i(TAG, "handleSystemVolumeChanged: route=$route linear=$linear")

        when (route) {
            VolumeRoute.USB_FIXED -> {
                forceUsbFixedVolume0Db("system_volume_changed_fixed_0db")
                return
            }
            VolumeRoute.USB_HARDWARE -> {
                AppLogger.i(
                    TAG,
                    "onSystemVolumeChanged ignored in USB_HARDWARE route: " +
                        "DAC Feature Unit owns volume linear=$linear remote=${callbacks.shouldUseUsbRemoteVolume()}",
                )
            }
            VolumeRoute.SYSTEM -> {
                if (
                    callbacks.isUsbExclusiveActive() &&
                    linear <= 0.0001f &&
                    isUsbExclusiveSoftwareVolumeActive() &&
                    AppPreferences.Player.volume > 0.0001f &&
                    !callbacks.explicitSoftwareMute() &&
                    (
                        callbacks.isUsbSeeking() ||
                            callbacks.isRenderSwitching() ||
                            callbacks.ffmpegState() == FfmpegAudioPlayer.State.PREPARING
                        )
                ) {
                    AppLogger.w(
                        TAG,
                        "onSystemVolumeChanged ignored suspicious zero in USB software route: " +
                            "appVolume=${AppPreferences.Player.volume} usbSeeking=${callbacks.isUsbSeeking()} " +
                            "renderSwitching=${callbacks.isRenderSwitching()} ffState=${callbacks.ffmpegState()}"
                    )
                    return
                }
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    applyUsbExclusiveSoftwareUserVolume(linear, "system_volume_changed")
                } else if (androidDvcController.isActive(usbExclusive = false)) {
                    androidDvcController.syncFromSystemVolume("system_volume_changed")
                } else {
                    AppPreferences.Player.volume = linear
                    if (linear > 0.0001f) callbacks.setExplicitSoftwareMute(false)
                }
            }
        }
    }

    fun release(reason: String) {
        unregisterSystemVolumeObserver()
        androidDvcController.release(reason)
    }

}
