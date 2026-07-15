package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.module.data.prefs.AppPreferences
import java.util.BitSet
import java.util.Random

/** Owns shuffle ordering, history, and restoration of the pre-shuffle queue. */
internal class ShuffleQueueController(
    private val statePersistence: PlayerStatePersistence
) {
    private var originalQueue: List<AudioFile> = emptyList()
    private val pendingIndices = mutableListOf<Int>()
    private val history = ArrayDeque<Int>()
    private val random = Random()
    private var reservedPreviousIndex: Int? = null

    fun enable(queue: PlayQueue, repeatMode: RepeatMode): PlayQueue? {
        if (queue.songs.size <= 1) return null

        originalQueue = queue.songs.toList()
        AppPreferences.Player.originalQueueSongsJson =
            statePersistence.encodeSongList(originalQueue)

        val currentSong = queue.currentSong ?: return null
        val remaining = queue.songs.filter { it.id != currentSong.id }
        val shuffledSongs = buildList {
            add(currentSong)
            addAll(shuffleWithoutReplacement(remaining))
        }

        pendingIndices.clear()
        history.clear()
        reservedPreviousIndex = null
        history.addLast(0)
        return PlayQueue(
            songs = shuffledSongs,
            currentIndex = 0,
            repeatMode = repeatMode,
            isShuffle = true,
            originalSongs = originalQueue
        )
    }

    fun disable(queue: PlayQueue, repeatMode: RepeatMode): PlayQueue? {
        val currentSong = queue.currentSong
        val restored = originalQueue.ifEmpty {
            statePersistence.decodeSongList(AppPreferences.Player.originalQueueSongsJson)
        }
        val restoredQueue = if (restored.isNotEmpty() && currentSong != null) {
            PlayQueue(
                songs = restored,
                currentIndex = restored.indexOfFirst { it.id == currentSong.id }.coerceAtLeast(0),
                repeatMode = repeatMode,
                isShuffle = false,
                originalSongs = emptyList()
            )
        } else {
            null
        }

        originalQueue = emptyList()
        pendingIndices.clear()
        history.clear()
        reservedPreviousIndex = null
        AppPreferences.Player.originalQueueSongsJson = ""
        return restoredQueue
    }

    fun nextIndex(queue: PlayQueue): Int {
        val nextIndex = peekNextIndex(queue)
        if (nextIndex < 0) return nextIndex
        pendingIndices.remove(nextIndex)
        history.addLast(nextIndex)
        if (history.size > queue.songs.size * 2) {
            repeat(queue.songs.size / 2) { history.removeFirst() }
        }
        return nextIndex
    }

    fun peekNextIndex(queue: PlayQueue): Int {
        val size = queue.songs.size
        if (size <= 1) return if (size == 1) 0 else -1

        if (pendingIndices.isEmpty()) {
            val unplayed = (0 until size).filter { it != queue.currentIndex }
            pendingIndices.addAll(shuffleWithoutReplacement(unplayed))
        }

        return pendingIndices.firstOrNull() ?: -1
    }

    fun previousIndex(queue: PlayQueue): Int {
        val size = queue.songs.size
        if (size <= 1) return if (size == 1) 0 else -1

        val currentIndex = queue.currentIndex
        if (currentIndex >= 0) pendingIndices.add(0, currentIndex)
        if (history.size >= 2) {
            history.removeLast()
            reservedPreviousIndex = null
            return history.last()
        }
        return peekPreviousIndex(queue).also { reservedPreviousIndex = null }
    }

    fun peekPreviousIndex(queue: PlayQueue): Int {
        val size = queue.songs.size
        if (size <= 1) return if (size == 1) 0 else -1
        if (history.size >= 2) return history.elementAt(history.size - 2)
        reservedPreviousIndex?.takeIf { it in 0 until size && it != queue.currentIndex }?.let { return it }
        val candidates = (0 until size).filter { it != queue.currentIndex }
        return candidates[random.nextInt(candidates.size)].also { reservedPreviousIndex = it }
    }

    fun nextIndexForGapless(currentIndex: Int, size: Int, wrap: Boolean): Int {
        if (size <= 0) return -1
        val pendingPosition = pendingIndices.indexOf(currentIndex)
        if (pendingPosition >= 0 && pendingPosition + 1 < pendingIndices.size) {
            return pendingIndices[pendingPosition + 1]
        }
        if (wrap && pendingIndices.isNotEmpty()) return pendingIndices[0]
        if (!wrap) return -1
        val next = currentIndex + 1
        return if (next >= size) 0 else next
    }

    private fun <T> shuffleWithoutReplacement(items: List<T>): List<T> {
        if (items.size <= 1) return items.toList()
        val result = ArrayList<T>(items.size)
        val used = BitSet(items.size)
        while (result.size < items.size) {
            var index = random.nextInt(items.size)
            while (used[index]) {
                index = random.nextInt(items.size)
            }
            used.set(index)
            result.add(items[index])
        }
        return result
    }
}
