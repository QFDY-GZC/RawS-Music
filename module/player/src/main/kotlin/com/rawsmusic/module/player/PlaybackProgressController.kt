package com.rawsmusic.module.player

import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Owns the 20 Hz playback progress loop and seek-settle guard.
 *
 * PlayerController supplies state callbacks so this class does not own transport,
 * queue, USB, persistence, or stats policy.
 */
internal class PlaybackProgressController(
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
    private val logTag: String,
) {
    data class Callbacks(
        val isReleased: () -> Boolean,
        val playerPositionMs: () -> Long,
        val playerDurationMs: () -> Long,
        val cueOffsetMs: () -> Long,
        val cueEndMs: () -> Long,
        val cueSongDurationMs: () -> Long,
        val displayedPositionMs: () -> Long,
        val setDisplayedPositionMs: (Long) -> Unit,
        val displayedDurationMs: () -> Long,
        val setDisplayedDurationMs: (Long) -> Unit,
        val currentSong: () -> AudioFile?,
        val onProgress: (AudioFile?, Long, Long) -> Unit,
        val shouldSyncUsbMediaIdentity: () -> Boolean,
        val syncUsbMediaIdentity: (AudioFile?, Long) -> Unit,
        val savePosition: () -> Unit,
        val onCueTrackEnd: () -> Unit,
    )

    private var progressJob: Job? = null

    @Volatile
    private var seekJustPerformed = false

    @Volatile
    private var seekMismatchPolls = 0

    fun markSeekPerformed() {
        seekMismatchPolls = 0
        seekJustPerformed = true
    }

    fun start() {
        stop()
        progressJob = scope.launch {
            var saveCounter = 0
            var logCounter = 0
            var lastLoggedPositionMs = -1L

            while (isActive && !callbacks.isReleased()) {
                try {
                    val playerPositionMs = callbacks.playerPositionMs()
                    val cueEndMs = callbacks.cueEndMs()
                    val cueOffsetMs = callbacks.cueOffsetMs()
                    val isCue = cueEndMs > 0L
                    val displayPositionMs = if (isCue && cueOffsetMs > 0L) {
                        (playerPositionMs - cueOffsetMs).coerceAtLeast(0L)
                    } else {
                        playerPositionMs
                    }

                    updateDisplayedPosition(displayPositionMs)
                    updateDisplayedDuration(isCue)

                    val displayedDurationMs = callbacks.displayedDurationMs()
                    callbacks.onProgress(
                        callbacks.currentSong(),
                        displayPositionMs,
                        displayedDurationMs,
                    )

                    logCounter++
                    if (logCounter >= LOG_INTERVAL_TICKS) {
                        logCounter = 0
                        if (playerPositionMs != lastLoggedPositionMs) {
                            Log.d(
                                logTag,
                                ">>> progressUpdate: ffmpegPos=$playerPositionMs, " +
                                    "displayPos=$displayPositionMs, cueEnd=$cueEndMs",
                            )
                            lastLoggedPositionMs = playerPositionMs
                        }
                    }

                    if (callbacks.shouldSyncUsbMediaIdentity() && logCounter == 0) {
                        callbacks.syncUsbMediaIdentity(
                            callbacks.currentSong(),
                            displayPositionMs.coerceAtLeast(0L),
                        )
                    }

                    saveCounter++
                    if (saveCounter >= SAVE_INTERVAL_TICKS) {
                        saveCounter = 0
                        callbacks.savePosition()
                    }

                    if (isCue && playerPositionMs >= cueEndMs) {
                        Log.d(
                            logTag,
                            "CUE track end reached: pos=$playerPositionMs >= " +
                                "cueEndMs=$cueEndMs, advancing to next",
                        )
                        callbacks.onCueTrackEnd()
                        break
                    }
                } catch (_: Exception) {
                    break
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updateDisplayedPosition(displayPositionMs: Long) {
        if (!seekJustPerformed) {
            callbacks.setDisplayedPositionMs(displayPositionMs)
            return
        }

        val targetPositionMs = callbacks.displayedPositionMs()
        if (abs(displayPositionMs - targetPositionMs) < SEEK_SETTLED_TOLERANCE_MS) {
            seekJustPerformed = false
            seekMismatchPolls = 0
            return
        }

        seekMismatchPolls++
        if (seekMismatchPolls > SEEK_SETTLE_MAX_POLLS) {
            seekJustPerformed = false
            seekMismatchPolls = 0
        }
    }

    private fun updateDisplayedDuration(isCue: Boolean) {
        if (isCue) {
            val cueDurationMs = callbacks.cueSongDurationMs()
            if (callbacks.displayedDurationMs() != cueDurationMs) {
                callbacks.setDisplayedDurationMs(cueDurationMs)
            }
            return
        }

        val playerDurationMs = callbacks.playerDurationMs()
        if (playerDurationMs > 0L && callbacks.displayedDurationMs() != playerDurationMs) {
            callbacks.setDisplayedDurationMs(playerDurationMs)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 50L
        const val SAVE_INTERVAL_TICKS = 25
        const val LOG_INTERVAL_TICKS = 100
        const val SEEK_SETTLED_TOLERANCE_MS = 500L
        const val SEEK_SETTLE_MAX_POLLS = 20
    }
}
