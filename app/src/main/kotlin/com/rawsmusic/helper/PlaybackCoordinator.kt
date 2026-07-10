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
    private val isPlayerUiVisible: () -> Boolean = { true },
    private val context: android.content.Context
) {
    private var lastSyncPositionTime = 0L
    private var lastMiniProgressUiTime = 0L
    private var lastMiniProgressPositionMs = Long.MIN_VALUE
    private var lastLyricUiTime = 0L
    private var lastLyricPositionMs = Long.MIN_VALUE

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
        val now = System.currentTimeMillis()
        val positionJumped = kotlin.math.abs(positionMs - lastMiniProgressPositionMs) > 1500L
        if (now - lastMiniProgressUiTime >= MINI_PROGRESS_UI_INTERVAL_MS || positionJumped) {
            lastMiniProgressUiTime = now
            lastMiniProgressPositionMs = positionMs
            miniPlayer.updateProgress(positionMs, durationMs)
        }

        val playerUiVisible = isPlayerUiVisible()
        val lyricInterval = if (playerUiVisible) LYRIC_PLAYER_UI_INTERVAL_MS else LYRIC_BACKGROUND_INTERVAL_MS
        val lyricJumped = kotlin.math.abs(positionMs - lastLyricPositionMs) > 1500L
        if (now - lastLyricUiTime >= lyricInterval || lyricJumped) {
            lastLyricUiTime = now
            lastLyricPositionMs = positionMs
            lyrics.onPositionChanged(positionMs, updateUiPosition = playerUiVisible)
        }

        if (now - lastSyncPositionTime >= 1000 && PlayerService.isRunning) {
            lastSyncPositionTime = now
            playerServiceBridgeHelper.syncPosition(positionMs)
        }

        onPositionChangedExtra(positionMs, durationMs)
    }

    private companion object {
        const val MINI_PROGRESS_UI_INTERVAL_MS = 1000L
        const val LYRIC_PLAYER_UI_INTERVAL_MS = 100L
        const val LYRIC_BACKGROUND_INTERVAL_MS = 500L
    }
}
