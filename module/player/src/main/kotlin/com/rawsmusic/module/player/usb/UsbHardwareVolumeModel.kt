package com.rawsmusic.module.player.usb

import kotlin.math.roundToInt

/**
 * USB 硬件音量步进模型。
 * 100 步映射到 UI 音量百分比 [0..1]，再换算到 [-60, 0] dB 硬件值。
 * AppPreferences.Player.volume 永远只表示 UI 百分比 0..1，不表示音频振幅。
 */
object UsbHardwareVolumeModel {

    const val MAX_STEPS = 100

    private const val MIN_DB = -60
    private const val MAX_DB = 0

    fun stepToUiVolume(step: Int): Float {
        return step.coerceIn(0, MAX_STEPS) / MAX_STEPS.toFloat()
    }

    fun uiVolumeToStep(volume: Float): Int {
        return (volume.coerceIn(0f, 1f) * MAX_STEPS).roundToInt()
            .coerceIn(0, MAX_STEPS)
    }

    fun uiVolumeToHardwareDb(volume: Float): Int {
        val v = volume.coerceIn(0f, 1f)
        return (MIN_DB + (MAX_DB - MIN_DB) * v)
            .roundToInt()
            .coerceIn(MIN_DB, MAX_DB)
    }

    fun stepToHardwareDb(step: Int): Int {
        return uiVolumeToHardwareDb(stepToUiVolume(step))
    }
}
