package com.rawsmusic.core.ui.widget.player

/**
 * Every sentence target uses the same row-animation path, including multi-line seeks.
 */
internal fun shouldPullFollowingLyrics(
    previousIndex: Int,
    newIndex: Int,
    manualSeekTransition: Boolean,
    isPlaying: Boolean,
    userScrolling: Boolean,
    hasActiveInterlude: Boolean
): Boolean {
    if (hasActiveInterlude || previousIndex < 0 || newIndex == previousIndex) return false
    if (manualSeekTransition) return true
    return isPlaying && !userScrolling
}
