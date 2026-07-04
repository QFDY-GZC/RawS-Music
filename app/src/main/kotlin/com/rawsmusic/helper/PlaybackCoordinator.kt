package com.rawsmusic.helper

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.player.lyrics.LyricGetterBridge

/**
 * 播放状态分发协调器。
 *
 * 把播放器事件分发给 UI 状态、歌词、服务桥、胶囊、场景旋转，
 * 替代 MainActivity 里散落的 observePlaybackState() / observeCurrentSong() / observePosition()。
 */
class PlaybackCoordinator(
    private val sceneController: () -> PlayerSceneController?,
    private val miniPlayer: MiniPlayerCoordinator,
    private val lyrics: LyricsCoordinator,
    private val playerServiceBridgeHelper: PlayerServiceBridgeHelper,
    private val onCurrentSongChangedExtra: (AudioFile) -> Unit = {},
    private val onPositionChangedExtra: (Long, Long) -> Unit = { _, _ -> },
    private val context: android.content.Context
) {
    private var lastSyncPositionTime = 0L

    fun onPlaybackStateChanged(state: PlayState) {
        val isPlaying = state == PlayState.PLAYING

        sceneController()?.syncRotationState(isPlaying)
        sceneController()?.isCurrentlyPlaying = isPlaying

        miniPlayer.updatePlaybackState(isPlaying)

        LyriconProviderManager.setPlaybackState(isPlaying)
        LyricGetterBridge.updatePlaybackState(context, isPlaying)

        miniPlayer.currentSong?.let {
            playerServiceBridgeHelper.pushSongUpdate(it)
        }
    }

    fun onCurrentSongChanged(song: AudioFile) {
        miniPlayer.updateSong(song)
        lyrics.loadLyricsForSong(song)
        playerServiceBridgeHelper.pushSongUpdate(song)
        onCurrentSongChangedExtra(song)
    }

    fun onPositionChanged(positionMs: Long, durationMs: Long) {
        miniPlayer.updateProgress(positionMs, durationMs)
        lyrics.onPositionChanged(positionMs)

        val now = System.currentTimeMillis()
        if (now - lastSyncPositionTime >= 1000 && PlayerService.isRunning) {
            lastSyncPositionTime = now
            playerServiceBridgeHelper.syncPosition(positionMs)
        }

        onPositionChangedExtra(positionMs, durationMs)
    }
}
