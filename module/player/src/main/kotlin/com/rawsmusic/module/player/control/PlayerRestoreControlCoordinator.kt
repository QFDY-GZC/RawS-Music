package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.module.player.RestoredPlayerState

/** Applies a persisted playback snapshot without taking ownership of decoder start/resume. */
internal class PlayerRestoreControlCoordinator(
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val elapsedRealtimeMs: () -> Long,
        val restoreSnapshot: () -> RestoredPlayerState?,
        val applyCurrentSong: (AudioFile) -> Unit,
        val clearRequestedSong: () -> Unit,
        val applyDurationMs: (Long) -> Unit,
        val applyPositionMs: (Long) -> Unit,
        val armPendingSeek: (positionMs: Long, path: String) -> Unit,
        val applyQueue: (PlayQueue) -> Unit,
        val applyPriorityQueue: (List<AudioFile>) -> Unit = {},
        /** Distinguishes a Reference-style logical queue selection from a resumable track. */
        val onQueueSelectionOnly: () -> Unit = {},
        val onPersistedNowPlaying: () -> Unit = {},
        val logInfo: (String) -> Unit,
        val traceStartup: (stage: String, detail: String, elapsedMs: Long) -> Unit,
    )

    fun restoreLastSong(): AudioFile? {
        val restoreStartMs = callbacks.elapsedRealtimeMs()
        val restored = callbacks.restoreSnapshot()
        if (restored == null) {
            callbacks.traceStartup(
                "restore_last_song_empty",
                "lastPath=blank",
                callbacks.elapsedRealtimeMs() - restoreStartMs,
            )
            return null
        }

        val persistedSong = restored.song
        // Reference exposes a current track object independently from decoder state.  When the
        // snapshot contains only queue rows, select the restored cursor (or the first row chosen
        // by persistence) for metadata/artwork/gesture ownership, but do not restore a position
        // or imply that playback has started.
        val selectedQueueIndex = restored.queueIndex
            .takeIf { it in restored.queue.indices }
            ?: if (restored.queue.isNotEmpty()) 0 else -1
        val selectedSong = persistedSong ?: restored.queue.getOrNull(selectedQueueIndex)
        if (selectedSong != null) {
            if (persistedSong == null) {
                callbacks.onQueueSelectionOnly()
            } else {
                callbacks.onPersistedNowPlaying()
            }
            callbacks.applyCurrentSong(selectedSong)
            callbacks.clearRequestedSong()
            callbacks.applyDurationMs(selectedSong.duration)

            val savedPosition = if (persistedSong != null) restored.positionMs else 0L
            if (persistedSong != null && savedPosition > 0L) {
                callbacks.applyPositionMs(savedPosition)
                callbacks.armPendingSeek(savedPosition, selectedSong.path)
            }
        }

        callbacks.logInfo(
            "RESTORE_TRACE restore_song path=${selectedSong?.path ?: "<queue-empty>"} " +
                "saved=${if (persistedSong != null) restored.positionMs else 0L} " +
                "selectedOnly=${persistedSong == null} source=${restored.source}"
        )
        callbacks.applyQueue(
            PlayQueue(
                songs = restored.queue,
                currentIndex = selectedQueueIndex,
                entryIds = restored.queueEntryIds,
            )
        )
        callbacks.applyPriorityQueue(restored.priorityQueue)
        callbacks.traceStartup(
            "restore_last_song_done",
            "source=${restored.source} repoSongs=${restored.repositorySongCount} " +
                "queue=${restored.queue.size} pos=${if (persistedSong != null) restored.positionMs else 0L} " +
                "title=${selectedSong?.title?.take(48) ?: "<queue-empty>"} " +
                "selectedOnly=${persistedSong == null}",
            callbacks.elapsedRealtimeMs() - restoreStartMs,
        )
        return selectedSong
    }
}
