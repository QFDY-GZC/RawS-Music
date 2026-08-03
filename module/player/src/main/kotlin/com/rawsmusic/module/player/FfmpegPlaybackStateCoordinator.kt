package com.rawsmusic.module.player

import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger

/** Publishes FFmpeg playback state and forwards terminal error/success events. */
internal class FfmpegPlaybackStateCoordinator(
    private val tag: String,
    private val isPlaying: () -> Boolean,
    private val hasAudioTrack: () -> Boolean,
    private val currentState: () -> FfmpegAudioPlayer.State,
    private val setCurrentState: (FfmpegAudioPlayer.State) -> Unit,
    private val setStateChangedAt: (Long) -> Unit,
    private val notifyStateChanged: (FfmpegAudioPlayer.State) -> Unit,
    private val reportError: (String) -> Unit,
    private val reportSuccess: () -> Unit,
) {
    fun onPlaybackError(message: String) = reportError(message)

    fun onPlaybackSuccess() = reportSuccess()

    fun setState(state: FfmpegAudioPlayer.State) {
        val oldState = currentState()
        if (oldState != state) {
            AppLogger.w(
                tag,
                "=== setState: $oldState -> $state, isPlaying=${isPlaying()}, " +
                    "audioTrack=${hasAudioTrack()} ==="
            )
            setStateChangedAt(SystemClock.elapsedRealtime())
        }
        setCurrentState(state)
        notifyStateChanged(state)
    }
}
