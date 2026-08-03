package com.rawsmusic.module.player

import android.media.AudioTrack
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Small AudioTrack drain helper shared by streaming and file playback paths.
 *
 * It keeps drain accounting out of FfmpegAudioPlayer and, more importantly, reads
 * playbackHeadPosition defensively.  Some vendor tracks throw while they are being
 * torn down, and EOF drain must never turn a successful playback into an error.
 */
internal object AudioTrackDrainHelper {
    fun drain(
        track: AudioTrack?,
        totalBytesWritten: Long,
        frameSize: Int,
        maxDrainMs: Long = 5_000L,
        label: String,
        isPlaying: () -> Boolean,
        isReleased: () -> Boolean
    ) {
        if (track == null) return
        val safeFrameSize = frameSize.coerceAtLeast(1)
        val totalFramesWritten = (totalBytesWritten / safeFrameSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val drainStart = System.currentTimeMillis()
        try {
            while (isPlaying() && !isReleased()) {
                val headPos = safePlaybackHeadPosition(track)
                if (headPos >= totalFramesWritten) break
                if (System.currentTimeMillis() - drainStart > maxDrainMs) {
                    AppLogger.w(TAG, "$label drain timeout after ${maxDrainMs}ms, headPos=$headPos, totalFrames=$totalFramesWritten")
                    break
                }
                Thread.sleep(20)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "$label drain interrupted by AudioTrack error", t)
        }
        val finalHead = safePlaybackHeadPosition(track)
        AppLogger.w(TAG, "$label drain complete, headPos=$finalHead, totalFrames=$totalFramesWritten, elapsed=${System.currentTimeMillis() - drainStart}ms")
    }

    private fun safePlaybackHeadPosition(track: AudioTrack): Int {
        return try {
            track.playbackHeadPosition
        } catch (_: Throwable) {
            -1
        }
    }

    private const val TAG = "AudioTrackDrainHelper"
}
