package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState

/** Decides whether destroying the UI host may also dispose the playback runtime. */
internal object PlaybackRuntimeRetentionPolicy {
    fun shouldRetain(
        controllerState: PlayState?,
        serviceState: PlayState?,
        usbActive: Boolean,
        persistedUsbActive: Boolean,
        hasRequestedSong: Boolean,
    ): Boolean {
        return usbActive ||
            persistedUsbActive ||
            hasRequestedSong ||
            controllerState.keepsPlaybackSessionAlive() ||
            serviceState.keepsPlaybackSessionAlive()
    }

    private fun PlayState?.keepsPlaybackSessionAlive(): Boolean =
        this == PlayState.PLAYING || this == PlayState.PAUSED || this == PlayState.PREPARING
}
