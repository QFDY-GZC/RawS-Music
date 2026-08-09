package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode

/** Applies non-shuffle queue boundaries without mutating the canonical queue. */
internal object QueueNavigationPolicy {
    fun next(queue: PlayQueue): AudioFile? {
        if (queue.songs.isEmpty()) return null
        val nextIndex = when (queue.repeatMode) {
            RepeatMode.ONE -> queue.currentIndex
            RepeatMode.ALL -> (queue.currentIndex + 1) % queue.songs.size
            RepeatMode.OFF -> {
                if (queue.currentIndex < queue.songs.size - 1) queue.currentIndex + 1 else return null
            }
        }
        return queue.songs.getOrNull(nextIndex)
    }

    fun previous(queue: PlayQueue): AudioFile? {
        if (queue.songs.isEmpty()) return null
        val previousIndex = when (queue.repeatMode) {
            RepeatMode.ONE -> queue.currentIndex
            RepeatMode.ALL -> (queue.currentIndex - 1 + queue.songs.size) % queue.songs.size
            RepeatMode.OFF -> {
                if (queue.currentIndex > 0) queue.currentIndex - 1 else return null
            }
        }
        return queue.songs.getOrNull(previousIndex)
    }
}
