package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/** Coordinates transient vocal/instrumental emphasis for the active decoder. */
internal class PlayerRealtimeStemCoordinator(
    private val tag: String,
    private val player: () -> FfmpegAudioPlayer,
    private val usbExclusiveActive: () -> Boolean,
) {
    fun setProcessing(enabled: Boolean, mode: Int, strength: Float) {
        val normalizedMode = mode.coerceIn(0, 1)
        val normalizedStrength = strength.coerceIn(0f, 1f)
        player().realtimeStemMode = normalizedMode
        player().realtimeStemStrength = normalizedStrength
        player().realtimeStemEnabled = enabled
        AppLogger.i(
            tag,
            "AI_REALTIME_STEM controller enabled=$enabled mode=$normalizedMode " +
                "strength=$normalizedStrength usbExclusive=${usbExclusiveActive()}",
        )
    }

    fun setEnabled(enabled: Boolean) {
        player().realtimeStemEnabled = enabled
        AppLogger.i(tag, "AI_REALTIME_STEM controller enabled=$enabled")
    }

    fun setMode(mode: Int) {
        val normalizedMode = mode.coerceIn(0, 1)
        player().realtimeStemMode = normalizedMode
        AppLogger.i(tag, "AI_REALTIME_STEM controller mode=$normalizedMode")
    }

    fun setStrength(strength: Float) {
        player().realtimeStemStrength = strength.coerceIn(0f, 1f)
    }
}
