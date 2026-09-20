package com.rawsmusic.helper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.core.common.model.AudioFile

/**
 * 迷你播放栏状态协调器。
 *
 * 管理 title / artist / isPlaying / progress / coverPath，
 * 替代 MainActivity 里散落的 miniPlayerXxx 字段。
 */
class MiniPlayerCoordinator(
    private val resolveCover: (AudioFile) -> String,
    private val noMusicText: () -> String
) {
    var title by mutableStateOf("")
        private set

    var artist by mutableStateOf("")
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    var positionMs by androidx.compose.runtime.mutableLongStateOf(0L)
        private set

    var durationMs by androidx.compose.runtime.mutableLongStateOf(0L)
        private set

    var coverPath by mutableStateOf<String?>(null)
        private set

    var currentSong by mutableStateOf<AudioFile?>(null)
        private set

    fun updateSong(song: AudioFile?) {
        val identityChanged = currentSong.playbackBarIdentity() != song.playbackBarIdentity()
        currentSong = song
        title = song?.title ?: noMusicText()
        artist = song?.artist.orEmpty()
        coverPath = song?.let { current ->
            resolveCover(current).ifBlank { current.coverKey }
        }?.takeIf { it.isNotBlank() }
        if (identityChanged) {
            // Song and timeline must switch in one Compose transaction. A requested/committed
            // track commonly starts at 0 just like the previous StateFlow value, so waiting for a
            // position emission can otherwise leave the previous song's fraction on screen.
            positionMs = 0L
            durationMs = song?.duration?.coerceAtLeast(0L) ?: 0L
            progress = 0f
        }
    }

    fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
    }

    fun updateProgress(positionMs: Long, durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = if (safeDuration > 0L) {
            positionMs.coerceIn(0L, safeDuration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        // Publish the exact numerator/denominator used for the fraction. MainComposeContent must
        // never independently read controller.value and combine values from different frames.
        this.positionMs = safePosition
        this.durationMs = safeDuration
        progress = if (safeDuration > 0L) {
            (safePosition.toDouble() / safeDuration.toDouble()).toFloat().coerceIn(0f, 1f)
        } else 0f
    }
}

private fun AudioFile?.playbackBarIdentity(): String? = this?.let { song ->
    "${song.path}|${song.cueTrackIndex}|${song.cueOffsetMs}|${song.cueEndMs}"
}
