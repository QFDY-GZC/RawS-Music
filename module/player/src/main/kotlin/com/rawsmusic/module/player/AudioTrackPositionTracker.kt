package com.rawsmusic.module.player

import android.media.AudioTimestamp
import android.media.AudioTrack

/**
 * Tracks Android AudioTrack playback position using hardware timestamps when available.
 *
 * FfmpegAudioPlayer owns the playback state and duration clamp; this class only owns the
 * mutable timestamp scratch state so route/rebuild/seek handling does not spread timestamp
 * internals through the player loop.
 */
class AudioTrackPositionTracker {
    private val timestamp = AudioTimestamp()
    private var lastFramePosition = 0L
    private var lastNanoTime = 0L
    private var timestampValid = false

    fun reset() {
        lastFramePosition = 0L
        lastNanoTime = 0L
        timestampValid = false
    }

    /**
     * Returns a hardware-derived position in milliseconds, or -1 when AudioTimestamp is
     * unavailable for this track/sample-rate pair.
     */
    fun hardwarePositionMs(track: AudioTrack?, sampleRate: Int): Long {
        if (track == null || sampleRate <= 0) return -1L
        return try {
            if (!track.getTimestamp(timestamp)) {
                timestampValid = false
                return -1L
            }

            val hardwareFramePosition = timestamp.framePosition
            val hardwarePositionMs = (hardwareFramePosition * 1000L) / sampleRate
            val elapsedNs = (System.nanoTime() - timestamp.nanoTime).coerceAtLeast(0L)
            val currentPositionMs = hardwarePositionMs + elapsedNs / 1_000_000L

            lastFramePosition = hardwareFramePosition
            lastNanoTime = timestamp.nanoTime
            timestampValid = true

            currentPositionMs
        } catch (_: Exception) {
            timestampValid = false
            -1L
        }
    }
}
