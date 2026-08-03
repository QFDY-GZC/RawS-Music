package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode

/** Commits an explicit play request to the stable queue before decoder startup. */
internal class PlayerPlaybackQueueCommitter(
    private val currentQueue: () -> PlayQueue,
    private val setQueue: (PlayQueue) -> Unit,
    private val setCurrentSong: (AudioFile) -> Unit,
    private val sameItem: (AudioFile, AudioFile) -> Boolean,
    private val sameQueue: (List<AudioFile>, List<AudioFile>) -> Boolean,
    private val clearHistory: () -> Unit,
    private val currentRepeatMode: () -> RepeatMode,
    private val isShuffleEnabled: () -> Boolean,
    private val rebuildShuffle: () -> Unit,
) {
    fun commit(song: AudioFile, requestedQueue: List<AudioFile>, requestedIndex: Int) {
        if (requestedQueue.isNotEmpty()) {
            val safeIndex = requestedIndex.coerceIn(0, requestedQueue.size - 1)
            val existingQueue = currentQueue()
            if (sameQueue(existingQueue.songs, requestedQueue)) {
                // Manual next/previous passes the active queue back into play(). Only move
                // the cursor; rebuilding here would regenerate the random order.
                setQueue(existingQueue.copy(currentIndex = safeIndex))
            } else {
                clearHistory()
                setQueue(
                    PlayQueue(
                        songs = requestedQueue,
                        currentIndex = safeIndex,
                        repeatMode = currentRepeatMode(),
                        isShuffle = false,
                        originalSongs = emptyList(),
                    ),
                )
                if (isShuffleEnabled()) {
                    rebuildShuffle()
                    val shuffled = currentQueue()
                    val selectedIndex = shuffled.songs.indexOfFirst { sameItem(it, song) }
                    if (selectedIndex >= 0 && selectedIndex != shuffled.currentIndex) {
                        setQueue(shuffled.copy(currentIndex = selectedIndex))
                    }
                }
            }
        } else {
            val currentSongs = currentQueue().songs.toMutableList()
            val existingIndex = currentSongs.indexOfFirst {
                it.path == song.path &&
                    it.cueOffsetMs == song.cueOffsetMs &&
                    it.cueTrackIndex == song.cueTrackIndex
            }
            if (existingIndex >= 0) {
                setQueue(currentQueue().copy(currentIndex = existingIndex))
            } else {
                currentSongs.add(song)
                setQueue(
                    PlayQueue(
                        songs = currentSongs,
                        currentIndex = currentSongs.lastIndex,
                    ),
                )
            }
        }
        setCurrentSong(song)
    }
}
