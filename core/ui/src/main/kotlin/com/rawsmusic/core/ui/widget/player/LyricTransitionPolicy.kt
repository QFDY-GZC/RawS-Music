package com.rawsmusic.core.ui.widget.player

/**
 * A manual forward lyric tap uses the same item-pulling path as an animated forward follow.
 * Natural playback keeps the adjacent-line guard so seek/jump updates do not create a false wave.
 */
internal fun shouldPullFollowingLyrics(
    previousIndex: Int,
    newIndex: Int,
    manualSeekTransition: Boolean,
    isPlaying: Boolean,
    userScrolling: Boolean,
    hasActiveInterlude: Boolean
): Boolean {
    if (hasActiveInterlude || previousIndex < 0 || newIndex <= previousIndex) return false
    if (manualSeekTransition) return true
    return isPlaying && !userScrolling && newIndex - previousIndex in 1..2
}
