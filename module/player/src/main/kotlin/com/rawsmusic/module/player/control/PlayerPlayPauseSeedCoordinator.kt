package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue

/** Resolves the first play/pause seed without owning transport or decoder startup. */
internal class PlayerPlayPauseSeedCoordinator(
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val currentSong: () -> AudioFile?,
        val restoreLastSong: () -> AudioFile?,
        val currentQueue: () -> PlayQueue,
        val applyCurrentSong: (AudioFile) -> Unit,
        val applyDurationMs: (Long) -> Unit,
        val loadRepositorySongs: () -> List<AudioFile>,
        val applyQueue: (PlayQueue) -> Unit,
        val elapsedRealtimeMs: () -> Long,
        val traceStartup: (stage: String, detail: String, elapsedMs: Long) -> Unit,
        val logDebug: (String) -> Unit,
        val logWarning: (String) -> Unit,
    )

    fun resolve(): AudioFile? {
        callbacks.currentSong()?.let { return it }

        callbacks.restoreLastSong()?.let { restored ->
            callbacks.logDebug("playPause seed: restored last song ${restored.path}")
            return restored
        }

        val queue = callbacks.currentQueue()
        if (queue.songs.isNotEmpty()) {
            val index = queue.currentIndex.coerceIn(0, queue.songs.lastIndex)
            return queue.songs[index].also { song ->
                callbacks.applyCurrentSong(song)
                if (song.duration > 0L) callbacks.applyDurationMs(song.duration)
                callbacks.logDebug("playPause seed: using queue index=$index ${song.path}")
            }
        }

        val loadStartMs = callbacks.elapsedRealtimeMs()
        val repositorySongs = runCatching(callbacks.loadRepositorySongs).getOrDefault(emptyList())
        callbacks.traceStartup(
            "play_pause_seed_repo_load",
            "songs=${repositorySongs.size}",
            callbacks.elapsedRealtimeMs() - loadStartMs,
        )
        if (repositorySongs.isNotEmpty()) {
            val song = repositorySongs.first()
            callbacks.applyQueue(PlayQueue(songs = repositorySongs, currentIndex = 0))
            callbacks.applyCurrentSong(song)
            if (song.duration > 0L) callbacks.applyDurationMs(song.duration)
            callbacks.logDebug(
                "playPause seed: using first repository song size=${repositorySongs.size} path=${song.path}"
            )
            return song
        }

        callbacks.logWarning("playPause seed missing: currentSong=null queue=empty repo=empty")
        return null
    }
}
