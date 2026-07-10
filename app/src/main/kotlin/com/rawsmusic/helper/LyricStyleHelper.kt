package com.rawsmusic.helper

import com.rawsmusic.module.player.PlayerController

class LyricStyleHelper(
    private val isLyricBackgroundLight: () -> Boolean,
    private val getPlayerController: () -> PlayerController?
) {
    fun applyLyricColors() {
        com.rawsmusic.core.ui.theme.ThemeManager.isLightBackground = isLyricBackgroundLight()
    }
}
