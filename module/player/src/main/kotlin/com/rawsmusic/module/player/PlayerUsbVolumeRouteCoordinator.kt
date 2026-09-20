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
        val configureTransitionGainOwner: (UsbOutputProfile, String) -> Unit,
        val syncUsbRemoteVolumeRoute: (String) -> Unit,
        val setNativeDvc: (Boolean, Float, Float) -> Unit,
        val setHardwareVolumeStep: (Int, String) -> Int,
        val setHardwareVolumeUi: (Float, String) -> Int,
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

    /**
     * Re-acquiring Android playback ownership must never restore a stale app-side value into
     * STREAM_MUSIC. Read the currently routed system/Bluetooth volume first; only later explicit
     * user volume commands are allowed to change the system coarse step.
     */
    fun prepareAndroidVolumeForPlaybackTakeover(reason: String) {
        if (callbacks.isUsbExclusiveActive()) return
        val systemLinear = getSystemMusicVolumeLinear()
        if (androidDvcController.isActive(usbExclusive = false)) {
            androidDvcController.prepareForPlaybackTakeover(
                usbExclusive = false,
                reason = reason,
            )
        } else {
            AppPreferences.Player.volume = systemLinear
            if (systemLinear > 0.0001f) callbacks.setExplicitSoftwareMute(false)
        }
        AppLogger.i(
            TAG,
            "Android playback takeover adopted current STREAM_MUSIC: systemLinear=$systemLinear " +
                "dvc=${androidDvcController.isActive(usbExclusive = false)} reason=$reason"
        )
    }

    fun keepUsbExclusiveSoftwareVolumeIsolated(reason: String) {
        if (!isUsbExclusiveSoftwareVolumeActive()) return
        AppLogger.i(
            TAG,
            "USB software volume follows local STREAM_MUSIC UI: " +
                "app=${AppPreferences.Player.volume.coerceIn(0f, 1f)} " +
                "system=${getSystemMusicVolumeLinear()} reason=$reason"
        )
    }

    fun isUsbExclusiveSoftwareVolumeActive(): Boolean {
        if (!callbacks.isUsbExclusiveActive() || resolveVolumeRoute() != VolumeRoute.SYSTEM) return false
        return callbacks.buildUsbOutputProfile(true).volumePath == UsbVolumePath.Software
    }

    fun usbSoftwarePcmGainForLocalUi(linear: Float): Float {
        return UsbSoftwareVolumeCurve.gainForLocalStream(
            uiLinear = linear,
            rangeDb = AppPreferences.Player.usbSoftwareVolumeRangeDb,
        )
    }

    fun refreshUsbSoftwareVolumeCurve(reason: String) {
        if (!isUsbExclusiveSoftwareVolumeActive()) return
        applyUsbExclusiveSoftwareUserVolume(
            AppPreferences.Player.usbSoftwareVolume.coerceIn(0f, 1f),
            "curve_refresh:$reason",
        )
    }

    fun normalizeUsbExclusiveSoftwareEntryVolume(systemLinear: Float, reason: String): Float {
        val desired = AppPreferences.Player.usbSoftwareVolume.coerceIn(0f, 1f)
        // Software USB volume now uses the normal local STREAM_MUSIC presentation. Seed the local
        // stream silently from the saved USB level so the first hardware-key press does not jump
        // from an unrelated Android volume value. The public stream is discrete, so use the actual
        // quantized value as the software-gain/UI source of truth.
        systemVolumeController().setMusicVolumeLinearIgnoringCallbacks(
            linear = desired,
            flags = 0,
            ignoreWindowMs = 500L,
            reason = "usb_software_entry:$reason",
        )
        val actual = getSystemMusicVolumeLinear()
        AppPreferences.Player.usbSoftwareVolume = actual
        AppPreferences.Player.volume = actual
        AppLogger.i(
            TAG,
            "USB software volume seeded onto local STREAM_MUSIC: desired=$desired " +
                "previousSystem=$systemLinear actual=$actual reason=$reason"
        )
        return actual
    }

    /**
     * Seed the software-volume presentation from the currently audible hardware Feature Unit dB.
     *
     * reference USB implementation keeps software and hardware volume as separate owners and does not release the Feature
     * Unit merely because the preference changed. RawSMusic additionally maps the current hardware
     * attenuation onto the software curve so the new owner starts at approximately the same audible
     * level instead of resurrecting a stale software-volume preference.
     */
    fun prepareHardwareToSoftwareEntry(hardwareDb: Float, reason: String): Float {
        val desiredUi = UsbSoftwareVolumeCurve.localStreamForDb(
            gainDb = hardwareDb.coerceAtMost(0f),
            rangeDb = AppPreferences.Player.usbSoftwareVolumeRangeDb,
        )
        val previousSystem = getSystemMusicVolumeLinear()
        systemVolumeController().setMusicVolumeLinearIgnoringCallbacks(
            linear = desiredUi,
            flags = 0,
            ignoreWindowMs = 700L,
            reason = "usb_hw_to_sw_entry:$reason",
        )
        val actualUi = getSystemMusicVolumeLinear()
        val pcmGain = usbSoftwarePcmGainForLocalUi(actualUi)
        AppPreferences.Player.usbSoftwareVolume = actualUi
        AppPreferences.Player.volume = actualUi
        callbacks.setExplicitSoftwareMute(actualUi <= 0.0001f)

        // Set the global/native target before changing Feature Unit ownership. On a strict
        // bit-perfect live handle native keeps that handle at unity PCM but still remembers this
        // target for the processed session that will be opened after the transport is stopped.
        engine.nativeSetUsbSoftwareGain(pcmGain)
        AppLogger.i(
            TAG,
            "USB HW->SW entry seeded from hardware dB: hardwareDb=$hardwareDb desiredUi=$desiredUi " +
                "actualUi=$actualUi pcmGain=$pcmGain previousSystem=$previousSystem reason=$reason",
        )
        return actualUi
    }

    fun applyUsbExclusiveSoftwareUserVolume(linear: Float, reason: String) {
        val target = linear.coerceIn(0f, 1f)
        val pcmGain = usbSoftwarePcmGainForLocalUi(target)
        AppPreferences.Player.usbSoftwareVolume = target
        AppPreferences.Player.volume = target
        callbacks.setExplicitSoftwareMute(target <= 0.0001f)
        AppLogger.i(
            TAG,
            "applyUsbExclusiveSoftwareUserVolume: ui=$target pcmGain=$pcmGain " +
                "systemMax=${systemVolumeController().getMusicVolumeMaxStep()} reason=$reason",
        )
        engine.nativeSetUsbSoftwareGain(pcmGain)
        callbacks.applyComposedVolume()
        keepUsbExclusiveSoftwareVolumeIsolated("applyUsbExclusiveSoftwareUserVolume:$reason")
    }

    fun forceUsbFixedVolume0Db(reason: String) {
        AppPreferences.Player.volume = 1.0f
        callbacks.setExplicitSoftwareMute(false)
        engine.nativeSetUsbSoftwareGain(1.0f)
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
                engine.setPolicy(exclusive = false, bitPerfect = false, hwVol = false)
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
                engine.setPolicy(
                    exclusive = true,
                    // DSD source-direct remains a fixed/raw native transport even when hardware
                    // volume is the user-volume owner. Keep this identical to the policy that was
                    // committed before native init so nativeStart does not require a reinit.
                    bitPerfect = profile.bitPerfect || profile.dsdSourceDirect,
                    hwVol = true,
                )
                callbacks.configureTransitionGainOwner(profile, "hardware:$reason")
                engine.nativeSetUsbSoftwareGain(1.0f)
            }
            volumePath == UsbVolumePath.Fixed -> {
                engine.setPolicy(exclusive = true, bitPerfect = true, hwVol = false)
                callbacks.configureTransitionGainOwner(profile, "fixed:$reason")
                forceUsbFixedVolume0Db("applyVolumeRoute:$reason")
                AppLogger.i(TAG, "USB exclusive fixed-output path active; user volume locked at 0dB")
            }
            else -> {
                engine.setPolicy(
                    exclusive = true,
                    bitPerfect = profile.bitPerfect,
                    hwVol = AppPreferences.Player.usbVolumeMode == 1 &&
                        AppPreferences.Player.hardwareFeatureUnitEnabled && exclusive,
                )
                callbacks.configureTransitionGainOwner(profile, "software:$reason")
                val userLinear = normalizeUsbExclusiveSoftwareEntryVolume(systemLinear, reason)
                val handle = engine.currentHandle
                if (handle != 0L) {
                    callbacks.applyUsbVolume(profile, "applyVolumeRoute:$reason")
                } else {
                    engine.nativeSetUsbSoftwareGain(usbSoftwarePcmGainForLocalUi(userLinear))
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
                callbacks.setHardwareVolumeUi(v, "setUserVolume")
            }
            VolumeRoute.SYSTEM -> {
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    systemVolumeController().setMusicVolumeLinearIgnoringCallbacks(
                        linear = v,
                        flags = 0,
                        ignoreWindowMs = 300L,
                        reason = "usb_software_setUserVolume",
                    )
                    applyUsbExclusiveSoftwareUserVolume(
                        getSystemMusicVolumeLinear(),
                        "setUserVolume_local_stream",
                    )
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
                        resolveVolumeRoute() == VolumeRoute.SYSTEM
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
