package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile

/** Stateful, engine-agnostic duplicate-play admission gate. */
internal class PlayerDuplicatePlayRequestGate(
    private val duplicateWindowMs: Long = 1_200L,
) {
    private var lastPath: String? = null
    private var lastCueOffsetMs: Long = 0L
    private var lastCueTrackIndex: Int = 0
    private var lastRequestElapsedMs: Long = Long.MIN_VALUE

    fun shouldIgnore(
        song: AudioFile,
        nowElapsedMs: Long,
        backendPreparingPlayingOrPaused: Boolean,
    ): Boolean {
        val sameItem = song.path == lastPath &&
            song.cueOffsetMs == lastCueOffsetMs &&
            song.cueTrackIndex == lastCueTrackIndex
        val withinWindow = lastRequestElapsedMs != Long.MIN_VALUE &&
            nowElapsedMs - lastRequestElapsedMs in 0 until duplicateWindowMs
        val ignore = sameItem && withinWindow && backendPreparingPlayingOrPaused
        if (!ignore) {
            lastPath = song.path
            lastCueOffsetMs = song.cueOffsetMs
            lastCueTrackIndex = song.cueTrackIndex
            lastRequestElapsedMs = nowElapsedMs
        }
        return ignore
    }

    fun clear() {
        lastPath = null
        lastCueOffsetMs = 0L
        lastCueTrackIndex = 0
        lastRequestElapsedMs = Long.MIN_VALUE
    }
}
