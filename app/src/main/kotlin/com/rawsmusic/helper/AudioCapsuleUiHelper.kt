package com.rawsmusic.helper

class AudioCapsuleUiHelper(
    private val isTransitioning: () -> Boolean,
    private val isPlayerScene: () -> Boolean,
    private val audioInfoCapsuleHelper: AudioInfoCapsuleHelper,
    private val getCurrentLyricText: () -> String
) {
    fun updateHiresBadge() {
        audioInfoCapsuleHelper.updateHiresBadge(isTransitioning(), isPlayerScene())
    }

    fun isMusicSymbolOnly(text: String): Boolean {
        return audioInfoCapsuleHelper.isMusicSymbolOnly(text)
    }

    fun updateText() {
        audioInfoCapsuleHelper.currentLyricText = getCurrentLyricText()
        audioInfoCapsuleHelper.updateText()
    }
}
