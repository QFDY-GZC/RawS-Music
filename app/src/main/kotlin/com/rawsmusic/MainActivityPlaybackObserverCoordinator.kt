package com.rawsmusic

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Collects the controller flows used by the main Compose shell. */
internal class MainActivityPlaybackObserverCoordinator(
    private val scope: CoroutineScope,
    private val controller: () -> PlayerController?,
    private val onPlaybackState: (PlayState) -> Unit,
    private val onCurrentSong: (AudioFile) -> Unit,
    private val onRequestedSongChanged: () -> Unit,
    private val onPosition: (positionMs: Long, durationMs: Long) -> Unit,
    private val onSampleRateChanged: (Int) -> Unit,
    private val onPlayModeChanged: (PlayMode) -> Unit,
) {
    fun start() {
        scope.launch(Dispatchers.Main) {
            controller()?.playState?.collect { state ->
                onPlaybackState(state)
            }
        }
        scope.launch(Dispatchers.Main) {
            controller()?.currentSong?.collect { song ->
                if (song != null) onCurrentSong(song)
            }
        }
        scope.launch(Dispatchers.Main) {
            controller()?.requestedSongForUi?.collect {
                // A cleared request commits the mini-player back to the decoder-owned song.
                onRequestedSongChanged()
            }
        }
        scope.launch(Dispatchers.Main) {
            controller()?.position?.collect { positionMs ->
                val durationMs = controller()?.duration?.value ?: 0L
                onPosition(positionMs, durationMs)
            }
        }
        scope.launch(Dispatchers.Main) {
            controller()?.usbOutputSampleRate?.collect { sampleRate ->
                onSampleRateChanged(sampleRate)
            }
        }
        scope.launch(Dispatchers.Main) {
            controller()?.playMode?.collect { mode ->
                onPlayModeChanged(mode)
            }
        }
    }
}
