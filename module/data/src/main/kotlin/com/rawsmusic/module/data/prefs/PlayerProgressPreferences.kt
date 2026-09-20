package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlayerProgressPreferences {
    const val COLOR_MODE_MIUIX = 0
    const val COLOR_MODE_ALBUM_ART = 1

    val supportedWaveformBarCounts = intArrayOf(100, 160, 200, 280)

    private val _progressStyle = MutableStateFlow(AppPreferences.UI.immersiveProgressStyle.coerceIn(0, 3))
    val progressStyle = _progressStyle.asStateFlow()

    private val _climaxEnabled = MutableStateFlow(AppPreferences.UI.immersiveClimaxEnabled)
    val climaxEnabled = _climaxEnabled.asStateFlow()

    private val _holdWhenPaused = MutableStateFlow(AppPreferences.UI.immersiveWaveformHoldWhenPaused)
    val holdWhenPaused = _holdWhenPaused.asStateFlow()

    private val _waveformBarCount = MutableStateFlow(normalizeBarCount(AppPreferences.UI.immersiveWaveformBarCount))
    val waveformBarCount = _waveformBarCount.asStateFlow()

    private val _waveformColorMode = MutableStateFlow(AppPreferences.UI.immersiveWaveformColorMode.coerceIn(0, 1))
    val waveformColorMode = _waveformColorMode.asStateFlow()

    private val _remainingColor = MutableStateFlow(AppPreferences.UI.immersiveWaveformRemainingColor)
    val remainingColor = _remainingColor.asStateFlow()

    private val _playedColor = MutableStateFlow(AppPreferences.UI.immersiveWaveformPlayedColor)
    val playedColor = _playedColor.asStateFlow()

    private val _climaxColor = MutableStateFlow(AppPreferences.UI.immersiveWaveformClimaxColor)
    val climaxColor = _climaxColor.asStateFlow()

    var progressStyleValue: Int
        get() = _progressStyle.value
        set(value) {
            val next = value.coerceIn(0, 3)
            if (_progressStyle.value == next) return
            _progressStyle.value = next
            AppPreferences.UI.immersiveProgressStyle = next
        }

    var climaxEnabledValue: Boolean
        get() = _climaxEnabled.value
        set(value) {
            if (_climaxEnabled.value == value) return
            _climaxEnabled.value = value
            AppPreferences.UI.immersiveClimaxEnabled = value
        }

    var holdWhenPausedValue: Boolean
        get() = _holdWhenPaused.value
        set(value) {
            if (_holdWhenPaused.value == value) return
            _holdWhenPaused.value = value
            AppPreferences.UI.immersiveWaveformHoldWhenPaused = value
        }

    var waveformBarCountValue: Int
        get() = _waveformBarCount.value
        set(value) {
            val next = normalizeBarCount(value)
            if (_waveformBarCount.value == next) return
            _waveformBarCount.value = next
            AppPreferences.UI.immersiveWaveformBarCount = next
        }

    var waveformColorModeValue: Int
        get() = _waveformColorMode.value
        set(value) {
            val next = value.coerceIn(0, 1)
            if (_waveformColorMode.value == next) return
            _waveformColorMode.value = next
            AppPreferences.UI.immersiveWaveformColorMode = next
        }

    var remainingColorValue: Int
        get() = _remainingColor.value
        set(value) {
            if (_remainingColor.value == value) return
            _remainingColor.value = value
            AppPreferences.UI.immersiveWaveformRemainingColor = value
        }

    var playedColorValue: Int
        get() = _playedColor.value
        set(value) {
            if (_playedColor.value == value) return
            _playedColor.value = value
            AppPreferences.UI.immersiveWaveformPlayedColor = value
        }

    var climaxColorValue: Int
        get() = _climaxColor.value
        set(value) {
            if (_climaxColor.value == value) return
            _climaxColor.value = value
            AppPreferences.UI.immersiveWaveformClimaxColor = value
        }

    private fun normalizeBarCount(value: Int): Int =
        supportedWaveformBarCounts.minByOrNull { kotlin.math.abs(it - value) } ?: 160
}
