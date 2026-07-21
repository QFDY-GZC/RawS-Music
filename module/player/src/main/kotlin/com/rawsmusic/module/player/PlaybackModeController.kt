package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.core.common.model.ShuffleMode
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns observable playback-mode state and the queue rewrite required when shuffle changes.
 *
 * PlayerController still decides when to advance tracks; this class only keeps PlayMode,
 * RepeatMode, shuffle state, persistence, and ShuffleQueueController transitions coherent.
 */
internal class PlaybackModeController(
    private val shuffleQueueController: ShuffleQueueController,
    private val currentQueue: () -> PlayQueue,
    private val updateQueue: (PlayQueue) -> Unit,
    private val persistState: () -> Unit,
) {
    private val _repeatMode = MutableStateFlow(AppPreferences.Player.repeatMode)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(AppPreferences.Player.isShuffle)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _playMode = MutableStateFlow(AppPreferences.Player.playMode)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    val currentRepeatMode: RepeatMode get() = _repeatMode.value
    val currentPlayMode: PlayMode get() = _playMode.value
    val isShuffleEnabled: Boolean get() = _isShuffle.value

    fun toggleRepeatMode() {
        val modes = RepeatMode.entries
        val currentIndex = modes.indexOf(_repeatMode.value)
        val nextMode = modes[(currentIndex + 1) % modes.size]
        setRepeatMode(nextMode)
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        AppPreferences.Player.repeatMode = mode
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle
        AppPreferences.Player.isShuffle = newShuffle
        _playMode.value = PlayMode.from(
            ShuffleMode.fromBoolean(newShuffle),
            _repeatMode.value,
        )
        AppPreferences.Player.playMode = _playMode.value
        if (newShuffle) enableShuffle() else disableShuffle()
    }

    fun cyclePlayMode() {
        setPlayMode(PlayMode.cycle(_playMode.value))
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        AppPreferences.Player.playMode = mode
        applyPlayMode(mode)
    }

    fun rebuildShuffleForCurrentQueue() {
        if (_isShuffle.value) enableShuffle()
    }

    private fun applyPlayMode(mode: PlayMode) {
        val wasShuffle = _isShuffle.value
        _isShuffle.value = mode.shuffleMode.isOn
        _repeatMode.value = mode.repeatMode
        AppPreferences.Player.isShuffle = _isShuffle.value
        AppPreferences.Player.repeatMode = _repeatMode.value

        if (_isShuffle.value && !wasShuffle && currentQueue().songs.size > 1) {
            enableShuffle()
        } else if (!_isShuffle.value && wasShuffle) {
            disableShuffle()
        }
    }

    private fun enableShuffle() {
        val shuffledQueue = shuffleQueueController.enable(currentQueue(), _repeatMode.value) ?: return
        updateQueue(shuffledQueue)
        persistState()
    }

    private fun disableShuffle() {
        shuffleQueueController.disable(currentQueue(), _repeatMode.value)?.let(updateQueue)
        persistState()
    }
}
