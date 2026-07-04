package com.rawsmusic.module.player

import android.media.AudioTrack
import com.rawsmusic.core.common.utils.AppLogger

/** Runtime counters/log throttling for the Android AudioTrack streaming loop. */
internal class AudioTrackStreamMetrics(private val logTag: String) {
    var totalBytesWrittenToTrack: Long = 0L
        private set

    private var lastStreamLogMs = System.currentTimeMillis()
    private var writeCyclesSinceLog = 0
    private var trackWriteErrorCount = 0
    private var lastHeadPos = 0

    fun seedWrittenBytes(bytes: Long) {
        totalBytesWrittenToTrack = bytes.coerceAtLeast(0L)
        lastStreamLogMs = System.currentTimeMillis()
        writeCyclesSinceLog = 0
        trackWriteErrorCount = 0
        lastHeadPos = 0
    }

    fun recordWriteResult(result: Int) {
        writeCyclesSinceLog++
        if (result > 0) {
            totalBytesWrittenToTrack += result.toLong()
        } else if (result < 0) {
            trackWriteErrorCount++
        }
    }

    fun maybeLog(track: AudioTrack, rbAvailable: Int, positionMs: Long) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastStreamLogMs < 5_000L) return

        val currentHeadPos = try { track.playbackHeadPosition } catch (_: Throwable) { -1 }
        val currentPlayState = try { track.playState } catch (_: Throwable) { -1 }
        val headAdvance = if (currentHeadPos > lastHeadPos) currentHeadPos - lastHeadPos else 0
        AppLogger.d(
            logTag,
            "Streaming status: writeCycles=$writeCyclesSinceLog, track.write.errors=$trackWriteErrorCount, " +
                "headPos=$currentHeadPos(+$headAdvance), playState=$currentPlayState, " +
                "totalWritten=${totalBytesWrittenToTrack}B, rb.avail=$rbAvailable, _posMs=$positionMs"
        )
        lastStreamLogMs = nowMs
        writeCyclesSinceLog = 0
        lastHeadPos = currentHeadPos
    }
}
