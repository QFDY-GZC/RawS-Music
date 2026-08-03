package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences

internal class AndroidDvcController(
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val systemVolume: () -> Float,
        val systemVolumeStep: () -> Int,
        val systemVolumeMaxStep: () -> Int,
        val systemVolumeDbAt: (Int) -> Float?,
        val setSystemVolumeStepSilently: (Int, String) -> Unit,
        val setNativeDvc: (enabled: Boolean, gain: Float, noDvcHeadroomDb: Float) -> Unit,
    )

    private var routeActive = false

    fun isRequested(): Boolean = AppPreferences.Player.androidDvcEnabled

    fun isActive(usbExclusive: Boolean): Boolean = isRequested() && !usbExclusive

    fun logicalVolume(): Float =
        AppPreferences.Player.androidDvcVolume.coerceIn(0f, 1f)

    fun setEnabled(enabled: Boolean, usbExclusive: Boolean, reason: String) {
        if (enabled == isRequested()) {
            applyRoute(usbExclusive, reason)
            return
        }

        if (enabled) {
            val systemStep = callbacks.systemVolumeStep()
            val seed = callbacks.systemVolumeDbAt(systemStep)
                ?.takeIf(Float::isFinite)
                ?.let(AndroidDvcVolumeCurve::dbToLinear)
                ?: callbacks.systemVolume().coerceIn(0f, 1f)
            AppPreferences.Player.androidDvcVolume = seed
            AppPreferences.Player.volume = seed
        }
        AppPreferences.Player.androidDvcEnabled = enabled
        applyRoute(usbExclusive, reason)
    }

    fun applyRoute(usbExclusive: Boolean, reason: String) {
        val active = isActive(usbExclusive)
        val logical = logicalVolume()

        if (active) {
            applyActiveVolume(logical, reason)
            AppPreferences.Player.volume = logical
        } else {
            applyNative(false, 1f)
        }
        routeActive = active
        if (!active) {
            AppLogger.i(
                TAG,
                "DVC_ROUTE active=false requested=${isRequested()} usb=$usbExclusive reason=$reason"
            )
        }
    }

    fun setLogicalVolume(linear: Float, reason: String) {
        val volume = linear.coerceIn(0f, 1f)
        AppPreferences.Player.androidDvcVolume = volume
        AppPreferences.Player.volume = volume
        applyActiveVolume(volume, reason)
    }

    fun syncFromSystemVolume(reason: String) {
        val systemStep = callbacks.systemVolumeStep()
        val logical = callbacks.systemVolumeDbAt(systemStep)
            ?.takeIf(Float::isFinite)
            ?.let(AndroidDvcVolumeCurve::dbToLinear)
            ?: callbacks.systemVolume().coerceIn(0f, 1f)
        AppPreferences.Player.androidDvcVolume = logical
        AppPreferences.Player.volume = logical
        applyNative(true, 1f)
        AppLogger.i(
            TAG,
            "DVC_SYSTEM_SYNC systemStep=$systemStep logical=$logical nativeGain=1.0 reason=$reason"
        )
    }

    fun adjust(direction: Int, reason: String) {
        if (direction == 0) return
        val step = AndroidDvcVolumeCurve.linearToStep(logicalVolume())
        val target = AndroidDvcVolumeCurve.stepToLinear(step + if (direction > 0) 1 else -1)
        setLogicalVolume(target, reason)
    }

    fun currentStep(): Int = AndroidDvcVolumeCurve.linearToStep(logicalVolume())

    fun release(reason: String) {
        applyNative(false, 1f)
        routeActive = false
        AppLogger.i(TAG, "DVC_RELEASE logical=${logicalVolume()} reason=$reason")
    }

    private fun applyActiveVolume(linear: Float, reason: String) {
        val maxStep = callbacks.systemVolumeMaxStep()
        val plan = AndroidDvcVolumeCurve.plan(
            linear = linear,
            systemMaxStep = maxStep,
            systemDbAt = callbacks.systemVolumeDbAt,
        )
        applyNative(true, plan.nativeGain)
        if (callbacks.systemVolumeStep() != plan.systemStep) {
            callbacks.setSystemVolumeStepSilently(plan.systemStep, "dvc_coarse:$reason")
        }
        AppLogger.i(
            TAG,
            "DVC_VOLUME linear=$linear targetDb=${plan.targetDb} systemStep=${plan.systemStep}/$maxStep " +
                "systemDb=${plan.systemDb} nativeGain=${plan.nativeGain} reason=$reason"
        )
    }

    fun refreshHeadroom(usbExclusive: Boolean, reason: String) {
        applyRoute(usbExclusive, reason)
    }

    private fun applyNative(enabled: Boolean, gain: Float) {
        callbacks.setNativeDvc(
            enabled,
            gain,
            AppPreferences.Player.androidNoDvcHeadroomDb,
        )
    }

    companion object {
        private const val TAG = "AndroidDvc"
    }
}
