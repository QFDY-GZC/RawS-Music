package com.rawsmusic.module.player

import android.media.AudioTrack
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Owns AudioTrack detach / stop / flush / release ordering for FfmpegAudioPlayer.
 *
 * The player must clear its shared AudioTrack reference before releasing the track
 * so route callbacks, resume checks, and rebuild code never see a half-released
 * AudioTrack instance.
 */
internal class AudioTrackLifecycleController(
    private val tag: String,
    private val detachCurrent: () -> AudioTrack?
) {
    fun detach(reason: String): AudioTrack? {
        val track = detachCurrent()
        if (track != null) {
            AppLogger.i(tag, "AudioTrack detached: reason=$reason sessionId=${safeSessionId(track)}")
        }
        return track
    }

    fun detachAndRelease(
        reason: String,
        stop: Boolean,
        flush: Boolean
    ) {
        releaseDetached(
            track = detach(reason),
            reason = reason,
            stop = stop,
            flush = flush
        )
    }

    fun stopDetached(track: AudioTrack?, reason: String) {
        if (track == null) return
        runCatching { track.stop() }
            .onFailure { AppLogger.w(tag, "AudioTrack stop failed: reason=$reason", it) }
    }

    fun releaseDetached(
        track: AudioTrack?,
        reason: String,
        stop: Boolean,
        flush: Boolean
    ) {
        if (track == null) return

        if (stop) {
            runCatching { track.stop() }
                .onFailure { AppLogger.w(tag, "AudioTrack stop before release failed: reason=$reason", it) }
        }

        if (flush) {
            runCatching { track.flush() }
                .onFailure { AppLogger.w(tag, "AudioTrack flush before release failed: reason=$reason", it) }
        }

        runCatching { track.release() }
            .onSuccess { AppLogger.i(tag, "AudioTrack released: reason=$reason sessionId=${safeSessionId(track)}") }
            .onFailure { AppLogger.w(tag, "AudioTrack release failed: reason=$reason", it) }
    }

    private fun safeSessionId(track: AudioTrack): Int =
        runCatching { track.audioSessionId }.getOrDefault(AudioTrack.ERROR)
}
