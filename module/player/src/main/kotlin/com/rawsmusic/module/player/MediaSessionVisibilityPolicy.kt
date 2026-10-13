package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState

/** A paused selection remains addressable by SystemUI; only stop/clear releases the session. */
internal fun shouldKeepMediaSessionActive(hasMediaIdentity: Boolean, state: PlayState): Boolean =
    hasMediaIdentity && (state == PlayState.PLAYING || state == PlayState.PAUSED || state == PlayState.PREPARING)
