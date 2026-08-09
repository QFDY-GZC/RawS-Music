package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlin.random.Random

/**
 * Stable shuffle traversal over an immutable canonical queue.
 *
 * The old implementation physically reordered PlayQueue.songs and later attempted to restore a
 * second serialized copy. That made queue identity depend on the current mode and allowed any
 * play()/next()/previous() path to rebuild it. This controller only moves an index cursor.
 */
internal class ShuffleQueueController(
    private val statePersistence: PlayerStatePersistence
) {
    private val traversal = mutableListOf<Int>()
    private var cursor = -1
    private var queueSignature = ""
    private val random = Random.Default

    fun enable(queue: PlayQueue, repeatMode: RepeatMode): PlayQueue? {
        if (queue.songs.size <= 1) return null
        ensureTraversal(queue, forceNew = true)
        return queue.copy(
            repeatMode = repeatMode,
            isShuffle = true,
            originalSongs = emptyList(),
        )
    }

    fun disable(queue: PlayQueue, repeatMode: RepeatMode): PlayQueue? {
        clearTraversal()
        AppPreferences.Player.originalQueueSongsJson = ""
        return queue.copy(
            repeatMode = repeatMode,
            isShuffle = false,
            originalSongs = emptyList(),
        )
    }

    fun nextIndex(queue: PlayQueue): Int {
        ensureTraversal(queue)
        if (cursor + 1 < traversal.size) {
            cursor++
            persistTraversal()
            return traversal[cursor]
        }
        if (AppPreferences.Player.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ONCE) {
            return -1
        }
        rebuildCycle(queue)
        return traversal.getOrNull(cursor) ?: -1
    }

    fun peekNextIndex(queue: PlayQueue): Int {
        ensureTraversal(queue)
        traversal.getOrNull(cursor + 1)?.let { return it }
        if (AppPreferences.Player.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ONCE) {
            return -1
        }
        return traversal.firstOrNull { it != queue.currentIndex } ?: queue.currentIndex
    }

    fun previousIndex(queue: PlayQueue): Int {
        ensureTraversal(queue)
        if (cursor > 0) {
            cursor--
            persistTraversal()
            return traversal[cursor]
        }
        if (AppPreferences.Player.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL &&
            traversal.isNotEmpty()
        ) {
            cursor = traversal.lastIndex
            persistTraversal()
            return traversal[cursor]
        }
        return queue.currentIndex
    }

    fun peekPreviousIndex(queue: PlayQueue): Int {
        ensureTraversal(queue)
        if (cursor > 0) return traversal[cursor - 1]
        return if (AppPreferences.Player.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL) {
            traversal.lastOrNull() ?: queue.currentIndex
        } else {
            queue.currentIndex
        }
    }

    fun nextIndexForGapless(currentIndex: Int, size: Int, wrap: Boolean): Int {
        if (size <= 0) return -1
        val position = traversal.indexOf(currentIndex)
        if (position >= 0 && position + 1 < traversal.size) return traversal[position + 1]
        return if (wrap) traversal.firstOrNull { it != currentIndex } ?: currentIndex else -1
    }

    private fun ensureTraversal(queue: PlayQueue, forceNew: Boolean = false) {
        if (queue.songs.isEmpty()) {
            clearTraversal()
            return
        }
        val signature = queue.songs.joinToString("\u001f") {
            "${it.path}\u001e${it.cueOffsetMs}\u001e${it.cueTrackIndex}"
        }
        if (!forceNew && queueSignature == signature && traversal.isNotEmpty()) {
            val currentPosition = traversal.indexOf(queue.currentIndex)
            if (currentPosition >= 0 && currentPosition != cursor) cursor = currentPosition
            return
        }

        queueSignature = signature
        val restored = if (!forceNew) {
            AppPreferences.Player.shuffleTraversalOrder
                .split(',')
                .mapNotNull(String::toIntOrNull)
                .takeIf { order ->
                    order.size == queue.songs.size &&
                        order.toSet().size == queue.songs.size &&
                        order.all { it in queue.songs.indices }
                }
        } else {
            null
        }
        traversal.clear()
        traversal += restored ?: buildList {
            add(queue.currentIndex.coerceIn(queue.songs.indices))
            addAll(queue.songs.indices.filter { it != queue.currentIndex }.shuffled(random))
        }
        cursor = traversal.indexOf(queue.currentIndex).takeIf { it >= 0 }
            ?: AppPreferences.Player.shuffleTraversalCursor.coerceIn(0, traversal.lastIndex)
        persistTraversal()
    }

    private fun rebuildCycle(queue: PlayQueue) {
        val current = queue.currentIndex.coerceIn(queue.songs.indices)
        traversal.clear()
        traversal += current
        traversal += queue.songs.indices.filter { it != current }.shuffled(random)
        cursor = if (traversal.size > 1) 1 else 0
        persistTraversal()
    }

    private fun clearTraversal() {
        traversal.clear()
        cursor = -1
        queueSignature = ""
        AppPreferences.Player.shuffleTraversalOrder = ""
        AppPreferences.Player.shuffleTraversalCursor = -1
    }

    private fun persistTraversal() {
        AppPreferences.Player.shuffleTraversalOrder = traversal.joinToString(",")
        AppPreferences.Player.shuffleTraversalCursor = cursor
    }
}
