package com.rawsmusic.helper

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.player.lyrics.LyricGetterBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * 播放状态分发协调器。
 *
 * 把播放器事件分发给 UI 状态、歌词、服务桥、胶囊、场景旋转，
 * 替代 MainActivity 里散落的 observePlaybackState() / observeCurrentSong() / observePosition()。
 */
class PlaybackCoordinator(
    private val scope: CoroutineScope,
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
    private var lastAuxiliaryUiTime = 0L
    private var lastMiniProgressUiTime = 0L
    private var lastMiniProgressPositionMs = Long.MIN_VALUE
    private var songPresentationGeneration = 0L
    private var mediaIdentityJob: Job? = null
    private var auxiliaryUiJob: Job? = null
    private var lyricsJob: Job? = null

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
        val generation = ++songPresentationGeneration
        // Publish the in-app identity immediately. MediaSession and lyric loading start in
        // independent cancellable lanes; no fixed-delay gate is allowed to bunch work near
        // the end of the artwork animation.
        miniPlayer.updateSong(song)
        // Advance lyric ownership immediately without rebuilding its Compose tree. This prevents
        // position ticks from publishing the retiring song's lines while its replacement loads.
        lyrics.markSongPending(song)

        mediaIdentityJob?.cancel()
        mediaIdentityJob = scope.launch(Dispatchers.IO) {
            if (generation == songPresentationGeneration) {
                playerServiceBridgeHelper.pushSongUpdate(song)
            }
        }

        auxiliaryUiJob?.cancel()
        auxiliaryUiJob = scope.launch {
            yield()
            if (generation == songPresentationGeneration) {
                onCurrentSongChangedExtra(song)
            }
        }

        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            if (generation == songPresentationGeneration) {
                lyrics.loadLyricsForSong(song)
            }
        }
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
        // Position already arrives on the player's 50 ms clock. Do not add another
        // lyric throttle: it delayed line changes (especially background/Lyricon) by
        // up to 500 ms and made correctly timestamped lyrics visibly trail the audio.
        lyrics.onPositionChanged(positionMs, updateUiPosition = playerUiVisible)

        if (now - lastSyncPositionTime >= 1000 && PlayerService.isRunning) {
            lastSyncPositionTime = now
            playerServiceBridgeHelper.syncPosition(positionMs)
        }

        // Non-timeline chrome (audio-chain capsule, diagnostics, etc.) must not inherit the
        // playback clock. It has no visual need for 20 Hz updates and otherwise adds unrelated
        // Main-thread work to every scene/player transition while audio is running.
        if (now - lastAuxiliaryUiTime >= AUXILIARY_UI_INTERVAL_MS) {
            lastAuxiliaryUiTime = now
            onPositionChangedExtra(positionMs, durationMs)
        }
    }

    private companion object {
        const val MINI_PROGRESS_UI_INTERVAL_MS = 1000L
        const val AUXILIARY_UI_INTERVAL_MS = 1000L
    }
}
