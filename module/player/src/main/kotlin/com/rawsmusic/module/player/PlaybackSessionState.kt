package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns playback-session identity separately from the currently committed track.
 *
 * A playback session is the async request token used by the worker / decoder loops.
 * The current track may change during gapless or crossfade handoff without invalidating
 * the session token that keeps the existing playback loop alive.
 */
internal class PlaybackSessionState(
    private val tag: String
) {
    private val generationCounter = AtomicInteger(0)

    @Volatile
    var sessionSourcePath: String? = null

    @Volatile
    var currentTrackPath: String? = null

    @Volatile
    var preparedNextTrackPath: String? = null

    @Volatile
    var crossfadeDurationMs: Int = 0

    val generation: Int
        get() = generationCounter.get()

    fun beginNewSession(path: String, currentTrackPath: String = path): Int {
        val generation = generationCounter.incrementAndGet()
        sessionSourcePath = path
        this.currentTrackPath = currentTrackPath
        AppLogger.d(tag, "PlaybackSession: begin gen=$generation session=$path current=$currentTrackPath next=$preparedNextTrackPath crossfadeMs=$crossfadeDurationMs")
        return generation
    }

    fun beginInternalRestart(reason: String, currentTrackPath: String): Int {
        val generation = generationCounter.incrementAndGet()
        sessionSourcePath = currentTrackPath
        this.currentTrackPath = currentTrackPath
        AppLogger.d(
            tag,
            "PlaybackSession: internal restart reason=$reason gen=$generation session=$currentTrackPath current=$currentTrackPath next=$preparedNextTrackPath crossfadeMs=$crossfadeDurationMs"
        )
        return generation
    }

    fun commitCurrentTrack(path: String) {
        currentTrackPath = path
    }

    fun clearNextRequest(reason: String) {
        val oldNext = preparedNextTrackPath
        val oldCrossfade = crossfadeDurationMs
        preparedNextTrackPath = null
        crossfadeDurationMs = 0
        if (oldNext != null || oldCrossfade != 0) {
            AppLogger.d(tag, "PlaybackSession: clear next reason=$reason next=$oldNext crossfadeMs=$oldCrossfade")
        }
    }

    fun isCurrent(sourcePath: String, generation: Int): Boolean {
        return generation == generationCounter.get() && sessionSourcePath == sourcePath
    }

    fun invalidate(reason: String, clearCurrentTrack: Boolean = false): Int {
        val generation = generationCounter.incrementAndGet()
        val oldSession = sessionSourcePath
        val oldCurrent = currentTrackPath
        sessionSourcePath = null
        preparedNextTrackPath = null
        crossfadeDurationMs = 0
        if (clearCurrentTrack) {
            currentTrackPath = null
        }
        AppLogger.d(
            tag,
            "PlaybackSession: invalidate reason=$reason gen=$generation oldSession=$oldSession oldCurrent=$oldCurrent clearCurrent=$clearCurrentTrack"
        )
        return generation
    }

    fun describe(): String {
        return "gen=${generationCounter.get()} session=$sessionSourcePath current=$currentTrackPath next=$preparedNextTrackPath crossfadeMs=$crossfadeDurationMs"
    }
}
