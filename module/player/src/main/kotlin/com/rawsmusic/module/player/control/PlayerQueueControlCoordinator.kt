package com.rawsmusic.module.player.control

import android.os.SystemClock
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.core.common.utils.PlayerSwitchTrace
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
        /**
         * Legacy hook kept for source compatibility with older controller/test wiring.
         * Queue projection is no longer published through this callback: doing so created a
         * second observable current before the renderer acknowledgement. The callback is
         * intentionally never invoked by this coordinator.
         */
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
        /** Artwork swipe path: transport is queued without publishing requested-song/queue UI state on the pointer frame. */
        val manualArtworkGestureSwitchFromStart: (AudioFile, List<AudioFile>, Int, String) -> Unit,
        val automaticSwitchFromStart: (AudioFile, List<AudioFile>, Int) -> Boolean,
        val stop: () -> Unit,
    )

    private val playHistory = ArrayDeque<AudioFile>()
    private val priorityQueue = ArrayDeque<AudioFile>()
    private val _priorityQueueState = MutableStateFlow<List<AudioFile>>(emptyList())
    val priorityQueueState: StateFlow<List<AudioFile>> = _priorityQueueState.asStateFlow()
    private var previousRestartBypassUntilMs = 0L

    // Manual navigation needs a current-index projection before the authoritative renderer
    // callback advances the public PlayQueue cursor. Keep it private and non-observable: buttons
    // and direct artwork gestures must never invalidate the complete UI tree on their input frame.
    private var manualProjectedIdentity: QueueIdentity? = null
    private var manualProjectedIndex = -1
    private var manualProjectedBaseQueue: PlayQueue? = null
    private var manualProjectedPriorityGeneration = 0L
    private var priorityGeneration = 0L
    private data class PendingPriorityMutation(
        val target: QueueIdentity,
        val clearAll: Boolean,
    )
    private var pendingPriorityMutation: PendingPriorityMutation? = null

    private fun publishPriorityQueue() {
        priorityGeneration += 1L
        _priorityQueueState.value = priorityQueue.toList()
    }

    private inline fun commitVisibleQueue(crossinline commit: () -> Unit) {
        // The renderer owns the public current cursor. Do not publish a speculative queue before
        // the transport callback: that made Compose observe target -> old -> target and caused a
        // second artwork-holder transition. Callers still commit the exact projected list/index
        // in one place, but only through the authoritative callback.
        commit()
    }

    private fun visibleQueue(): PlayQueue =
        callbacks.currentQueue().withPriorityQueue(priorityQueue.toList())

    /**
     * Returns the queue cursor used only for successive artwork swipes.
     *
     * The first A -> B swipe records B privately. If a second swipe arrives before the renderer
     * publishes B as the authoritative current track, navigation resolves from B and therefore
     * targets C. Once the public queue catches up to the projected identity the private projection
     * collapses automatically. No StateFlow is written by this helper.
     */
    private fun manualNavigationQueue(): PlayQueue {
        val queue = visibleQueue()
        val projected = manualProjectedIdentity ?: return queue
        val authoritativeIdentity = queue.songs.getOrNull(queue.currentIndex)?.queueIdentity()
        if (authoritativeIdentity == projected) {
            clearManualProjectionCursor()
            return queue
        }
        val projectedIndex = if (
            manualProjectedBaseQueue === callbacks.currentQueue() &&
                manualProjectedPriorityGeneration == priorityGeneration &&
                manualProjectedIndex in queue.songs.indices &&
                queue.songs[manualProjectedIndex].queueIdentity() == projected
        ) {
            // Successive Next/Previous commands usually arrive before the renderer callback. Keep
            // the projected adapter position just like reference player's transport cursor; do not scan a
            // several-thousand-item queue again for every tap/swipe.
            manualProjectedIndex
        } else {
            queue.songs.indexOfFirst { it.queueIdentity() == projected }
        }
        if (projectedIndex < 0) {
            clearManualProjectionCursor()
            return queue
        }
        manualProjectedIndex = projectedIndex
        manualProjectedBaseQueue = callbacks.currentQueue()
        manualProjectedPriorityGeneration = priorityGeneration
        return queue.copy(currentIndex = projectedIndex)
    }

    private fun rememberManualTarget(song: AudioFile, index: Int) {
        manualProjectedIdentity = song.queueIdentity()
        manualProjectedIndex = index
        manualProjectedBaseQueue = callbacks.currentQueue()
        manualProjectedPriorityGeneration = priorityGeneration
    }

    private fun clearManualProjectionCursor() {
        manualProjectedIdentity = null
        manualProjectedIndex = -1
        manualProjectedBaseQueue = null
        manualProjectedPriorityGeneration = 0L
    }

    private fun clearManualProjection() {
        clearManualProjectionCursor()
        pendingPriorityMutation = null
    }

    /** Applies queue-policy side effects only after the renderer owns the requested track. */
    fun onManualTargetCommitted(song: AudioFile) {
        val identity = song.queueIdentity()
        if (manualProjectedIdentity == identity) clearManualProjectionCursor()
        val mutation = pendingPriorityMutation?.takeIf { it.target == identity } ?: return
        pendingPriorityMutation = null
        val changed = if (mutation.clearAll) {
            if (priorityQueue.isEmpty()) false else {
                priorityQueue.clear()
                true
            }
        } else {
            val before = priorityQueue.size
            consumePrioritySong(song)
            priorityQueue.size != before
        }
        if (changed) publishPriorityQueue()
    }

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
        clearManualProjection()
    }

    fun armPreviousRestartBypass(durationMs: Long = PREVIOUS_RESTART_BYPASS_MS) {
        previousRestartBypassUntilMs = uptimeMillis() + durationMs.coerceAtLeast(0L)
    }

    fun clearPreviousRestartBypass() {
        previousRestartBypassUntilMs = 0L
    }

    /**
     * Reference-style artwork swipe Next.
     *
     * ArtworkPagerMotion/ArtworkPager owns the moving list position; the transport callback must not publish a new
     * queue cursor/requested-song tree in the same pointer/settle frame. Resolve the exact target
     * from the current projected queue, enqueue the backend switch, and let the authoritative
     * renderer/controller commit currentSong + queue when that switch is accepted.
     */
    fun nextFromArtworkGesture(): AudioFile? {
        if (callbacks.isReleased()) return null
        val queue = manualNavigationQueue()
        if (queue.songs.isEmpty()) return null
        val targetIndex = nextIndex(queue)
        val target = queue.songs.getOrNull(targetIndex) ?: return null
        PlayerSwitchTrace.mark(
            "queue_project_target",
            "source=artwork_gesture_next from=${queue.currentIndex} to=$targetIndex id=${target.id}",
        )
        rememberManualTarget(target, targetIndex)

        if (priorityQueue.any { it.queueIdentity() == target.queueIdentity() }) {
            pendingPriorityMutation = PendingPriorityMutation(target.queueIdentity(), clearAll = false)
        }
        callbacks.manualArtworkGestureSwitchFromStart(
            target,
            queue.songs,
            targetIndex,
            "artwork_gesture_next",
        )
        return target
    }

    fun next(): AudioFile? {
        if (callbacks.isReleased()) return null
        val queue = manualNavigationQueue()
        if (queue.songs.isEmpty()) return null
        val nextIndex = nextIndex(queue)
        if (nextIndex !in queue.songs.indices) return null

        val nextSong = queue.songs[nextIndex]
        PlayerSwitchTrace.mark(
            "queue_project_target",
            "source=manual_next from=${queue.currentIndex} to=$nextIndex id=${nextSong.id}",
        )
        rememberManualTarget(nextSong, nextIndex)
        if (priorityQueue.any { it.queueIdentity() == nextSong.queueIdentity() }) {
            pendingPriorityMutation = PendingPriorityMutation(nextSong.queueIdentity(), clearAll = false)
        }
        // The retained pager already knows this exact target. Queue/current-song publication is
        // owned by the renderer acknowledgement in PlayerController; doing it here forces every
        // Compose queue consumer to rebuild before the first animation frame.
        callbacks.manualSwitchFromStart(nextSong, queue.songs, nextIndex, "manual_next")
        return nextSong
    }

    /**
     * Advances after renderer EOF without borrowing manual button/gesture transition semantics.
     * Queue traversal stays identical to [next], including priority and shuffle behavior.
     */
    fun automaticAdvance(): AudioFile? {
        clearManualProjection()
        if (callbacks.isReleased()) return null
        val queue = visibleQueue()
        if (queue.songs.isEmpty()) return null
        val targetIndex = nextIndex(queue)
        val target = queue.songs.getOrNull(targetIndex) ?: return null
        if (!callbacks.automaticSwitchFromStart(target, queue.songs, targetIndex)) return null

        callbacks.savePosition()
        val committedQueue = queue.copy(currentIndex = targetIndex)
        commitVisibleQueue {
            callbacks.updateQueue(committedQueue)
            consumePrioritySong(target)
            publishPriorityQueue()
        }
        return target
    }

    /** Previous counterpart of [nextFromArtworkGesture]; artwork swipes never use restart-at-3s. */
    fun previousFromArtworkGesture(): AudioFile? {
        if (callbacks.isReleased()) return null
        val queue = manualNavigationQueue()
        if (queue.songs.isEmpty()) return null
        previousRestartBypassUntilMs = 0L
        val targetIndex = when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> if (queue.currentIndex > 0) queue.currentIndex - 1 else queue.songs.lastIndex
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.previousShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
        }
        val target = queue.songs.getOrNull(targetIndex) ?: return null
        PlayerSwitchTrace.mark(
            "queue_project_target",
            "source=artwork_gesture_previous from=${queue.currentIndex} to=$targetIndex id=${target.id}",
        )
        rememberManualTarget(target, targetIndex)

        if (priorityQueue.isNotEmpty()) {
            pendingPriorityMutation = PendingPriorityMutation(target.queueIdentity(), clearAll = true)
        }
        callbacks.manualArtworkGestureSwitchFromStart(
            target,
            queue.songs,
            targetIndex,
            "artwork_gesture_previous",
        )
        return target
    }

    fun previous(restartCurrentAfterThreshold: Boolean): AudioFile? {
        if (callbacks.isReleased()) return null
        val queue = manualNavigationQueue()
        if (queue.songs.isEmpty()) return null

        val bypassRestart = restartCurrentAfterThreshold &&
            uptimeMillis() <= previousRestartBypassUntilMs
        previousRestartBypassUntilMs = 0L
        if (restartCurrentAfterThreshold && !bypassRestart && callbacks.playerPositionMs() > 3000L) {
            PlayerSwitchTrace.mark(
                "queue_previous_restart_current",
                "index=${queue.currentIndex} position=${callbacks.playerPositionMs()}",
            )
            callbacks.seekToStart()
            return callbacks.currentSong()
        }

        val previousIndex = when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> if (queue.currentIndex > 0) queue.currentIndex - 1 else queue.songs.lastIndex
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> mode.previousShuffleIndex(queue)
            PlayMode.REPEAT_ONE -> queue.currentIndex
        }
        if (previousIndex !in queue.songs.indices) return null

        val previousSong = queue.songs[previousIndex]
        PlayerSwitchTrace.mark(
            "queue_project_target",
            "source=manual_previous from=${queue.currentIndex} to=$previousIndex id=${previousSong.id}",
        )
        rememberManualTarget(previousSong, previousIndex)
        if (priorityQueue.isNotEmpty()) {
            pendingPriorityMutation = PendingPriorityMutation(previousSong.queueIdentity(), clearAll = true)
        }
        // Keep the authoritative current song attached until the renderer/transport commits the
        // previous target. Clearing it here tears down title/background/artwork ownership in the
        // same UI turn that starts the artwork motion, while Next already keeps the old current alive.
        // The requested-song lane exposes the preview target without introducing an empty owner.
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
        clearManualProjection()
        if (callbacks.isReleased()) return null
        val queue = visibleQueue()
        if (index !in queue.songs.indices) return null

        val target = queue.songs[index]
        if (index == queue.currentIndex && callbacks.currentSong()?.queueIdentity() == target.queueIdentity()) {
            return target
        }

        callbacks.savePosition()
        val committedQueue = queue.copy(currentIndex = index)
        commitVisibleQueue {
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

    /**
     * Completion policy must ask the same traversal that automaticAdvance() uses.  Looking only
     * at the physical array index is wrong for shuffle-once: the last shuffled item can be at any
     * array position.  Reference checks the queue cursor, not the row's sort position.
     */
    fun hasAutomaticContinuation(queue: PlayQueue = visibleQueue()): Boolean {
        if (queue.songs.isEmpty()) return false
        if (priorityQueue.isNotEmpty()) return true
        return when (mode.currentPlayMode()) {
            PlayMode.SEQUENTIAL -> queue.currentIndex < queue.songs.lastIndex
            PlayMode.SHUFFLE_ALL -> mode.peekNextShuffleIndex(queue) in queue.songs.indices
            PlayMode.SHUFFLE_ONCE -> mode.peekNextShuffleIndex(queue) in queue.songs.indices
            PlayMode.REPEAT_ONE -> false
        }
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
        val current = callbacks.currentQueue()
        val canReuseEntryIds = current.songs.size == songs.size && current.songs.indices.all { index ->
            current.songs[index].queueIdentity() == songs[index].queueIdentity()
        }
        val entryIds = com.rawsmusic.module.player.QueueEntryIdGenerator.normalize(
            songs.size,
            if (canReuseEntryIds) current.entryIds else emptyList(),
        )
        callbacks.updateQueue(
            current.copy(
                songs = songs,
                currentIndex = safeIndex,
                entryIds = entryIds,
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
        val entryIds = com.rawsmusic.module.player.QueueEntryIdGenerator.normalize(
            songs.size,
            previous.entryIds,
        ).toMutableList()
        songs.removeAt(index)
        entryIds.removeAt(index)
        var currentIndex = previous.currentIndex
        when {
            index < currentIndex -> currentIndex--
            index == currentIndex -> currentIndex = (currentIndex - 1).coerceAtLeast(0)
        }
        if (songs.isEmpty()) currentIndex = -1
        callbacks.updateQueue(
            previous.copy(
                songs = songs,
                currentIndex = currentIndex,
                entryIds = entryIds,
            )
        )
        callbacks.saveState()
    }

    fun removeSongsFromQueue(songs: Collection<AudioFile>) {
        if (songs.isEmpty()) return
        val identities = songs.map { it.queueIdentity() }.toHashSet()
        val previous = callbacks.currentQueue()
        val currentIdentity = previous.currentSong?.queueIdentity()
        val currentWasRemoved = currentIdentity != null && currentIdentity in identities
        val previousEntryIds = com.rawsmusic.module.player.QueueEntryIdGenerator.normalize(
            previous.songs.size,
            previous.entryIds,
        )
        val retainedWithIds = previous.songs.mapIndexed { index, song -> song to previousEntryIds[index] }
            .filterNot { it.first.queueIdentity() in identities }
        val retained = retainedWithIds.map { it.first }
        val retainedEntryIds = retainedWithIds.map { it.second }
        val newIndex = when {
            retained.isEmpty() -> -1
            currentIdentity != null -> retained.indexOfFirst { it.queueIdentity() == currentIdentity }
                .takeIf { it >= 0 }
                ?: previous.currentIndex.coerceIn(0, retained.lastIndex)
            else -> previous.currentIndex.coerceIn(0, retained.lastIndex)
        }
        callbacks.updateQueue(
            previous.copy(
                songs = retained,
                currentIndex = newIndex,
                entryIds = retainedEntryIds,
            )
        )
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
