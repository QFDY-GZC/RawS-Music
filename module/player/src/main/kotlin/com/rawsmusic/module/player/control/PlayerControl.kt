package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow playback-control surface for UI and feature modules.
 *
 * The runtime implementation remains [com.rawsmusic.module.player.PlayerController], but callers
 * that only need transport, timeline, queue and mode control no longer need the full USB/DSP
 * controller API. This is the first boundary used to split the former PlayerController monolith.
 */
interface PlayerControl {
    val playState: StateFlow<PlayState>
    val currentSong: StateFlow<AudioFile?>
    val requestedSongForUi: StateFlow<AudioFile?>
    val queue: StateFlow<PlayQueue>
    val position: StateFlow<Long>
    val duration: StateFlow<Long>
    val repeatMode: StateFlow<RepeatMode>
    val isShuffle: StateFlow<Boolean>
    val playMode: StateFlow<PlayMode>

    fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0)
    fun playQueue(songs: List<AudioFile>, startIndex: Int = 0)
    fun playPause()
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long, userInitiated: Boolean = true)
    fun next(): AudioFile?
    fun previous(): AudioFile?
    fun previousTrackFromArtworkGesture(): AudioFile?
    fun previewNextSong(): AudioFile?
    fun previewPreviousSong(): AudioFile?

    fun toggleRepeatMode()
    fun setRepeatMode(mode: RepeatMode)
    fun toggleShuffle()
    fun cyclePlayMode()
    fun setPlayMode(mode: PlayMode)

    fun setVolume(volume: Float)
    fun addToQueue(song: AudioFile)
    fun playNext(song: AudioFile)
    fun removeFromQueue(index: Int)
    fun removeSongsFromQueue(songs: Collection<AudioFile>)
    fun clearPriorityQueue()
    fun getPriorityQueue(): List<AudioFile>
}
