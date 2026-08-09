package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the UI-facing requested-song identity while transport work is still serialized.
 *
 * This coordinator never treats a request as proof that audio has started. It only keeps the
 * visible media identity aligned with the latest accepted user action until PlayerController
 * commits the corresponding decoder item.
 */
class PlayerUiSelectionControlCoordinator(
    private val isReleased: () -> Boolean,
    private val currentSong: () -> AudioFile?,
    private val setCurrentSong: (AudioFile?) -> Unit,
    private val currentQueue: () -> PlayQueue,
    private val updateQueue: (PlayQueue) -> Unit,
    private val samePlaybackItem: (AudioFile, AudioFile) -> Boolean,
    private val persistSelection: (AudioFile) -> Unit,
    private val resetPersistedPosition: () -> Unit,
) {
    private val requestedSongState = MutableStateFlow<AudioFile?>(null)
    val requestedSongForUi: StateFlow<AudioFile?> = requestedSongState.asStateFlow()

    fun currentOrRequestedSong(): AudioFile? = requestedSongState.value ?: currentSong()

    fun requestedSong(): AudioFile? = requestedSongState.value

    fun hasRequestedSong(): Boolean = requestedSongState.value != null

    fun primeSelection(song: AudioFile) {
        if (isReleased()) return
        requestedSongState.value = song
        persistSelection(song)
        resetPersistedPosition()
    }

    fun clearRequestedSong() {
        requestedSongState.value = null
    }

    fun clearRequestedSongIfMatching(song: AudioFile) {
        val requested = requestedSongState.value ?: return
        if (samePlaybackItem(requested, song)) {
            requestedSongState.value = null
        }
    }

    fun updateCurrentSongIfSameIdentity(song: AudioFile) {
        val current = currentSong() ?: return
        if (!samePlaybackItem(current, song)) return

        setCurrentSong(song)
        val queue = currentQueue()
        val index = queue.songs.indexOfFirst { candidate -> samePlaybackItem(candidate, song) }
        if (index < 0) return

        val updatedSongs = queue.songs.toMutableList()
        updatedSongs[index] = song
        updateQueue(queue.copy(songs = updatedSongs))
    }
}
