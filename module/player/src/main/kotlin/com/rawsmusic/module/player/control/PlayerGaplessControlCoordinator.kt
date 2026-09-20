package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue

internal data class GaplessPlaybackPlan(
    val nextSongPath: String?,
    val automaticCrossfadeEnabled: Boolean,
)

internal fun resolveGaplessNextIndex(
    queue: PlayQueue,
    playMode: PlayMode,
    peekShuffleIndex: (PlayQueue) -> Int,
): Int {
    val currentIndex = queue.currentIndex
    val size = queue.songs.size
    if (size <= 0 || currentIndex !in queue.songs.indices) return -1
    return when (playMode) {
        PlayMode.REPEAT_ONE -> currentIndex
        PlayMode.SEQUENTIAL -> (currentIndex + 1).takeIf { it < size } ?: -1
        PlayMode.SHUFFLE_ALL,
        PlayMode.SHUFFLE_ONCE -> peekShuffleIndex(queue)
    }
}

/** Owns the next-decoder path/crossfade preparation policy previously embedded in PlayerController. */
internal class PlayerGaplessControlCoordinator(
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val gaplessEnabled: () -> Boolean,
        val automaticCrossfadeEnabled: () -> Boolean,
        val currentQueue: () -> PlayQueue,
        val currentPlayMode: () -> PlayMode,
        val peekShuffleIndex: (PlayQueue) -> Int,
        val applyPlan: (GaplessPlaybackPlan) -> Unit,
        val logInfo: (String) -> Unit,
        val logWarning: (String, Throwable) -> Unit,
    )

    fun prepareNextSong() {
        try {
            val gaplessEnabled = callbacks.gaplessEnabled()
            val automaticCrossfadeEnabled = callbacks.automaticCrossfadeEnabled()
            if (!gaplessEnabled && !automaticCrossfadeEnabled) {
                callbacks.applyPlan(GaplessPlaybackPlan(null, false))
                return
            }

            val queue = callbacks.currentQueue()
            val nextIndex = resolveGaplessNextIndex(
                queue = queue,
                playMode = callbacks.currentPlayMode(),
                peekShuffleIndex = callbacks.peekShuffleIndex,
            )
            val nextSong = queue.songs.getOrNull(nextIndex)
            if (nextSong == null || nextSong.path.isBlank()) {
                // A mode change can remove the previously planned next item (for example,
                // sequential playback at the end of the list). Clear the native pending slot;
                // leaving the old path alive would make the renderer crossfade into a song that
                // no longer belongs to the current Reference-style cursor.
                callbacks.applyPlan(GaplessPlaybackPlan(null, false))
                callbacks.logInfo("Gapless: no next item for mode=${callbacks.currentPlayMode()}")
                return
            }

            val plan = GaplessPlaybackPlan(
                nextSongPath = nextSong.path,
                automaticCrossfadeEnabled = automaticCrossfadeEnabled,
            )
            callbacks.applyPlan(plan)
            callbacks.logInfo(
                "Gapless: nextSong='${nextSong.title}', gapless=$gaplessEnabled, " +
                    "automaticCrossfade=$automaticCrossfadeEnabled"
            )
        } catch (error: Exception) {
            callbacks.logWarning("setupNextSongForGapless failed", error)
        }
    }
}
