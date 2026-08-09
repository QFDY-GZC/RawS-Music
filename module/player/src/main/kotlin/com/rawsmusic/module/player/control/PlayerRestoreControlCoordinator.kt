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

        val song = restored.song
        callbacks.applyCurrentSong(song)
        callbacks.clearRequestedSong()
        callbacks.applyDurationMs(song.duration)

        val savedPosition = restored.positionMs
        if (savedPosition > 0L) {
            callbacks.applyPositionMs(savedPosition)
            callbacks.armPendingSeek(savedPosition, song.path)
        }

        callbacks.logInfo(
            "RESTORE_TRACE restore_song path=${song.path} saved=$savedPosition " +
                "source=${restored.source}"
        )
        callbacks.applyQueue(
            PlayQueue(
                songs = restored.queue,
                currentIndex = restored.queueIndex,
            )
        )
        callbacks.traceStartup(
            "restore_last_song_done",
            "source=${restored.source} repoSongs=${restored.repositorySongCount} " +
                "queue=${restored.queue.size} pos=$savedPosition title=${song.title.take(48)}",
            callbacks.elapsedRealtimeMs() - restoreStartMs,
        )
        return song
    }
}
