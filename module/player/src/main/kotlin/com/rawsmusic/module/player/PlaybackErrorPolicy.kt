package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/** Tracks consecutive playback failures and exposes the conservative AudioTrack safe mode. */
internal class PlaybackErrorPolicy(
    private val tag: String,
    private val notifyError: (String) -> Unit,
) {
    private companion object {
        const val MAX_ERRORS_BEFORE_SAFE_MODE = 3
    }

    private var consecutiveErrors = 0
    @Volatile
    var safeMode: Boolean = false
        private set

    fun reportError(message: String) {
        consecutiveErrors++
        AppLogger.e(tag, "Playback error #$consecutiveErrors: $message")
        if (consecutiveErrors >= MAX_ERRORS_BEFORE_SAFE_MODE && !safeMode) {
            safeMode = true
            AppLogger.w(tag, "=== SAFE MODE ACTIVATED after $consecutiveErrors consecutive errors ===")
        }
        notifyError(message)
    }

    fun reportSuccess() {
        if (consecutiveErrors > 0) {
            AppLogger.i(tag, "Playback successful, resetting error count (was $consecutiveErrors)")
            consecutiveErrors = 0
        }
        if (safeMode) {
            AppLogger.i(tag, "=== SAFE MODE DEACTIVATED ===")
            safeMode = false
        }
    }
}
