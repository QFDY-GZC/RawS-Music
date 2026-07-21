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

    var coverPath by mutableStateOf<String?>(null)
        private set

    var currentSong: AudioFile? = null
        private set

    fun updateSong(song: AudioFile?) {
        currentSong = song
        title = song?.title ?: noMusicText()
        artist = song?.artist.orEmpty()
        coverPath = song?.let { current ->
            resolveCover(current).ifBlank { current.coverKey }
        }?.takeIf { it.isNotBlank() }
    }

    fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
    }

    fun updateProgress(positionMs: Long, durationMs: Long) {
        progress = if (durationMs > 0L) {
            positionMs.toFloat() / durationMs.toFloat()
        } else {
            0f
        }
    }
}
