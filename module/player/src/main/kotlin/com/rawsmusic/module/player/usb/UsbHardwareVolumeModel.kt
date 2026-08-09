package com.rawsmusic.module.player.usb

import kotlin.math.roundToInt

/**
 * USB 硬件音量 UI 步进模型。
 * 100 步映射到 UI 百分比 [0..1]。真正的 Feature Unit 写入由 Controller 按当前
 * DAC 报告的 min/max/resolution 映射成原始值；这里的 [-60, 0] dB 仅用于旧 UI/诊断显示。
 * AppPreferences.Player.volume 永远只表示 UI 百分比 0..1，不表示音频振幅。
 */
object UsbHardwareVolumeModel {

    const val MAX_STEPS = 100
    const val DEFAULT_LINEAR_STEP = 0.04f

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
