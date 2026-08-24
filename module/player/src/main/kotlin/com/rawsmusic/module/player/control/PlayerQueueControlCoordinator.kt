package com.rawsmusic.module.player.control

import android.os.SystemClock
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.module.player.withPriorityQueue
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns queue navigation, play-history and mode commands.
 *
 * Decoder/renderer switching remains in PlayerController. Keeping those callbacks explicit avoids
 * giving this class access to USB or Android-output internals while removing queue policy from the
 * runtime controller.
 */
internal class PlayerQueueControlCoordinator(
    private val mode: ModeCallbacks,
    private val callbacks: Callbacks,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    internal data class ModeCallbacks(
        val currentPlayMode: () -> PlayMode,
        val isShuffleEnabled: () -> Boolean,
        val nextShuffleIndex: (PlayQueue) -> Int,
        val previousShuffleIndex: (PlayQueue) -> Int,
        val peekNextShuffleIndex: (PlayQueue) -> Int,
        val peekPreviousShuffleIndex: (PlayQueue) -> Int,
        val peekRelativeShuffleIndex: (PlayQueue, Int) -> Int,
        val toggleRepeatMode: () -> Unit,
        val setRepeatMode: (RepeatMode) -> Unit,
        val toggleShuffle: () -> Unit,
        val cyclePlayMode: () -> Unit,
        val setPlayMode: (PlayMode) -> Unit,
        val rebuildShuffleForCurrentQueue: () -> Unit,
    )

    internal data class Callbacks(
        val isReleased: () -> Boolean,
        val currentQueue: () -> PlayQueue,
        val updateQueue: (PlayQueue) -> Unit,
        /** Publishes one stable queue projection while base/priority state is committed. */
        val setVisibleQueueOverride: (PlayQueue?) -> Unit = {},
        val currentSong: () -> AudioFile?,
        val clearCurrentSong: () -> Unit,
        val clearRequestedSong: () -> Unit,
        val resetTimeline: () -> Unit,
        val playerPositionMs: () -> Long,
        val seekToStart: () -> Unit,
        val savePosition: () -> Unit,
        val saveState: () -> Unit,
        val play: (AudioFile, List<AudioFile>, Int) -> Unit,
        val manualSwitchFromStart: (AudioFile, List<AudioFile>, Int, String) -> Unit,
        val stop: () -> Unit,
    )

    private val playHistory = ArrayDeque<AudioFile>()
    private val priorityQueue = ArrayDeque<AudioFile>()
    private val _priorityQueueState = MutableStateFlow<List<AudioFile>>(emptyList())
    val priorityQueueState: StateFlow<List<AudioFile>> = _priorityQueueState.asStateFlow()
    private var previousRestartBypassUntilMs = 0L

    private fun publishPriorityQueue() {
        _priorityQueueState.value = priorityQueue.toList()
    }

    private inline fun commitVisibleQueue(
        queue: PlayQueue,
        crossinline commit: () -> Unit,
    ) {
        // Base queue, priority queue and renderer ownership commit at different times. Keep the
        // target projection pinned until PlayerController confirms the matching rendered song;
        // clearing it in this call stack exposes an old cursor between those asynchronous writes.
        callbacks.setVisibleQueueOverride(queue)
        commit()
    }

    private fun visibleQueue(): PlayQueue =
        callbacks.currentQueue().withPriorityQueue(priorityQueue.toList())

    /**
     * Resolve the next item from the same projected queue used by the UI.
     *
     * A play-next item is already placed immediately after the current item by
     * [withPriorityQueue]. It must win over shuffle and repeat-one until it has
     * actually been consumed; asking the shuffle controller first creates a
     * different preview from the transport target.
     */
    private fun nextIndex(queue: PlayQueue): Int {
        if (priorityQueue.isNotEmpty()) {
            return (queue.currentIndex + 1).mod(queue.songs.size)
        }
        return when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> (queue.currentIndex + 1) % queue.songs.size
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.nextShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
        }
    }

    private fun previewNextIndex(queue: PlayQueue): Int {
        if (priorityQueue.isNotEmpty()) {
            return (queue.currentIndex + 1).mod(queue.songs.size)
        }
        return when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> (queue.currentIndex + 1) % queue.songs.size
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.peekNextShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
        }
    }

    private fun consumePrioritySong(song: AudioFile) {
        val iterator = priorityQueue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().queueIdentity() == song.queueIdentity()) {
                iterator.remove()
                return
            }
        }
    }

    fun recordCurrentSongBeforePlay(current: AudioFile, next: AudioFile) {
        if (mode.isShuffleEnabled()) return
        if (current.id == next.id || playHistory.lastOrNull()?.id == current.id) return
        playHistory.addLast(current)
        if (playHistory.size > MAX_HISTORY_SIZE) playHistory.removeFirst()
    }

    fun clearHistoryForNewQueue() {
        playHistory.clear()
    }

    fun armPreviousRestartBypass(durationMs: Long = PREVIOUS_RESTART_BYPASS_MS) {
        previousRestartBypassUntilMs = uptimeMillis() + durationMs.coerceAtLeast(0L)
    }

    fun clearPreviousRestartBypass() {
        previousRestartBypassUntilMs = 0L
    }

    fun next(): AudioFile? {
        if (callbacks.isReleased()) return null
        val queue = visibleQueue()
        if (queue.songs.isEmpty()) return null
        val nextIndex = nextIndex(queue)
        if (nextIndex !in queue.songs.indices) return null

        callbacks.savePosition()
        val nextSong = queue.songs[nextIndex]
        val committedQueue = queue.copy(currentIndex = nextIndex)
        commitVisibleQueue(committedQueue) {
            callbacks.updateQueue(committedQueue)
            // Keep later play-next items in the same projected order. Clearing the
            // whole deque here made the next preview fall back to the base/shuffle
            // queue after only one manual advance.
            consumePrioritySong(nextSong)
            publishPriorityQueue()
        }
        callbacks.manualSwitchFromStart(nextSong, queue.songs, nextIndex, "manual_next")
        return nextSong
    }

    fun previous(restartCurrentAfterThreshold: Boolean): AudioFile? {
        if (callbacks.isReleased()) return null
        val queue = visibleQueue()
        if (queue.songs.isEmpty()) return null

        val bypassRestart = restartCurrentAfterThreshold &&
            uptimeMillis() <= previousRestartBypassUntilMs
        previousRestartBypassUntilMs = 0L
        if (restartCurrentAfterThreshold && !bypassRestart && callbacks.playerPositionMs() > 3000L) {
            callbacks.seekToStart()
            return callbacks.currentSong()
        }

        val previousIndex = when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> if (queue.currentIndex > 0) queue.currentIndex - 1 else queue.songs.lastIndex
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.previousShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
        }
        if (previousIndex !in queue.songs.indices) return null

        callbacks.savePosition()
        val previousSong = queue.songs[previousIndex]
        val committedQueue = queue.copy(currentIndex = previousIndex)
        commitVisibleQueue(committedQueue) {
            callbacks.updateQueue(committedQueue)
            priorityQueue.clear()
            publishPriorityQueue()
        }
        callbacks.clearCurrentSong()
        callbacks.manualSwitchFromStart(previousSong, queue.songs, previousIndex, "manual_previous")
        return previousSong
    }

    /**
     * Select an item from the already active queue without publishing a replacement queue.
     *
     * UI carousels render the controller's queue snapshot. Routing their selection through
     * play(song, suppliedQueue, index) republishes the same list as a new queue and briefly lets
     * the retiring decoder cursor win, producing target -> old -> target artwork frames.
     */
    fun selectExistingQueueIndex(index: Int, reason: String): AudioFile? {
        return selectVisibleQueueIndex(index, reason)
    }

    /** Selects from the exact queue rendered by the home carousel and playback bar. */
    fun selectVisibleQueueIndex(index: Int, reason: String): AudioFile? {
        if (callbacks.isReleased()) return null
        val queue = visibleQueue()
        if (index !in queue.songs.indices) return null

        val target = queue.songs[index]
        if (index == queue.currentIndex && callbacks.currentSong()?.queueIdentity() == target.queueIdentity()) {
            return target
        }

        callbacks.savePosition()
        val committedQueue = queue.copy(currentIndex = index)
        commitVisibleQueue(committedQueue) {
            callbacks.updateQueue(committedQueue)
            priorityQueue.clear()
            publishPriorityQueue()
        }
        callbacks.manualSwitchFromStart(target, queue.songs, index, reason)
        return target
    }

    fun previewNextSong(queue: PlayQueue = visibleQueue()): AudioFile? {
        if (queue.songs.isEmpty()) return null
        val index = previewNextIndex(queue)
        return queue.songs.getOrNull(index)
    }

    fun previewPreviousSong(queue: PlayQueue = visibleQueue()): AudioFile? {
        if (queue.songs.isEmpty()) return null
        val index = when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> if (queue.currentIndex > 0) queue.currentIndex - 1 else queue.songs.lastIndex
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.peekPreviousShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
        }
        return queue.songs.getOrNull(index)
    }

    /**
     * Resolves every carousel lane from the same immutable traversal used by transport.
     * Unlike repeated next/previous calls this never advances shuffle state.
     */
    fun previewRelativeSong(queue: PlayQueue = visibleQueue(), offset: Int): AudioFile? {
        if (queue.songs.isEmpty()) return null
        if (offset == 0) return queue.currentSong
        val index = when {
            priorityQueue.isNotEmpty() -> (queue.currentIndex + offset).floorMod(queue.songs.size)
            mode.currentPlayMode() == PlayMode.SEQUENTIAL ->
                (queue.currentIndex + offset).floorMod(queue.songs.size)
            mode.currentPlayMode() == PlayMode.SHUFFLE_ALL ||
                mode.currentPlayMode() == PlayMode.SHUFFLE_ONCE ->
                mode.peekRelativeShuffleIndex(queue, offset)
            else -> queue.currentIndex
        }
        return queue.songs.getOrNull(index)
    }

    fun toggleRepeatMode() = mode.toggleRepeatMode()

    fun setRepeatMode(mode: RepeatMode) = this.mode.setRepeatMode(mode)

    fun toggleShuffle() {
        playHistory.clear()
        mode.toggleShuffle()
    }

    fun cyclePlayMode() {
        playHistory.clear()
        mode.cyclePlayMode()
    }

    fun setPlayMode(mode: PlayMode) {
        playHistory.clear()
        this.mode.setPlayMode(mode)
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    fun addToPriorityQueue(song: AudioFile) {
        val current = callbacks.currentQueue().currentSong
        if (current?.queueIdentity() == song.queueIdentity()) return
        if (priorityQueue.any { it.queueIdentity() == song.queueIdentity() }) return
        priorityQueue.addLast(song)
        publishPriorityQueue()
        callbacks.saveState()
    }

    fun priorityQueueSnapshot(): List<AudioFile> = priorityQueue.toList()

    fun restorePriorityQueue(songs: List<AudioFile>) {
        priorityQueue.clear()
        priorityQueue.addAll(songs)
        publishPriorityQueue()
    }

    fun clearPriorityQueue() {
        priorityQueue.clear()
        publishPriorityQueue()
        callbacks.saveState()
    }

    fun adoptVisibleQueueSnapshot(songs: List<AudioFile>, currentIndex: Int) {
        if (songs.isEmpty()) return
        val safeIndex = currentIndex.coerceIn(0, songs.lastIndex)
        playHistory.clear()
        callbacks.updateQueue(
            callbacks.currentQueue().copy(
                songs = songs,
                currentIndex = safeIndex,
            )
        )
        priorityQueue.clear()
        publishPriorityQueue()
        callbacks.saveState()
    }

    fun playNext(song: AudioFile) {
        addToPriorityQueue(song)
    }

    fun removeFromQueue(index: Int) {
        val previous = callbacks.currentQueue()
        val songs = previous.songs.toMutableList()
        if (index !in songs.indices) return
        songs.removeAt(index)
        var currentIndex = previous.currentIndex
        when {
            index < currentIndex -> currentIndex--
            index == currentIndex -> currentIndex = (currentIndex - 1).coerceAtLeast(0)
        }
        if (songs.isEmpty()) currentIndex = -1
        callbacks.updateQueue(previous.copy(songs = songs, currentIndex = currentIndex))
    }

    fun removeSongsFromQueue(songs: Collection<AudioFile>) {
        if (songs.isEmpty()) return
        val identities = songs.map { it.queueIdentity() }.toHashSet()
        val previous = callbacks.currentQueue()
        val currentIdentity = previous.currentSong?.queueIdentity()
        val currentWasRemoved = currentIdentity != null && currentIdentity in identities
        val retained = previous.songs.filterNot { it.queueIdentity() in identities }
        val newIndex = when {
            retained.isEmpty() -> -1
            currentIdentity != null -> retained.indexOfFirst { it.queueIdentity() == currentIdentity }
                .takeIf { it >= 0 }
                ?: previous.currentIndex.coerceIn(0, retained.lastIndex)
            else -> previous.currentIndex.coerceIn(0, retained.lastIndex)
        }
        callbacks.updateQueue(previous.copy(songs = retained, currentIndex = newIndex))
        playHistory.removeAll { it.queueIdentity() in identities }
        priorityQueue.removeAll { it.queueIdentity() in identities }
        publishPriorityQueue()

        if (mode.isShuffleEnabled() && retained.size > 1) {
            mode.rebuildShuffleForCurrentQueue()
        }

        if (currentWasRemoved) {
            val replacementQueue = callbacks.currentQueue()
            val replacement = replacementQueue.currentSong
            if (replacement == null) {
                callbacks.clearCurrentSong()
                callbacks.clearRequestedSong()
                callbacks.resetTimeline()
                callbacks.stop()
            } else {
                callbacks.clearCurrentSong()
                callbacks.manualSwitchFromStart(
                    replacement,
                    replacementQueue.songs,
                    replacementQueue.currentIndex,
                    "deleted_current_song",
                )
            }
        }
        callbacks.saveState()
    }

    private fun AudioFile.queueIdentity(): QueueIdentity =
        QueueIdentity(path, cueOffsetMs, cueTrackIndex)

    private data class QueueIdentity(
        val path: String,
        val cueOffsetMs: Long,
        val cueTrackIndex: Int,
    )

    private companion object {
        const val MAX_HISTORY_SIZE = 30
        const val PREVIOUS_RESTART_BYPASS_MS = 6_000L
    }
}
