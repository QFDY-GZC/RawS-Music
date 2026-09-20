package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
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
    private val initialPlayMode = AppPreferences.Player.playMode

    private val _repeatMode = MutableStateFlow(initialPlayMode.repeatMode)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(initialPlayMode.shuffleMode.isOn)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _playMode = MutableStateFlow(initialPlayMode)
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
        // The app intentionally exposes four modes, while Reference keeps repeat and shuffle as
        // orthogonal service flags.  Do not invent a fifth UI mode for the unsupported
        // shuffle+one combination: collapse it to the nearest existing mode and keep all three
        // observable values coherent.  Repeat OFF with shuffle OFF remains a valid service state
        // even though the compact mode label is the sequential one.
        val wasShuffle = _isShuffle.value
        val nextShuffle = if (mode == RepeatMode.ONE) false else wasShuffle
        _isShuffle.value = nextShuffle
        _repeatMode.value = mode
        _playMode.value = when {
            mode == RepeatMode.ONE -> PlayMode.REPEAT_ONE
            nextShuffle && mode == RepeatMode.OFF -> PlayMode.SHUFFLE_ONCE
            nextShuffle -> PlayMode.SHUFFLE_ALL
            else -> PlayMode.SEQUENTIAL
        }
        AppPreferences.Player.isShuffle = nextShuffle
        AppPreferences.Player.repeatMode = mode
        AppPreferences.Player.playMode = _playMode.value
        updateQueue(
            currentQueue().copy(
                repeatMode = mode,
                isShuffle = nextShuffle,
                originalSongs = emptyList(),
            )
        )
        if (nextShuffle != wasShuffle) {
            if (nextShuffle) enableShuffle() else disableShuffle()
        } else {
            persistState()
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle
        if (newShuffle && _repeatMode.value == RepeatMode.ONE) {
            // No extra shuffle+repeat-one mode is exposed in this product. Match the existing
            // four-state picker by entering shuffle-all when shuffle is explicitly enabled.
            _repeatMode.value = RepeatMode.ALL
            AppPreferences.Player.repeatMode = RepeatMode.ALL
        }
        AppPreferences.Player.isShuffle = newShuffle
        _playMode.value = if (newShuffle) {
            if (_repeatMode.value == RepeatMode.OFF) PlayMode.SHUFFLE_ONCE else PlayMode.SHUFFLE_ALL
        } else if (_repeatMode.value == RepeatMode.ONE) {
            PlayMode.REPEAT_ONE
        } else {
            PlayMode.SEQUENTIAL
        }
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
        updateQueue(
            currentQueue().copy(
                repeatMode = _repeatMode.value,
                isShuffle = _isShuffle.value,
                originalSongs = emptyList(),
            )
        )

        if (_isShuffle.value && !wasShuffle && currentQueue().songs.size > 1) {
            enableShuffle()
        } else if (!_isShuffle.value && wasShuffle) {
            disableShuffle()
        } else {
            persistState()
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
