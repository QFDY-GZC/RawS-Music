package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.flow.StateFlow

/** Adapts the full runtime controller to the narrow [PlayerControl] contract. */
internal class PlayerControlFacade(
    private val controller: PlayerController,
) : PlayerControl {
    override val playState: StateFlow<PlayState> get() = controller.playState
    override val currentSong: StateFlow<AudioFile?> get() = controller.currentSong
    override val requestedSongForUi: StateFlow<AudioFile?> get() = controller.requestedSongForUi
    override val queue: StateFlow<PlayQueue> get() = controller.queue
    override val position: StateFlow<Long> get() = controller.position
    override val duration: StateFlow<Long> get() = controller.duration
    override val repeatMode: StateFlow<RepeatMode> get() = controller.repeatMode
    override val isShuffle: StateFlow<Boolean> get() = controller.isShuffle
    override val playMode: StateFlow<PlayMode> get() = controller.playMode

    override fun play(song: AudioFile, queue: List<AudioFile>, index: Int) =
        controller.play(song, queue, index)

    override fun playQueue(songs: List<AudioFile>, startIndex: Int) =
        controller.playQueue(songs, startIndex)

    override fun playPause() = controller.playPause()
    override fun pause() = controller.pause()
    override fun resume() = controller.resume()
    override fun stop() = controller.stop()
    override fun seekTo(positionMs: Long, userInitiated: Boolean) =
        controller.seekTo(positionMs, userInitiated)

    override fun next(): AudioFile? = controller.next()
    override fun previous(): AudioFile? = controller.previous()
    override fun previousTrackFromArtworkGesture(): AudioFile? =
        controller.previousTrackFromArtworkGesture()

    override fun previewNextSong(): AudioFile? = controller.previewNextSong()
    override fun previewPreviousSong(): AudioFile? = controller.previewPreviousSong()

    override fun toggleRepeatMode() = controller.toggleRepeatMode()
    override fun setRepeatMode(mode: RepeatMode) = controller.setRepeatMode(mode)
    override fun toggleShuffle() = controller.toggleShuffle()
    override fun cyclePlayMode() = controller.cyclePlayMode()
    override fun setPlayMode(mode: PlayMode) = controller.setPlayMode(mode)

    override fun setVolume(volume: Float) = controller.setVolume(volume)
    override fun addToQueue(song: AudioFile) = controller.addToQueue(song)
    override fun playNext(song: AudioFile) = controller.playNext(song)
    override fun removeFromQueue(index: Int) = controller.removeFromQueue(index)
    override fun removeSongsFromQueue(songs: Collection<AudioFile>) =
        controller.removeSongsFromQueue(songs)

    override fun clearPriorityQueue() = controller.clearPriorityQueue()
    override fun getPriorityQueue(): List<AudioFile> = controller.getPriorityQueue()
}
