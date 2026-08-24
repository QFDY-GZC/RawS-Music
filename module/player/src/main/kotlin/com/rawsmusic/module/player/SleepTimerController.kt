package com.rawsmusic.module.player

import android.os.SystemClock
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns all timer modes and reports when a completed track must stop playback. */
internal class SleepTimerController(
    private val scope: CoroutineScope,
    private val pausePlayback: () -> Unit
) {
    private var timerJob: Job? = null
    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    private var endElapsedTimeMs = 0L
    private var stopAfterCurrentSong = false
    private var songsUntilStop = 0

    fun restorePersistedTimer() {
        timerJob?.cancel()
        timerJob = null
        endElapsedTimeMs = 0L
        _remainingMs.value = 0L
        stopAfterCurrentSong = false
        songsUntilStop = 0
        when (AppPreferences.Player.sleepTimerMode) {
            MODE_MINUTES -> {
                val remaining = AppPreferences.Player.sleepTimerDeadlineEpochMs -
                    System.currentTimeMillis()
                if (remaining > 0L) {
                    startMinutesInternal(remaining, persist = false)
                } else {
                    cancel()
                }
            }
            MODE_SONGS -> {
                songsUntilStop = AppPreferences.Player.sleepTimerSongs.coerceAtLeast(1)
            }
            MODE_CURRENT_SONG -> {
                stopAfterCurrentSong = AppPreferences.Player.stopAfterCurrent
            }
            else -> Unit
        }
    }

    fun startMinutes(minutes: Int) {
        val durationMs = minutes.coerceAtLeast(1) * 60_000L
        AppPreferences.Player.sleepTimerMode = MODE_MINUTES
        AppPreferences.Player.sleepTimerMinutes = minutes
        AppPreferences.Player.sleepTimerDeadlineEpochMs =
            System.currentTimeMillis() + durationMs
        startMinutesInternal(durationMs, persist = false)
    }

    private fun startMinutesInternal(durationMs: Long, persist: Boolean) {
        timerJob?.cancel()
        endElapsedTimeMs = SystemClock.elapsedRealtime() + durationMs
        _remainingMs.value = durationMs
        stopAfterCurrentSong = false
        songsUntilStop = 0
        if (persist) {
            AppPreferences.Player.sleepTimerMode = MODE_MINUTES
            AppPreferences.Player.sleepTimerDeadlineEpochMs =
                System.currentTimeMillis() + durationMs
        }
        timerJob = scope.launch {
            while (isActive) {
                val remaining = endElapsedTimeMs - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    _remainingMs.value = 0L
                    pausePlayback()
                    cancel()
                    break
                }
                _remainingMs.value = remaining
                delay(1000L)
            }
        }
    }

    fun startSongCount(count: Int) {
        cancel()
        songsUntilStop = count.coerceAtLeast(1)
        AppPreferences.Player.sleepTimerMode = MODE_SONGS
        AppPreferences.Player.sleepTimerSongs = songsUntilStop
        AppPreferences.Player.sleepTimerDeadlineEpochMs = 0L
    }

    fun stopAfterCurrent() {
        cancel()
        stopAfterCurrentSong = true
        AppPreferences.Player.sleepTimerMode = MODE_CURRENT_SONG
        AppPreferences.Player.stopAfterCurrent = true
    }

    /** Returns true when normal repeat/next handling must not continue. */
    fun consumePlaybackCompletion(): Boolean {
        if (stopAfterCurrentSong) {
            stopAfterCurrentSong = false
            AppPreferences.Player.stopAfterCurrent = false
            AppPreferences.Player.sleepTimerMode = MODE_OFF
            return true
        }
        if (songsUntilStop > 0) {
            songsUntilStop--
            if (songsUntilStop <= 0) {
                AppPreferences.Player.sleepTimerSongs = 0
                AppPreferences.Player.sleepTimerMode = MODE_OFF
                return true
            }
            // The remaining count is part of the timer state. Persist the decrement so a
            // process restart cannot restore the original count and keep playback running.
            AppPreferences.Player.sleepTimerSongs = songsUntilStop
        }
        return false
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        endElapsedTimeMs = 0L
        _remainingMs.value = 0L
        AppPreferences.Player.sleepTimerDeadlineEpochMs = 0L
        stopAfterCurrentSong = false
        songsUntilStop = 0
        AppPreferences.Player.sleepTimerMode = MODE_OFF
        AppPreferences.Player.stopAfterCurrent = false
    }

    fun songsRemaining(): Int = songsUntilStop

    fun isStopAfterCurrentEnabled(): Boolean = stopAfterCurrentSong

    fun isActive(): Boolean = timerJob?.isActive == true || stopAfterCurrentSong || songsUntilStop > 0

    fun mode(): Int = AppPreferences.Player.sleepTimerMode

    private companion object {
        const val MODE_OFF = 0
        const val MODE_MINUTES = 1
        const val MODE_SONGS = 2
        const val MODE_CURRENT_SONG = 3
    }
}
