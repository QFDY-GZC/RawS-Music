package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode

/**
 * Owns the controller policy triggered by backend state callbacks.
 *
 * The renderer reports only a small [BackendState]. This coordinator decides how that state updates
 * the public player state, progress clock, audio session, failure fuse, queue fallback and playback
 * completion policy. It deliberately has no FFmpeg, Android or USB-engine ownership.
 */
class PlayerBackendStateControlCoordinator(
    private val callbacks: Callbacks,
) {
    enum class BackendState {
        IDLE,
        PREPARING,
        PLAYING,
        PAUSED,
        STOPPED,
        ERROR,
        COMPLETED,
    }

    enum class UnavailableSourceResult {
        ADVANCED,
        CLEARED_SINGLE_SOURCE,
        STOPPED_FAILURE_FUSE,
    }

    data class Callbacks(
        val isReleased: () -> Boolean,
        val forcePlayState: (state: PlayState, reason: String) -> Unit,
        val startProgressUpdate: () -> Unit,
        val stopProgressUpdate: () -> Unit,
        val audioSessionId: () -> Int,
        val onAudioSessionReady: (sessionId: Int) -> Unit,
        val lastPlayerError: () -> String?,
        val isUsbExclusiveActive: () -> Boolean,
        val backendUsbExclusiveMode: () -> Boolean,
        val currentQueue: () -> PlayQueue,
        val currentSong: () -> AudioFile?,
        val currentRepeatMode: () -> RepeatMode,
        val consumePlaybackCompletion: () -> Boolean,
        val playTransport: (song: AudioFile, queue: List<AudioFile>, index: Int) -> Unit,
        val replayCurrentSong: (song: AudioFile) -> Unit,
        val pauseTransport: () -> Unit,
        val nextTransport: () -> Unit,
        val stopTransport: () -> Unit,
        val clearUnavailableSong: (song: AudioFile) -> Unit,
        val logDebug: (message: String) -> Unit,
        val logWarning: (message: String) -> Unit,
    )

    private var consecutiveFailures: Int = 0

    fun resetFailures() {
        consecutiveFailures = 0
    }

    fun handleUnavailableSource(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
    ): UnavailableSourceResult {
        consecutiveFailures++
        if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
            callbacks.logWarning(
                "Unavailable-source failure fuse opened after $consecutiveFailures attempts"
            )
            callbacks.stopTransport()
            resetFailures()
            return UnavailableSourceResult.STOPPED_FAILURE_FUSE
        }

        if (queue.size > 1) {
            val nextIndex = (index + 1).floorMod(queue.size)
            callbacks.playTransport(queue[nextIndex], queue, nextIndex)
            return UnavailableSourceResult.ADVANCED
        }

        callbacks.clearUnavailableSong(song)
        return UnavailableSourceResult.CLEARED_SINGLE_SOURCE
    }

    fun onStateChanged(state: BackendState) {
        if (callbacks.isReleased()) return

        when (state) {
            BackendState.PLAYING -> {
                resetFailures()
                callbacks.forcePlayState(PlayState.PLAYING, "recover_success")
                callbacks.startProgressUpdate()
                val sessionId = callbacks.audioSessionId()
                callbacks.logDebug("Player PLAYING, audioSessionId=$sessionId")
                if (sessionId != 0) callbacks.onAudioSessionReady(sessionId)
            }

            BackendState.PAUSED -> {
                callbacks.forcePlayState(PlayState.PAUSED, "recover_paused")
                callbacks.stopProgressUpdate()
            }

            BackendState.PREPARING -> {
                callbacks.forcePlayState(PlayState.PREPARING, "recover_preparing")
            }

            BackendState.STOPPED -> {
                callbacks.forcePlayState(PlayState.STOPPED, "recover_stopped")
                callbacks.stopProgressUpdate()
            }

            BackendState.ERROR -> handleErrorState()
            BackendState.COMPLETED -> {
                callbacks.forcePlayState(PlayState.STOPPED, "recover_final_stop")
                callbacks.stopProgressUpdate()
                handlePlaybackComplete()
            }

            BackendState.IDLE -> {
                callbacks.forcePlayState(PlayState.IDLE, "recover_idle")
            }
        }
    }

    private fun handleErrorState() {
        callbacks.forcePlayState(PlayState.ERROR, "recover_error")
        callbacks.stopProgressUpdate()

        val errorMessage = callbacks.lastPlayerError()
        consecutiveFailures++
        callbacks.logWarning(
            "Playback entered ERROR, consecutiveFailures=$consecutiveFailures, " +
                "usb=${callbacks.isUsbExclusiveActive()}, msg=$errorMessage"
        )

        if (!shouldAutoAdvanceOnError(errorMessage)) {
            callbacks.logWarning("Not auto-advancing after playback error")
            return
        }

        if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
            callbacks.logWarning("Too many consecutive failures, stopping playback")
            callbacks.stopTransport()
            resetFailures()
            return
        }

        val queue = callbacks.currentQueue()
        if (queue.songs.size > 1) {
            val nextIndex = (queue.currentIndex + 1).floorMod(queue.songs.size)
            callbacks.playTransport(queue.songs[nextIndex], queue.songs, nextIndex)
        }
    }

    private fun shouldAutoAdvanceOnError(message: String?): Boolean {
        if (callbacks.isUsbExclusiveActive() || callbacks.backendUsbExclusiveMode()) return false
        val normalized = message?.lowercase().orEmpty()
        return OUTPUT_ERROR_MARKERS.none(normalized::contains)
    }

    private fun handlePlaybackComplete() {
        if (callbacks.isReleased()) return
        if (callbacks.consumePlaybackCompletion()) {
            callbacks.pauseTransport()
            return
        }

        when (callbacks.currentRepeatMode()) {
            RepeatMode.ONE -> callbacks.currentSong()?.let(callbacks.replayCurrentSong)

            RepeatMode.ALL -> callbacks.nextTransport()
            RepeatMode.OFF -> {
                val queue = callbacks.currentQueue()
                if (queue.currentIndex < queue.songs.lastIndex) callbacks.nextTransport()
            }
        }
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5

        val OUTPUT_ERROR_MARKERS = listOf(
            "usb",
            "claim",
            "resource busy",
            "audiotrack",
            "device",
            "voice_communication",
            "sco",
            "bluetooth",
        )
    }
}
