package com.rawsmusic.module.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.module.player.control.PlayerControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {

    private var playerControl: PlayerControl? = null
    private var controlBindingJob: Job? = null

    private val _playState = MutableStateFlow(PlayState.IDLE)
    val playState: StateFlow<PlayState> = _playState.asStateFlow()

    private val _currentSong = MutableStateFlow<AudioFile?>(null)
    val currentSong: StateFlow<AudioFile?> = _currentSong.asStateFlow()

    private val _queue = MutableStateFlow(PlayQueue())
    val queue: StateFlow<PlayQueue> = _queue.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    fun init(context: Context) {
        // PlayerController由MainActivity管理，此处仅确保PlayerService运行
        if (!PlayerService.isRunning) {
            PlayerService.ensureServiceStarted(
                context,
                "player_view_model_init"
            )
        }
    }

    fun setPlayerController(controller: PlayerController) {
        setPlayerControl(controller.controls)
    }

    fun setPlayerControl(control: PlayerControl) {
        playerControl = control
        controlBindingJob?.cancel()
        controlBindingJob = viewModelScope.launch {
            launch { control.playState.collect { _playState.value = it } }
            launch { control.currentSong.collect { _currentSong.value = it } }
            launch { control.queue.collect { _queue.value = it } }
            launch { control.position.collect { _position.value = it } }
            launch { control.duration.collect { _duration.value = it } }
            launch { control.repeatMode.collect { _repeatMode.value = it } }
            launch { control.isShuffle.collect { _isShuffle.value = it } }
        }
    }

    fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
        playerControl?.play(song, queue, index)
    }

    fun playQueue(songs: List<AudioFile>, startIndex: Int = 0) {
        playerControl?.playQueue(songs, startIndex)
    }

    fun playPause() {
        playerControl?.playPause()
    }

    fun pause() {
        playerControl?.pause()
    }

    fun resume() {
        playerControl?.resume()
    }

    fun next() {
        playerControl?.next()
    }

    fun previous() {
        playerControl?.previous()
    }

    fun seekTo(positionMs: Long) {
        playerControl?.seekTo(positionMs)
    }

    fun toggleRepeatMode() {
        playerControl?.toggleRepeatMode()
    }

    fun toggleShuffle() {
        playerControl?.toggleShuffle()
    }

    fun setVolume(volume: Float) {
        playerControl?.setVolume(volume)
    }

    fun addToQueue(song: AudioFile) {
        playerControl?.addToQueue(song)
    }

    fun removeFromQueue(index: Int) {
        playerControl?.removeFromQueue(index)
    }

    override fun onCleared() {
        controlBindingJob?.cancel()
        playerControl = null
        super.onCleared()
    }
}
