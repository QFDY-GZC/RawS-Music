package com.rawsmusic.module.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {

    private var playerController: PlayerController? = null

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
        playerController = controller

        playerController?.let { controller ->
            viewModelScope.launch {
                controller.playState.collect { _playState.value = it }
            }
            viewModelScope.launch {
                controller.currentSong.collect { _currentSong.value = it }
            }
            viewModelScope.launch {
                controller.queue.collect { _queue.value = it }
            }
            viewModelScope.launch {
                controller.position.collect { _position.value = it }
            }
            viewModelScope.launch {
                controller.duration.collect { _duration.value = it }
            }
            viewModelScope.launch {
                controller.repeatMode.collect { _repeatMode.value = it }
            }
            viewModelScope.launch {
                controller.isShuffle.collect { _isShuffle.value = it }
            }
        }
    }

    fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
        playerController?.play(song, queue, index)
    }

    fun playQueue(songs: List<AudioFile>, startIndex: Int = 0) {
        playerController?.playQueue(songs, startIndex)
    }

    fun playPause() {
        playerController?.playPause()
    }

    fun pause() {
        playerController?.pause()
    }

    fun resume() {
        playerController?.resume()
    }

    fun next() {
        playerController?.next()
    }

    fun previous() {
        playerController?.previous()
    }

    fun seekTo(positionMs: Long) {
        playerController?.seekTo(positionMs)
    }

    fun toggleRepeatMode() {
        playerController?.toggleRepeatMode()
    }

    fun toggleShuffle() {
        playerController?.toggleShuffle()
    }

    fun setVolume(volume: Float) {
        playerController?.setVolume(volume)
    }

    fun addToQueue(song: AudioFile) {
        playerController?.addToQueue(song)
    }

    fun removeFromQueue(index: Int) {
        playerController?.removeFromQueue(index)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
