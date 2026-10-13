package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
import kotlin.random.Random

/**
 * Stable shuffle traversal over an immutable canonical queue.
 *
 * The old implementation physically reordered PlayQueue.songs and later attempted to restore a
 * second serialized copy. That made queue identity depend on the current mode and allowed any
 * play()/next()/previous() path to rebuild it. This controller only moves an index cursor.
 */
internal class ShuffleQueueController(
    private val preferences: ShuffleQueuePreferences = PersistedShuffleQueuePreferences,
    private val lastPlayedAt: () -> Map<Long, Long> = { emptyMap() },
) {
    private val traversal = mutableListOf<Int>()
    private val previousCycles = ArrayDeque<List<Int>>()
    private val forwardCycles = ArrayDeque<List<Int>>()
    private var cursor = -1
    private var queueSignature = ""
    private val random = Random.Default
    private var pendingPlannedSignature = ""
    private var activeSongs: List<AudioFile>? = null
    private var checkedSongs: List<AudioFile>? = null
    private var checkedSignature = ""
    private var traversalPositions = IntArray(0)

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
        preferences.clearOriginalQueue()
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
        if (preferences.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ONCE) {
            return -1
        }
        rebuildCycle(queue)
        return traversal.getOrNull(cursor) ?: -1
    }

    @Synchronized
    fun peekNextIndex(queue: PlayQueue): Int {
        if (!canPreview(queue)) return -1
        ensureTraversal(queue, syncCursor = false)
        val position = traversalPosition(queue.currentIndex)
        traversal.getOrNull(position + 1)?.let { return it }
        if (preferences.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ONCE) {
            return -1
        }
        return preparedNextCycle(queue).getOrNull(1) ?: queue.currentIndex
    }

    @Synchronized
    fun previousIndex(queue: PlayQueue): Int {
        ensureTraversal(queue)
        if (cursor > 0) {
            cursor--
            persistTraversal()
            return traversal[cursor]
        }
        if (preferences.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL &&
            traversal.isNotEmpty()
        ) {
            if (previousCycles.isNotEmpty()) {
                forwardCycles.addLast(traversal.toList())
                val previous = previousCycles.removeLast()
                traversal.clear()
                traversal.addAll(previous)
                rebuildPositions()
                preferences.order = traversal.joinToString(",")
                // The new cycle's first entry is the previous cycle's last entry.
                cursor = (traversal.lastIndex - 1).coerceAtLeast(0)
            } else {
                cursor = traversal.lastIndex
            }
            persistTraversal()
            return traversal[cursor]
        }
        return queue.currentIndex
    }

    @Synchronized
    fun peekPreviousIndex(queue: PlayQueue): Int {
        if (!canPreview(queue)) return -1
        ensureTraversal(queue, syncCursor = false)
        val position = traversalPosition(queue.currentIndex)
        if (position > 0) return traversal[position - 1]
        return if (preferences.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL) {
            previousCycles.lastOrNull()?.let { it.getOrNull(it.lastIndex - 1) }
                ?: traversal.lastOrNull() ?: queue.currentIndex
        } else {
            queue.currentIndex
        }
    }

    /** Returns a stable relative item without advancing or rebuilding the playback cursor. */
    @Synchronized
    fun peekRelativeIndex(queue: PlayQueue, offset: Int): Int {
        if (!canPreview(queue)) return -1
        ensureTraversal(queue, syncCursor = false)
        if (traversal.isEmpty()) return -1
        val currentPosition = traversalPosition(queue.currentIndex).takeIf { it >= 0 } ?: cursor
        val targetPosition = currentPosition + offset
        if (targetPosition < 0 && previousCycles.isNotEmpty()) {
            val previous = previousCycles.last()
            previous.getOrNull(previous.lastIndex + targetPosition)?.let { return it }
        }
        if (targetPosition > traversal.lastIndex && forwardCycles.isNotEmpty()) {
            forwardCycles.last().getOrNull(targetPosition - traversal.lastIndex)?.let { return it }
        }
        if (targetPosition > traversal.lastIndex &&
            preferences.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL
        ) {
            preparedNextCycle(queue).getOrNull(targetPosition - traversal.lastIndex)?.let { return it }
        }
        return when {
            targetPosition in traversal.indices -> traversal[targetPosition]
            preferences.playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL ->
                traversal[targetPosition.floorMod(traversal.size)]
            else -> -1
        }
    }

    @Synchronized
    fun nextIndexForGapless(currentIndex: Int, size: Int, wrap: Boolean): Int {
        if (size <= 0) return -1
        val position = traversalPosition(currentIndex)
        if (position >= 0 && position + 1 < traversal.size) return traversal[position + 1]
        return if (wrap) forwardCycles.lastOrNull()?.getOrNull(1)
            ?: traversal.firstOrNull { it != currentIndex } ?: currentIndex else -1
    }

    @Synchronized
    fun planSession(songs: List<AudioFile>, groups: List<List<AudioFile>>? = null): List<AudioFile> {
        val factor = preferences.randomFactor
        val stats = lastPlayedAt()
        return if (groups == null) {
            ReferenceShufflePlanner.songs(songs, factor, stats, random)
        } else {
            ReferenceShufflePlanner.categories(
                groups,
                preferences.categoryMode,
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
            preferences.noReshuffle,
            preferences.noReshuffleForLargeLists,
        )
        val selectedQueue = queue.copy(currentIndex = selectedIndex)
        ensureTraversal(selectedQueue, forceNew = !preserve)
        return preferences.noReshuffle
    }

    private fun ensureTraversal(queue: PlayQueue, forceNew: Boolean = false, syncCursor: Boolean = true) {
        if (queue.songs.isEmpty()) {
            clearTraversal()
            return
        }
        val signature = signatureOf(queue.songs)
        if (!forceNew && queueSignature == signature && traversal.isNotEmpty()) {
            activeSongs = queue.songs
            val currentPosition = traversalPosition(queue.currentIndex)
            if (syncCursor && currentPosition >= 0 && currentPosition != cursor) cursor = currentPosition
            return
        }

        queueSignature = signature
        activeSongs = queue.songs
        previousCycles.clear()
        forwardCycles.clear()
        val restored = if (!forceNew) {
            preferences.order
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
        rebuildPositions()
        cursor = traversalPosition(queue.currentIndex).takeIf { it >= 0 }
            ?: preferences.cursor.coerceIn(0, traversal.lastIndex)
        persistTraversal(orderChanged = true)
    }

    private fun rebuildCycle(queue: PlayQueue) {
        val next = preparedNextCycle(queue)
        forwardCycles.removeLast()
        previousCycles.addLast(traversal.toList())
        // Bound history to avoid unbounded retention during unattended continuous playback.
        if (previousCycles.size > 8) previousCycles.removeFirst()
        traversal.clear()
        traversal += next
        rebuildPositions()
        cursor = if (traversal.size > 1) 1 else 0
        persistTraversal(orderChanged = true)
    }

    private fun canPreview(queue: PlayQueue): Boolean =
        queue.songs.isNotEmpty() && (traversal.isEmpty() || signatureOf(queue.songs) == queueSignature)

    private fun preparedNextCycle(queue: PlayQueue): List<Int> {
        forwardCycles.lastOrNull()?.let { return it }
        val anchor = traversal.lastOrNull() ?: queue.currentIndex.coerceIn(queue.songs.indices)
        return (listOf(anchor) + sampledIndices(queue, queue.songs.indices.filter { it != anchor }))
            .also { forwardCycles.addLast(it) }
    }

    private fun clearTraversal() {
        traversal.clear()
        previousCycles.clear()
        forwardCycles.clear()
        cursor = -1
        queueSignature = ""
        activeSongs = null
        checkedSongs = null
        checkedSignature = ""
        traversalPositions = IntArray(0)
        preferences.order = ""
        preferences.cursor = -1
    }

    private fun persistTraversal(orderChanged: Boolean = false) {
        if (orderChanged) preferences.order = traversal.joinToString(",")
        preferences.cursor = cursor
    }

    private fun traversalPosition(index: Int): Int = traversalPositions.getOrNull(index) ?: -1

    private fun rebuildPositions() {
        traversalPositions = IntArray(traversal.size) { -1 }
        traversal.forEachIndexed { position, index ->
            if (index in traversalPositions.indices) traversalPositions[index] = position
        }
    }

    private fun sampledIndices(queue: PlayQueue, indices: List<Int>): List<Int> {
        val stats = lastPlayedAt()
        return ReferenceShufflePlanner.sampled(
            indices,
            preferences.randomFactor,
            { index -> stats[queue.songs[index].id] ?: 0L },
            random,
        )
    }

    private fun signatureOf(songs: List<AudioFile>): String {
        // PlayQueue publishes immutable lists; moving its cursor preserves this source identity.
        if (songs === activeSongs) return queueSignature
        if (songs === checkedSongs) return checkedSignature
        val signature = songs.joinToString("\u001f") {
            "${it.path}\u001e${it.cueOffsetMs}\u001e${it.cueTrackIndex}"
        }
        checkedSongs = songs
        checkedSignature = signature
        return signature
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
