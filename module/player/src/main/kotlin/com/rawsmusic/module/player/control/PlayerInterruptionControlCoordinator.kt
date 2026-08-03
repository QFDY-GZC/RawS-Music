package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState

/**
 * Owns PlayerController's host-side response to Android audio interruptions.
 *
 * AndroidAudioInterruptionController still owns AudioFocus, phone/noisy/SCO policy. This class owns
 * only the player command decisions that follow those platform callbacks: deciding whether playback
 * is active, pausing a non-USB backend, and resuming or rebuilding the remembered queue item.
 */
class PlayerInterruptionControlCoordinator(
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

    data class Callbacks(
        val isReleased: () -> Boolean,
        val isUsbExclusiveActive: () -> Boolean,
        val backendIsPlayingNow: () -> Boolean,
        val backendState: () -> BackendState,
        val controllerPlayState: () -> PlayState,
        val pauseBackend: () -> Unit,
        val transitionToPaused: (reason: String) -> Unit,
        val stopProgressUpdate: () -> Unit,
        val savePosition: () -> Unit,
        val saveState: () -> Unit,
        val currentSong: () -> AudioFile?,
        val restoreLastSong: () -> AudioFile?,
        val currentQueue: () -> PlayQueue,
        val samePlaybackItem: (AudioFile, AudioFile) -> Boolean,
        val resumeTransport: () -> Unit,
        val playTransport: (song: AudioFile, queue: List<AudioFile>, index: Int) -> Unit,
        val logInfo: (message: String) -> Unit,
    )

    fun isPlaybackActive(): Boolean =
        callbacks.backendIsPlayingNow() ||
            callbacks.backendState() == BackendState.PLAYING ||
            callbacks.backendState() == BackendState.PREPARING ||
            callbacks.controllerPlayState() == PlayState.PLAYING ||
            callbacks.controllerPlayState() == PlayState.PREPARING

    fun pauseForInterruption(reason: String): Boolean {
        if (callbacks.isUsbExclusiveActive() || !isPlaybackActive()) return false

        callbacks.pauseBackend()
        callbacks.transitionToPaused("audio_focus_$reason")
        callbacks.stopProgressUpdate()
        callbacks.savePosition()
        callbacks.saveState()
        return true
    }

    fun resumeOrStartRememberedSong(reason: String): Boolean {
        if (callbacks.isReleased() || isPlaybackActive()) return false
        val song = callbacks.currentSong() ?: callbacks.restoreLastSong() ?: return false
        callbacks.logInfo("AudioFocus: automatic playback reason=$reason title=${song.title}")

        if (callbacks.backendState() == BackendState.PAUSED) {
            callbacks.resumeTransport()
            return true
        }

        val queueSnapshot = callbacks.currentQueue()
        val songs = queueSnapshot.songs.ifEmpty { listOf(song) }
        val index = songs.indexOfFirst { candidate -> callbacks.samePlaybackItem(candidate, song) }
            .takeIf { it >= 0 }
            ?: queueSnapshot.currentIndex.coerceIn(0, songs.lastIndex)
        callbacks.playTransport(songs[index], songs, index)
        return true
    }
}
