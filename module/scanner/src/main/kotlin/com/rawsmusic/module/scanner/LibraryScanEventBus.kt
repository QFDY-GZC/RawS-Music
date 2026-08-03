package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object LibraryScanEventBus {
    private val _scanState = MutableStateFlow(ScanUiState.idle())
    val scanState = _scanState.asStateFlow()

    private val _songsUpdated = MutableSharedFlow<List<AudioFile>>(replay = 0, extraBufferCapacity = 1)
    val songsUpdated = _songsUpdated.asSharedFlow()

    fun updateState(state: ScanUiState) { _scanState.value = state }
    fun reset() { _scanState.value = ScanUiState.idle() }
    fun tryEmitSongs(songs: List<AudioFile>) { _songsUpdated.tryEmit(songs) }
}
