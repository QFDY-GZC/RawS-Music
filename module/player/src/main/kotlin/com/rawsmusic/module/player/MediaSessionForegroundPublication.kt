package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState

/** Reconcile the existing session on foreground entry without changing the audio transport. */
internal fun refreshMediaSessionOnForeground(
    hasMediaIdentity: Boolean,
    state: PlayState,
    publishPlaybackState: (PlayState) -> Unit,
    publishNotification: () -> Unit,
) {
    if (!shouldKeepMediaSessionActive(hasMediaIdentity, state)) return
    publishPlaybackState(state)
    publishNotification()
}
