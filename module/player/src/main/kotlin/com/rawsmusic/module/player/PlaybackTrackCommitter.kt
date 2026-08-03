package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/**
 * Applies the final bookkeeping after a gapless/crossfade decoder handoff.
 *
 * Decoder ownership and ring-buffer/thread replacement still happen in
 * FfmpegAudioPlayer/DecoderHandoffController. This helper keeps the visible
 * track commit atomic and consistent: current track identity, position,
 * duration, hardware-position reset, next-track request cleanup, and listener
 * notifications are updated in one place.
 */
internal class PlaybackTrackCommitter(private val tag: String) {
    data class CommitResult(
        val reason: String,
        val path: String,
        val positionMs: Long,
        val durationMs: Long
    )

    fun commit(
        reason: String,
        path: String,
        decoderHandle: Long,
        startPositionMs: Long,
        durationProvider: (Long) -> Long,
        setCurrentPath: (String) -> Unit,
        setPositionMs: (Long) -> Unit,
        setDurationMs: (Long) -> Unit,
        resetHardwarePosition: () -> Unit,
        clearNextRequest: () -> Unit,
        listener: FfmpegAudioPlayer.Listener?
    ): CommitResult {
        val position = startPositionMs.coerceAtLeast(0L)
        val duration = durationProvider(decoderHandle).coerceAtLeast(0L)

        // Update internal track identity before listener notification so that any
        // immediate seek/rebuild triggered by PlayerController observes the new song.
        setCurrentPath(path)
        setPositionMs(position)
        setDurationMs(duration)
        resetHardwarePosition()

        // Clear the consumed request before notifying. If the listener schedules a
        // new nextSongPath synchronously, we must not wipe it after the callback.
        clearNextRequest()

        try {
            listener?.onStateChanged(FfmpegAudioPlayer.State.PLAYING)
            listener?.onPositionChanged(position, duration)
            listener?.onGaplessSongChanged(path)
        } catch (t: Throwable) {
            AppLogger.w(tag, "Track commit[$reason]: listener callback failed path=$path", t)
        }

        AppLogger.d(
            tag,
            "Track commit[$reason]: path=$path pos=${position}ms dur=${duration}ms handle=$decoderHandle"
        )
        return CommitResult(reason = reason, path = path, positionMs = position, durationMs = duration)
    }
}
