package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState

/** Serial publication boundary; metadata is committed before playback is reannounced. */
internal fun publishMediaSessionMetadata(
    commitMetadata: () -> Unit,
    currentState: () -> PlayState,
    publishPlaybackState: (PlayState) -> Unit,
    publishNotification: () -> Unit,
    metadataOnly: Boolean = false,
) {
    commitMetadata()
    // Mid-track lyric changes are metadata, not transport events. In particular,
    // reannouncing playback can race AVRCP's metadata callback/coalescing window.
    if (metadataOnly) return
    publishPlaybackState(currentState())
    publishNotification()
}
