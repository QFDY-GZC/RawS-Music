package com.rawsmusic.module.data.prefs

enum class HdrScene { HOME, LIBRARY, PLAYER, LYRICS, SETTINGS }

data class HdrWindowSettings(
    val enabled: Boolean = false,
    val scenes: Set<HdrScene> = setOf(HdrScene.PLAYER),
    val desiredHeadroom: Float = 0f,
) {
    fun requestedHeadroom(sdk: Int): Float = if (sdk < 35 || !desiredHeadroom.isFinite() || desiredHeadroom <= 0f) {
        0f
    } else desiredHeadroom.coerceIn(1f, 8f)
    fun requests(current: HdrScene, transition: HdrScene? = null): Boolean =
        enabled && (current in scenes || transition in scenes)
}

fun observedHdrRatio(sdk: Int, available: Boolean, value: Float): Float? =
    value.takeIf { sdk >= 34 && available && it.isFinite() && it >= 1f }

fun canKeepHdrWindow(sdk: Int, supported: Boolean, hardware: Boolean, resumed: Boolean, requested: Boolean): Boolean =
    sdk >= 34 && supported && hardware && resumed && requested
