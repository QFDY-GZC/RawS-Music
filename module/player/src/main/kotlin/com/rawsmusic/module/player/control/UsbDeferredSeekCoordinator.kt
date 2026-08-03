package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns the cancellable wait/retry lifecycle for a seek requested before USB is ready. */
class UsbDeferredSeekCoordinator(
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val isReleased: () -> Boolean,
        val currentSong: () -> AudioFile?,
        val isSameSong: (AudioFile?, AudioFile?) -> Boolean,
        val isRuntimeReady: () -> Boolean,
        val executeSeek: suspend (realSeekMs: Long, displaySeekMs: Long, keepPaused: Boolean) -> Unit,
        val retainPendingSeek: (song: AudioFile, realSeekMs: Long) -> Unit,
        val logInfo: (String) -> Unit,
        val logWarning: (String) -> Unit,
    )

    private var retryJob: Job? = null

    fun defer(
        song: AudioFile,
        realSeekMs: Long,
        displaySeekMs: Long,
        keepPaused: Boolean,
        reason: String,
    ) {
        retryJob?.cancel()
        retryJob = scope.launch {
            repeat(12) { attempt ->
                if (callbacks.isReleased()) return@launch
                if (!callbacks.isSameSong(callbacks.currentSong(), song)) {
                    callbacks.logInfo("deferred USB seek cancelled: song changed reason=$reason")
                    return@launch
                }
                if (callbacks.isRuntimeReady()) {
                    callbacks.logInfo(
                        "deferred USB seek ready: attempt=${attempt + 1} " +
                            "real=$realSeekMs display=$displaySeekMs reason=$reason"
                    )
                    callbacks.executeSeek(realSeekMs, displaySeekMs, keepPaused)
                    return@launch
                }
                delay(UsbSeekRuntimePolicy.retryDelayMs(attempt))
            }

            callbacks.retainPendingSeek(song, realSeekMs)
            callbacks.logWarning(
                "deferred USB seek timed out: keep pending real=$realSeekMs " +
                    "display=$displaySeekMs reason=$reason"
            )
        }
    }

    fun cancel() {
        retryJob?.cancel()
        retryJob = null
    }
}
