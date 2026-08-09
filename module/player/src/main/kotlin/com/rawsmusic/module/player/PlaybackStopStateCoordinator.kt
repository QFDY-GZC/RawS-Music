package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/** Resets the transient playback state before the owned resources are retired. */
internal class PlaybackStopStateCoordinator(
    private val clearFade: (String) -> Unit,
    private val invalidateUsbSerial: (String) -> Unit,
    private val resetUsbRecoveryFuse: (String) -> Unit,
    private val resetPlaybackFlags: () -> Unit,
    private val clearSeekState: () -> Unit,
    private val clearUsbState: () -> Unit,
    private val tag: String,
) {
    fun reset(reason: String) {
        clearFade(reason)
        invalidateUsbSerial(reason)
        resetUsbRecoveryFuse(reason)
        resetPlaybackFlags()
        clearSeekState()
        clearUsbState()
        AppLogger.d(tag, "playback stop state reset reason=$reason")
    }
}
