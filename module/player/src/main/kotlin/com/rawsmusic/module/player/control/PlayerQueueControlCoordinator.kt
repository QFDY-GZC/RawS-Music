package com.rawsmusic.module.player.control

import android.os.SystemClock
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
import java.util.ArrayDeque

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
    private var previousRestartBypassUntilMs = 0L

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

        if (priorityQueue.isNotEmpty()) {
            val nextSong = priorityQueue.removeFirst()
            val previous = callbacks.currentQueue()
            val songs = previous.songs.toMutableList()
            val insertIndex = (previous.currentIndex + 1).coerceAtMost(songs.size)
            songs.add(insertIndex, nextSong)
            callbacks.updateQueue(previous.copy(songs = songs, currentIndex = insertIndex))
            callbacks.savePosition()
            callbacks.play(nextSong, songs, insertIndex)
            return nextSong
        }

        val queue = callbacks.currentQueue()
        if (queue.songs.isEmpty()) return null
        val nextIndex = when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> (queue.currentIndex + 1) % queue.songs.size
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.nextShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
        }
        if (nextIndex !in queue.songs.indices) return null

        callbacks.savePosition()
        val nextSong = queue.songs[nextIndex]
        callbacks.updateQueue(queue.copy(currentIndex = nextIndex))
        callbacks.manualSwitchFromStart(nextSong, queue.songs, nextIndex, "manual_next")
        return nextSong
    }

    fun previous(restartCurrentAfterThreshold: Boolean): AudioFile? {
        if (callbacks.isReleased()) return null
        val queue = callbacks.currentQueue()
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
        callbacks.updateQueue(queue.copy(currentIndex = previousIndex))
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
        if (callbacks.isReleased()) return null
        val queue = callbacks.currentQueue()
        if (index !in queue.songs.indices) return null

        val target = queue.songs[index]
        if (index == queue.currentIndex && callbacks.currentSong()?.queueIdentity() == target.queueIdentity()) {
            return target
        }

        callbacks.savePosition()
        callbacks.updateQueue(queue.copy(currentIndex = index))
        callbacks.manualSwitchFromStart(target, queue.songs, index, reason)
        return target
    }

    fun previewNextSong(): AudioFile? {
        priorityQueue.firstOrNull()?.let { return it }
        val queue = callbacks.currentQueue()
        if (queue.songs.isEmpty()) return null
        val index = when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> (queue.currentIndex + 1) % queue.songs.size
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.peekNextShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
        }
        return queue.songs.getOrNull(index)
    }

    fun previewPreviousSong(): AudioFile? {
        val queue = callbacks.currentQueue()
        if (queue.songs.isEmpty()) return null
        val index = when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> if (queue.currentIndex > 0) queue.currentIndex - 1 else queue.songs.lastIndex
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.peekPreviousShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
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

    fun addToPriorityQueue(song: AudioFile) {
        if (priorityQueue.any { it.path == song.path }) return
        priorityQueue.addLast(song)
        callbacks.saveState()
    }

    fun priorityQueueSnapshot(): List<AudioFile> = priorityQueue.toList()

    fun clearPriorityQueue() {
        priorityQueue.clear()
        callbacks.saveState()
    }

    fun adoptVisibleQueueSnapshot(songs: List<AudioFile>, currentIndex: Int) {
        if (songs.isEmpty()) return
        val safeIndex = currentIndex.coerceIn(0, songs.lastIndex)
        priorityQueue.clear()
        playHistory.clear()
        callbacks.updateQueue(
            callbacks.currentQueue().copy(
                songs = songs,
                currentIndex = safeIndex,
            )
        )
        callbacks.saveState()
    }

    fun playNext(song: AudioFile) {
        val previous = callbacks.currentQueue()
        val songs = previous.songs.toMutableList()
        songs.removeAll { it.path == song.path }
        val insertIndex = (previous.currentIndex + 1).coerceAtMost(songs.size)
        songs.add(insertIndex, song)
        callbacks.updateQueue(previous.copy(songs = songs))
        callbacks.saveState()
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
