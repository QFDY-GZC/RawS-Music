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
    private val statePersistence: PlayerStatePersistence,
    private val lastPlayedAt: () -> Map<Long, Long> = { emptyMap() },
) {
    private val traversal = mutableListOf<Int>()
    private var cursor = -1
    private var queueSignature = ""
    private val random = Random.Default
    private var pendingPlannedSignature = ""

    @Synchronized
    fun enable(queue: PlayQueue, repeatMode: RepeatMode): PlayQueue? {
        if (queue.songs.size <= 1) return null
        ensureTraversal(queue, forceNew = true)
        return queue.copy(
            repeatMode = repeatMode,
            isShuffle = true,
            originalSongs = emptyList(),
        )
    }

    @Synchronized
    fun disable(queue: PlayQueue, repeatMode: RepeatMode): PlayQueue? {
        clearTraversal()
        AppPreferences.Player.originalQueueSongsJson = ""
        return queue.copy(
            repeatMode = repeatMode,
            isShuffle = false,
            originalSongs = emptyList(),
        )
    }

    @Synchronized
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

    @Synchronized
    fun peekNextIndex(queue: PlayQueue): Int {
        ensureTraversal(queue)
        traversal.getOrNull(cursor + 1)?.let { return it }
        if (AppPreferences.Player.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ONCE) {
            return -1
        }
        return traversal.firstOrNull { it != queue.currentIndex } ?: queue.currentIndex
    }

    @Synchronized
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

    @Synchronized
    fun peekPreviousIndex(queue: PlayQueue): Int {
        ensureTraversal(queue)
        if (cursor > 0) return traversal[cursor - 1]
        return if (AppPreferences.Player.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL) {
            traversal.lastOrNull() ?: queue.currentIndex
        } else {
            queue.currentIndex
        }
    }

    /** Returns a stable relative item without advancing or rebuilding the playback cursor. */
    @Synchronized
    fun peekRelativeIndex(queue: PlayQueue, offset: Int): Int {
        ensureTraversal(queue)
        if (traversal.isEmpty()) return -1
        val currentPosition = traversal.indexOf(queue.currentIndex).takeIf { it >= 0 } ?: cursor
        val targetPosition = currentPosition + offset
        return when {
            targetPosition in traversal.indices -> traversal[targetPosition]
            AppPreferences.Player.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL ->
                traversal[targetPosition.floorMod(traversal.size)]
            else -> -1
        }
    }

    @Synchronized
    fun nextIndexForGapless(currentIndex: Int, size: Int, wrap: Boolean): Int {
        if (size <= 0) return -1
        val position = traversal.indexOf(currentIndex)
        if (position >= 0 && position + 1 < traversal.size) return traversal[position + 1]
        return if (wrap) traversal.firstOrNull { it != currentIndex } ?: currentIndex else -1
    }

    @Synchronized
    fun planSession(songs: List<AudioFile>, groups: List<List<AudioFile>>? = null): List<AudioFile> {
        val factor = AppPreferences.Player.shuffleRandomFactor
        val stats = lastPlayedAt()
        return if (groups == null) {
            ReferenceShufflePlanner.songs(songs, factor, stats, random)
        } else {
            ReferenceShufflePlanner.categories(
                groups,
                AppPreferences.Player.categoryShuffleMode,
                factor,
                stats,
                random,
            )
        }
    }

    /** Preserve a category-aware physical plan while the existing four-mode UI uses shuffle-once. */
    @Synchronized
    fun armPlannedTraversal(songs: List<AudioFile>) {
        pendingPlannedSignature = signatureOf(songs)
    }

    @Synchronized
    fun onExplicitSelection(queue: PlayQueue, selectedIndex: Int): Boolean {
        if (selectedIndex !in queue.songs.indices || queue.songs.size <= 1) return false
        val preserve = ReferenceShufflePlanner.preserveOnManualSelection(
            queue.songs.size,
            AppPreferences.Player.noReshuffle,
            AppPreferences.Player.noReshuffleForLargeLists,
        )
        val selectedQueue = queue.copy(currentIndex = selectedIndex)
        ensureTraversal(selectedQueue, forceNew = !preserve)
        return AppPreferences.Player.noReshuffle
    }

    private fun ensureTraversal(queue: PlayQueue, forceNew: Boolean = false) {
        if (queue.songs.isEmpty()) {
            clearTraversal()
            return
        }
        val signature = signatureOf(queue.songs)
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
        val planned = pendingPlannedSignature == signature
        if (planned) pendingPlannedSignature = ""
        traversal.clear()
        traversal += restored ?: if (planned) {
            queue.songs.indices.toList()
        } else buildList {
            add(queue.currentIndex.coerceIn(queue.songs.indices))
            addAll(sampledIndices(queue, queue.songs.indices.filter { it != queue.currentIndex }))
        }
        cursor = traversal.indexOf(queue.currentIndex).takeIf { it >= 0 }
            ?: AppPreferences.Player.shuffleTraversalCursor.coerceIn(0, traversal.lastIndex)
        persistTraversal()
    }

    private fun rebuildCycle(queue: PlayQueue) {
        val current = queue.currentIndex.coerceIn(queue.songs.indices)
        traversal.clear()
        traversal += current
        traversal += sampledIndices(queue, queue.songs.indices.filter { it != current })
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

    private fun sampledIndices(queue: PlayQueue, indices: List<Int>): List<Int> {
        val stats = lastPlayedAt()
        return ReferenceShufflePlanner.sampled(
            indices,
            AppPreferences.Player.shuffleRandomFactor,
            { index -> stats[queue.songs[index].id] ?: 0L },
            random,
        )
    }

    private fun signatureOf(songs: List<AudioFile>): String = songs.joinToString("\u001f") {
        "${it.path}\u001e${it.cueOffsetMs}\u001e${it.cueTrackIndex}"
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
