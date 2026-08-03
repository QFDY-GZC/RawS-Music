package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.player.statemachine.PlaybackEventQueue
import com.rawsmusic.module.player.statemachine.PlaybackEventQueue.PlaybackEvent as PE

internal interface PlayerSeekEventQueue {
    val isRunning: Boolean
    fun submitSeek(
        positionMs: Long,
        songPath: String,
        handler: suspend (Long, String) -> Unit,
    )
}

internal class PlaybackEventQueueSeekAdapter(
    private val delegate: PlaybackEventQueue,
) : PlayerSeekEventQueue {
    override val isRunning: Boolean
        get() = delegate.isRunning

    override fun submitSeek(
        positionMs: Long,
        songPath: String,
        handler: suspend (Long, String) -> Unit,
    ) {
        delegate.submit(PE.SeekEvent(positionMs, songPath, handler))
    }
}

/**
 * Owns public seek command policy while leaving decoder and USB implementation in PlayerController.
 */
internal class PlayerSeekControlCoordinator(
    private val eventQueue: PlayerSeekEventQueue,
    private val callbacks: Callbacks,
) {
    internal data class Callbacks(
        val isReleased: () -> Boolean,
        val currentSong: () -> AudioFile?,
        val armPreviousRestartBypass: () -> Unit,
        val isPaused: () -> Boolean,
        val setDisplayedPosition: (Long) -> Unit,
        val markSeekPerformed: () -> Unit,
        val isSameSongIdentity: (AudioFile?, AudioFile) -> Boolean,
        val executePausedSeekRequest: suspend (AudioFile, Long, Long) -> Unit,
        val executeSeekRequest: (AudioFile, Long, Long, Boolean) -> Unit,
        val logInfo: (String) -> Unit,
    )

    fun seekTo(positionMs: Long, userInitiated: Boolean = true) {
        if (callbacks.isReleased()) return
        val song = callbacks.currentSong() ?: return
        if (userInitiated) callbacks.armPreviousRestartBypass()

        val keepPaused = callbacks.isPaused()
        val realSeekMs = if (song.cueOffsetMs > 0L) {
            song.cueOffsetMs + positionMs
        } else {
            positionMs
        }.coerceAtLeast(0L)
        val displaySeekMs = if (song.cueOffsetMs > 0L) positionMs else realSeekMs

        callbacks.setDisplayedPosition(displaySeekMs)
        callbacks.markSeekPerformed()

        if (keepPaused && eventQueue.isRunning) {
            val requestedSong = song
            eventQueue.submitSeek(realSeekMs, song.path) handler@{ queuedRealSeekMs, _ ->
                if (!callbacks.isSameSongIdentity(callbacks.currentSong(), requestedSong)) {
                    callbacks.logInfo("paused seek dropped: song changed target=${requestedSong.path}")
                    return@handler
                }
                callbacks.executePausedSeekRequest(
                    requestedSong,
                    queuedRealSeekMs,
                    displaySeekMs,
                )
            }
            return
        }

        callbacks.executeSeekRequest(song, realSeekMs, displaySeekMs, keepPaused)
    }
}
