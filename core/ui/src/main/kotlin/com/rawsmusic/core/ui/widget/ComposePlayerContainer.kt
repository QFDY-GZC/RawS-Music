package com.rawsmusic.core.ui.widget

import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.player.AlbumDetailPanel
import com.rawsmusic.core.ui.widget.player.FullCoverPage
import com.rawsmusic.core.ui.widget.player.ImmersiveAlbumInfoPage
import com.rawsmusic.core.ui.widget.player.ImmersivePlayerHorizontalStack
import com.rawsmusic.core.ui.widget.player.ImmersiveLyricPage
import com.rawsmusic.core.ui.widget.player.ImmersivePlayerMainPage
import com.rawsmusic.core.ui.widget.player.LyricPage
import com.rawsmusic.core.ui.widget.player.PlayerMainPage
import com.rawsmusic.core.ui.widget.player.QueuePanel
import com.rawsmusic.core.ui.widget.player.StandardPlayerBackdrop
import com.rawsmusic.core.ui.widget.player.rememberCoverAccentColor
import io.github.proify.lyricon.lyric.model.Song

private const val ENABLE_PLAYER_LIST_COVER_TRANSITION = false

/**
 * Compose 播放器辅助容器。
 *
 * 仅管理歌词、队列、全屏封面等 Compose 页面。
 * 播放器主体由 MainActivity 的 Compose 树渲染，场景切换由 PlayerSceneController 驱动。
 */
@Composable
fun ComposePlayerContainer(
    sceneState: PlayerSceneState,
    currentSong: AudioFile?,
    coverPath: String? = null,
    isPlaying: Boolean = false,
    currentPositionMs: Long = 0L,
    totalDurationMs: Long = 0L,
    previousIconRes: Int = 0,
    playIconRes: Int = 0,
    pauseIconRes: Int = 0,
    nextIconRes: Int = 0,
    playModeIconRes: Int = 0,
    moreIconRes: Int = 0,
    audioQualityIconRes: Int = 0,
    audioInfoText: String = "",
    onSeekStart: () -> Unit = {},
    onSeekStop: (Float) -> Unit = {},
    onPrevious: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPlayMode: () -> Unit = {},
    onPlayModeLongPress: () -> Unit = {},
    onMore: () -> Unit = {},
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onOpenLyric: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onPlayerCoverSwipeUpStart: () -> Unit = {},
    onPlayerCoverSwipeUpProgress: (Float) -> Unit = {},
    onPlayerCoverSwipeUpEnd: (Boolean, Float) -> Unit = { _, _ -> },
    onPlayerCoverSwipeDownStart: () -> Unit = {},
    onPlayerCoverSwipeDownProgress: (Float) -> Unit = {},
    onPlayerCoverSwipeDownEnd: (Boolean, Float) -> Unit = { _, _ -> },
    onLyricCoverSwipeDownStart: () -> Unit = {},
    onLyricCoverSwipeDownProgress: (Float) -> Unit = {},
    onLyricCoverSwipeDownEnd: (Boolean, Float) -> Unit = { _, _ -> },
    queueSongs: List<AudioFile> = emptyList(),
    queueCurrentIndex: Int = -1,
    onQueueSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onClearPriorityQueue: (() -> Unit)? = null,
    albumSongs: List<AudioFile> = emptyList(),
    albumCoverPath: String? = null,
    onAlbumSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    lyricSong: Song? = null,
    lyricPositionMs: Long = 0L,
    displayTranslation: Boolean = false,
    displayRoma: Boolean = false,
    onLyricSeek: (Long) -> Unit = {},
    onLyricTranslationToggle: () -> Unit = {},
    isImmersiveEnabled: Boolean = false,
    onClosePlayer: () -> Unit = { sceneState.backToMain() },
    onBackToPlayer: () -> Unit = { sceneState.backToPlayer() },
    onModalVisibleChange: (Boolean) -> Unit = {},
    controllerScene: PlayerSceneController.Scene? = null,
    controllerFromScene: PlayerSceneController.Scene? = null,
    controllerToScene: PlayerSceneController.Scene? = null,
    controllerProgress: Float = 0f,
    controllerIsTransitioning: Boolean = false,
    sourceCoverTarget: CoverTransitionTarget? = null,
    modifier: Modifier = Modifier
) {
    val resolvedCoverPath = currentSong.resolveArtworkKey(coverPath)
    val resolvedAlbumCoverPath = albumCoverPath?.takeIf { it.isNotBlank() } ?: resolvedCoverPath

    if (isImmersiveEnabled) {
        ImmersiveComposePlayerContainer(
            sceneState = sceneState,
            currentSong = currentSong,
            coverPath = resolvedCoverPath,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            previousIconRes = previousIconRes,
            playIconRes = playIconRes,
            pauseIconRes = pauseIconRes,
            nextIconRes = nextIconRes,
            playModeIconRes = playModeIconRes,
            lyricSong = lyricSong,
            lyricPositionMs = lyricPositionMs,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            albumSongs = albumSongs,
            albumCoverPath = resolvedAlbumCoverPath,
            onAlbumSongClick = onAlbumSongClick,
            queueSongs = queueSongs,
            queueCurrentIndex = queueCurrentIndex,
            onQueueSongClick = onQueueSongClick,
            onClearPriorityQueue = onClearPriorityQueue,
            onClosePlayer = onClosePlayer,
            onModalVisibleChange = onModalVisibleChange,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPlayMode = onPlayMode,
            audioInfoText = audioInfoText,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            onLyricSeek = onLyricSeek,
            onLyricTranslationToggle = onLyricTranslationToggle,
            controllerScene = controllerScene,
            controllerFromScene = controllerFromScene,
            controllerToScene = controllerToScene,
            controllerProgress = controllerProgress,
            controllerIsTransitioning = controllerIsTransitioning,
            modifier = modifier
        )
    } else {
        StandardComposePlayerContainer(
            sceneState = sceneState,
            currentSong = currentSong,
            coverPath = resolvedCoverPath,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            previousIconRes = previousIconRes,
            playIconRes = playIconRes,
            pauseIconRes = pauseIconRes,
            nextIconRes = nextIconRes,
            playModeIconRes = playModeIconRes,
            moreIconRes = moreIconRes,
            audioQualityIconRes = audioQualityIconRes,
            audioInfoText = audioInfoText,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPlayMode = onPlayMode,
            onPlayModeLongPress = onPlayModeLongPress,
            onMore = onMore,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            onOpenLyric = onOpenLyric,
            onOpenQueue = onOpenQueue,
            onPlayerCoverSwipeUpStart = onPlayerCoverSwipeUpStart,
            onPlayerCoverSwipeUpProgress = onPlayerCoverSwipeUpProgress,
            onPlayerCoverSwipeUpEnd = onPlayerCoverSwipeUpEnd,
            onPlayerCoverSwipeDownStart = onPlayerCoverSwipeDownStart,
            onPlayerCoverSwipeDownProgress = onPlayerCoverSwipeDownProgress,
            onPlayerCoverSwipeDownEnd = onPlayerCoverSwipeDownEnd,
            onLyricCoverSwipeDownStart = onLyricCoverSwipeDownStart,
            onLyricCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
            onLyricCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
            queueSongs = queueSongs,
            queueCurrentIndex = queueCurrentIndex,
            onQueueSongClick = onQueueSongClick,
            onClearPriorityQueue = onClearPriorityQueue,
            albumSongs = albumSongs,
            albumCoverPath = resolvedAlbumCoverPath,
            onAlbumSongClick = onAlbumSongClick,
            lyricSong = lyricSong,
            lyricPositionMs = lyricPositionMs,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            onLyricSeek = onLyricSeek,
            onLyricTranslationToggle = onLyricTranslationToggle,
            onClosePlayer = onClosePlayer,
            onBackToPlayer = onBackToPlayer,
            controllerScene = controllerScene,
            controllerFromScene = controllerFromScene,
            controllerToScene = controllerToScene,
            controllerProgress = controllerProgress,
            controllerIsTransitioning = controllerIsTransitioning,
            sourceCoverTarget = sourceCoverTarget,
            modifier = modifier
        )
    }
}

@Composable
private fun ImmersiveComposePlayerContainer(
    sceneState: PlayerSceneState,
    currentSong: AudioFile?,
    coverPath: String?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    previousIconRes: Int,
    playIconRes: Int,
    pauseIconRes: Int,
    nextIconRes: Int,
    playModeIconRes: Int,
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    albumSongs: List<AudioFile>,
    albumCoverPath: String?,
    onAlbumSongClick: (AudioFile, Int) -> Unit,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onClosePlayer: () -> Unit,
    onModalVisibleChange: (Boolean) -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    audioInfoText: String = "",
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    controllerScene: PlayerSceneController.Scene?,
    controllerFromScene: PlayerSceneController.Scene?,
    controllerToScene: PlayerSceneController.Scene?,
    controllerProgress: Float,
    controllerIsTransitioning: Boolean,
    modifier: Modifier
) {
    val controllerSceneForVisibility = if (controllerIsTransitioning) {
        val from = controllerFromScene ?: PlayerSceneController.Scene.MAIN
        val to = controllerToScene ?: from
        if (to != PlayerSceneController.Scene.MAIN) to else from
    } else {
        controllerScene
    }
    val scene = when {
        sceneState.currentScene != PlayerScene.MAIN -> sceneState.currentScene
        controllerSceneForVisibility != null -> controllerSceneForVisibility.toPlayerScene()
        else -> PlayerScene.MAIN
    }
    if (scene == PlayerScene.MAIN) return

    Box(modifier = modifier.fillMaxSize()) {
        if (scene in setOf(PlayerScene.PLAYER, PlayerScene.LYRIC, PlayerScene.ALBUM_DETAIL)) {
            val from = controllerFromScene ?: scene.toControllerScene()
            val to = controllerToScene ?: scene.toControllerScene()
            val progress = controllerProgress.coerceIn(0f, 1f)
            val drawerProgress = when {
                controllerIsTransitioning && from == PlayerSceneController.Scene.MAIN && to != PlayerSceneController.Scene.MAIN -> progress
                controllerIsTransitioning && from != PlayerSceneController.Scene.MAIN && to == PlayerSceneController.Scene.MAIN -> 1f - progress
                else -> 1f
            }.coerceIn(0f, 1f)
            AnimatedVisibility(
                visible = true,
                enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.ExitTransition.None,
                modifier = Modifier.fillMaxSize()
            ) {
                ImmersivePlayerHorizontalStack(
                    currentScene = controllerScene ?: scene.toControllerScene(),
                    fromScene = controllerFromScene ?: scene.toControllerScene(),
                    toScene = controllerToScene ?: scene.toControllerScene(),
                    progress = controllerProgress,
                    isTransitioning = controllerIsTransitioning,
                    currentSong = currentSong,
                    coverPath = coverPath,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
                    previousIconRes = previousIconRes,
                    playIconRes = playIconRes,
                    pauseIconRes = pauseIconRes,
                    nextIconRes = nextIconRes,
                    playModeIconRes = playModeIconRes,
                    lyricSong = lyricSong,
                    lyricPositionMs = lyricPositionMs,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    audioInfoText = audioInfoText,
                    albumSongs = albumSongs,
                    albumCoverPath = albumCoverPath ?: coverPath,
                    onBack = onClosePlayer,
                    onSeekStart = onSeekStart,
                    onSeekStop = onSeekStop,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPlayMode = onPlayMode,
                    onAudioQuality = onAudioQuality,
                    onAudioQualityLongPress = onAudioQualityLongPress,
                    onMorePanelVisibleChange = onModalVisibleChange,
                    onLyricSeek = onLyricSeek,
                    onLyricTranslationToggle = onLyricTranslationToggle,
                    onAlbumSongClick = onAlbumSongClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = size.height * (1f - drawerProgress)
                            alpha = 1f
                        }
                )
            }
            return@Box
        }

        // 沉浸模式的队列/全屏封面仍使用共用辅助面板，不进入普通播放器主体。
        AnimatedVisibility(
            visible = scene == PlayerScene.QUEUE,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            QueuePanel(
                songs = queueSongs,
                currentIndex = queueCurrentIndex,
                onSongClick = onQueueSongClick,
                onClearPriorityQueue = onClearPriorityQueue,
                onBack = { sceneState.backToPlayer() }
            )
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.FULL_COVER,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            FullCoverPage(
                coverPath = currentSong?.albumArtPath,
                title = currentSong?.title ?: "",
                onBack = { sceneState.backToPlayer() }
            )
        }
    }
}

@Composable
private fun StandardComposePlayerContainer(
    sceneState: PlayerSceneState,
    currentSong: AudioFile?,
    coverPath: String?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    previousIconRes: Int,
    playIconRes: Int,
    pauseIconRes: Int,
    nextIconRes: Int,
    playModeIconRes: Int,
    moreIconRes: Int,
    audioQualityIconRes: Int,
    audioInfoText: String,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onPlayModeLongPress: () -> Unit,
    onMore: () -> Unit,
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit,
    onOpenLyric: () -> Unit,
    onOpenQueue: () -> Unit,
    onPlayerCoverSwipeUpStart: () -> Unit,
    onPlayerCoverSwipeUpProgress: (Float) -> Unit,
    onPlayerCoverSwipeUpEnd: (Boolean, Float) -> Unit,
    onPlayerCoverSwipeDownStart: () -> Unit,
    onPlayerCoverSwipeDownProgress: (Float) -> Unit,
    onPlayerCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    onLyricCoverSwipeDownStart: () -> Unit,
    onLyricCoverSwipeDownProgress: (Float) -> Unit,
    onLyricCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    albumSongs: List<AudioFile>,
    albumCoverPath: String?,
    onAlbumSongClick: (AudioFile, Int) -> Unit,
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onClosePlayer: () -> Unit,
    onBackToPlayer: () -> Unit,
    controllerScene: PlayerSceneController.Scene?,
    controllerFromScene: PlayerSceneController.Scene?,
    controllerToScene: PlayerSceneController.Scene?,
    controllerProgress: Float,
    controllerIsTransitioning: Boolean,
    sourceCoverTarget: CoverTransitionTarget?,
    modifier: Modifier
) {
    val controllerSceneForVisibility = if (controllerIsTransitioning) {
        val from = controllerFromScene ?: PlayerSceneController.Scene.MAIN
        val to = controllerToScene ?: from
        if (to != PlayerSceneController.Scene.MAIN) to else from
    } else {
        controllerScene
    }
    val scene = when {
        sceneState.currentScene != PlayerScene.MAIN -> sceneState.currentScene
        controllerSceneForVisibility != null -> controllerSceneForVisibility.toPlayerScene()
        else -> PlayerScene.MAIN
    }
    if (scene == PlayerScene.MAIN) return

    Box(modifier = modifier.fillMaxSize()) {
        if (scene == PlayerScene.PLAYER || scene == PlayerScene.LYRIC) {
            val from = controllerFromScene ?: scene.toControllerScene()
            val to = controllerToScene ?: scene.toControllerScene()
            val progress = controllerProgress.coerceIn(0f, 1f)
            val drawerProgress = when {
                controllerIsTransitioning && from == PlayerSceneController.Scene.MAIN && to != PlayerSceneController.Scene.MAIN -> progress
                controllerIsTransitioning && from != PlayerSceneController.Scene.MAIN && to == PlayerSceneController.Scene.MAIN -> 1f - progress
                else -> 1f
            }.coerceIn(0f, 1f)
            val mainPlayerTransition =
                (from == PlayerSceneController.Scene.MAIN && to == PlayerSceneController.Scene.PLAYER) ||
                    (from == PlayerSceneController.Scene.PLAYER && to == PlayerSceneController.Scene.MAIN)
            StandardPlayerLyricStack(
                currentScene = controllerScene ?: scene.toControllerScene(),
                fromScene = from,
                toScene = to,
                progress = progress,
                isTransitioning = controllerIsTransitioning,
                currentSong = currentSong,
                coverPath = coverPath,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                previousIconRes = previousIconRes,
                playIconRes = playIconRes,
                pauseIconRes = pauseIconRes,
                nextIconRes = nextIconRes,
                playModeIconRes = playModeIconRes,
                moreIconRes = moreIconRes,
                audioQualityIconRes = audioQualityIconRes,
                audioInfoText = audioInfoText,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onPlayModeLongPress = onPlayModeLongPress,
                onMore = onMore,
                onAudioQuality = onAudioQuality,
                onAudioQualityLongPress = onAudioQualityLongPress,
                onOpenLyric = onOpenLyric,
                onOpenQueue = onOpenQueue,
                onPlayerCoverSwipeUpStart = onPlayerCoverSwipeUpStart,
                onPlayerCoverSwipeUpProgress = onPlayerCoverSwipeUpProgress,
                onPlayerCoverSwipeUpEnd = onPlayerCoverSwipeUpEnd,
                onPlayerCoverSwipeDownStart = onPlayerCoverSwipeDownStart,
                onPlayerCoverSwipeDownProgress = onPlayerCoverSwipeDownProgress,
                onPlayerCoverSwipeDownEnd = onPlayerCoverSwipeDownEnd,
                onLyricCoverSwipeDownStart = onLyricCoverSwipeDownStart,
                onLyricCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
                onLyricCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
                lyricSong = lyricSong,
                lyricPositionMs = lyricPositionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                onLyricSeek = onLyricSeek,
                onLyricTranslationToggle = onLyricTranslationToggle,
                onClosePlayer = onClosePlayer,
                onBackToPlayer = onBackToPlayer,
                sourceCoverTarget = sourceCoverTarget,
                drawerProgress = drawerProgress,
                modifier = Modifier
                    .fillMaxSize()
            )
            return@Box
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.PLAYER,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerMainPage(
                currentSong = currentSong,
                coverPath = coverPath,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                previousIconRes = previousIconRes,
                playIconRes = playIconRes,
                pauseIconRes = pauseIconRes,
                nextIconRes = nextIconRes,
                playModeIconRes = playModeIconRes,
                moreIconRes = moreIconRes,
                audioQualityIconRes = audioQualityIconRes,
                audioInfoText = audioInfoText,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onPlayModeLongPress = onPlayModeLongPress,
                onMore = onMore,
                onAudioQuality = onAudioQuality,
                onAudioQualityLongPress = onAudioQualityLongPress,
                onOpenLyric = onOpenLyric,
                onOpenQueue = onOpenQueue,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.LYRIC,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            LyricPage(
                currentSong = currentSong,
                coverPath = coverPath,
                song = lyricSong,
                positionMs = lyricPositionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                moreIconRes = moreIconRes,
                onSeek = onLyricSeek,
                onTranslationToggle = onLyricTranslationToggle,
                onMore = onMore,
                onBack = onBackToPlayer
            )
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.QUEUE,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            QueuePanel(
                songs = queueSongs,
                currentIndex = queueCurrentIndex,
                onSongClick = onQueueSongClick,
                onClearPriorityQueue = onClearPriorityQueue,
                onBack = { sceneState.backToPlayer() }
            )
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.ALBUM_DETAIL,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            AlbumDetailPanel(
                currentSong = currentSong,
                songs = albumSongs,
                coverPath = albumCoverPath,
                onSongClick = onAlbumSongClick,
                onBack = { sceneState.backToPlayer() }
            )
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.FULL_COVER,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            FullCoverPage(
                coverPath = currentSong?.albumArtPath,
                title = currentSong?.title ?: "",
                onBack = { sceneState.backToPlayer() }
            )
        }
    }
}

private fun PlayerScene.toControllerScene(): PlayerSceneController.Scene = when (this) {
    PlayerScene.MAIN -> PlayerSceneController.Scene.MAIN
    PlayerScene.PLAYER -> PlayerSceneController.Scene.PLAYER
    PlayerScene.LYRIC -> PlayerSceneController.Scene.LYRIC
    PlayerScene.QUEUE -> PlayerSceneController.Scene.QUEUE
    PlayerScene.ALBUM_DETAIL -> PlayerSceneController.Scene.ALBUM_DETAIL
    PlayerScene.FULL_COVER -> PlayerSceneController.Scene.PLAYER
}

@Composable
private fun StandardPlayerLyricStack(
    currentScene: PlayerSceneController.Scene,
    fromScene: PlayerSceneController.Scene,
    toScene: PlayerSceneController.Scene,
    progress: Float,
    isTransitioning: Boolean,
    currentSong: AudioFile?,
    coverPath: String?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    previousIconRes: Int,
    playIconRes: Int,
    pauseIconRes: Int,
    nextIconRes: Int,
    playModeIconRes: Int,
    moreIconRes: Int,
    audioQualityIconRes: Int,
    audioInfoText: String,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onPlayModeLongPress: () -> Unit,
    onMore: () -> Unit,
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit,
    onOpenLyric: () -> Unit,
    onOpenQueue: () -> Unit,
    onPlayerCoverSwipeUpStart: () -> Unit,
    onPlayerCoverSwipeUpProgress: (Float) -> Unit,
    onPlayerCoverSwipeUpEnd: (Boolean, Float) -> Unit,
    onPlayerCoverSwipeDownStart: () -> Unit,
    onPlayerCoverSwipeDownProgress: (Float) -> Unit,
    onPlayerCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    onLyricCoverSwipeDownStart: () -> Unit,
    onLyricCoverSwipeDownProgress: (Float) -> Unit,
    onLyricCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onClosePlayer: () -> Unit,
    onBackToPlayer: () -> Unit,
    sourceCoverTarget: CoverTransitionTarget?,
    drawerProgress: Float,
    modifier: Modifier = Modifier
) {
    var containerRootBounds by remember { mutableStateOf<RectF?>(null) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                containerRootBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
        val playerCoverRect = remember(widthPx, statusTopPx, density.density) {
            val side = 14.dp.value * density.density
            val top = statusTopPx + 20.dp.value * density.density
            val coverWidth = widthPx - side * 2f
            val coverHeight = coverWidth / 1.02f
            RectF(side, top, side + coverWidth, top + coverHeight)
        }
        val lyricCoverRect = remember(statusTopPx, density.density) {
            val left = 18.dp.value * density.density
            val top = statusTopPx + 22.dp.value * density.density
            val size = 104.dp.value * density.density
            RectF(left, top, left + size, top + size)
        }
        val mainPlayerTransition = isTransitioning &&
            ((fromScene == PlayerSceneController.Scene.MAIN && toScene == PlayerSceneController.Scene.PLAYER) ||
                (fromScene == PlayerSceneController.Scene.PLAYER && toScene == PlayerSceneController.Scene.MAIN))
        val playerLyricTransition = isTransitioning &&
            ((fromScene == PlayerSceneController.Scene.PLAYER && toScene == PlayerSceneController.Scene.LYRIC) ||
                (fromScene == PlayerSceneController.Scene.LYRIC && toScene == PlayerSceneController.Scene.PLAYER))
        val lyricProgress = when {
            playerLyricTransition && fromScene == PlayerSceneController.Scene.PLAYER -> progress.coerceIn(0f, 1f)
            playerLyricTransition && fromScene == PlayerSceneController.Scene.LYRIC -> 1f - progress.coerceIn(0f, 1f)
            currentScene == PlayerSceneController.Scene.LYRIC -> 1f
            else -> 0f
        }
        val playerVisible = currentScene == PlayerSceneController.Scene.PLAYER || playerLyricTransition || mainPlayerTransition
        val lyricVisible = currentScene == PlayerSceneController.Scene.LYRIC || playerLyricTransition
        val source = sourceCoverTarget?.bounds
            ?.toLocalRect(containerRootBounds)
            ?.takeIf { it.isUsableSource(widthPx, heightPx) }
        val mainSharedTransition = ENABLE_PLAYER_LIST_COVER_TRANSITION &&
            mainPlayerTransition &&
            !coverPath.isNullOrBlank() &&
            source != null
        StandardPlayerBackdrop(
            coverPath = coverPath,
            accent = rememberCoverAccentColor(coverPath),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = if (mainPlayerTransition) {
                        heightPx * (1f - drawerProgress.coerceIn(0f, 1f))
                    } else {
                        0f
                    }
                }
        )

        if (playerVisible) {
            PlayerMainPage(
                currentSong = currentSong,
                coverPath = coverPath,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                previousIconRes = previousIconRes,
                playIconRes = playIconRes,
                pauseIconRes = pauseIconRes,
                nextIconRes = nextIconRes,
                playModeIconRes = playModeIconRes,
                moreIconRes = moreIconRes,
                audioQualityIconRes = audioQualityIconRes,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onPlayModeLongPress = onPlayModeLongPress,
                onMore = onMore,
                onAudioQuality = onAudioQuality,
                onOpenLyric = onOpenLyric,
                onOpenQueue = onOpenQueue,
                onClosePlayer = onClosePlayer,
                onCoverSwipeUpStart = onPlayerCoverSwipeUpStart,
                onCoverSwipeUpProgress = onPlayerCoverSwipeUpProgress,
                onCoverSwipeUpEnd = onPlayerCoverSwipeUpEnd,
                onCoverSwipeDownStart = onPlayerCoverSwipeDownStart,
                onCoverSwipeDownProgress = onPlayerCoverSwipeDownProgress,
                onCoverSwipeDownEnd = onPlayerCoverSwipeDownEnd,
                showAlbumArt = !mainSharedTransition && !playerLyricTransition,
                renderBackdrop = false,
                audioInfoText = audioInfoText,
                onAudioQualityLongPress = onAudioQualityLongPress,
                contentAlpha = if (playerLyricTransition) (1f - lyricProgress).coerceIn(0f, 1f) else 1f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f
                        translationY = if (mainPlayerTransition) {
                            heightPx * (1f - drawerProgress.coerceIn(0f, 1f))
                        } else {
                            0f
                        }
                    }
            )
        }
        if (lyricVisible) {
            LyricPage(
                currentSong = currentSong,
                coverPath = coverPath,
                song = lyricSong,
                positionMs = lyricPositionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                moreIconRes = moreIconRes,
                onSeek = onLyricSeek,
                onTranslationToggle = onLyricTranslationToggle,
                onMore = onMore,
                onBack = onBackToPlayer,
                onCoverSwipeDownStart = onLyricCoverSwipeDownStart,
                onCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
                onCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
                showHeaderCover = !playerLyricTransition,
                renderBackdrop = false,
                contentAlpha = if (playerLyricTransition) lyricProgress.coerceIn(0f, 1f) else 1f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f
                        translationY = heightPx * 0.06f * (1f - lyricProgress.coerceIn(0f, 1f))
                    }
            )
        }
        if (playerLyricTransition && !coverPath.isNullOrBlank()) {
            val f = if (fromScene == PlayerSceneController.Scene.PLAYER) progress.coerceIn(0f, 1f) else 1f - progress.coerceIn(0f, 1f)
            val rect = lerpRect(playerCoverRect, lyricCoverRect, f)
            Box(
                modifier = Modifier
                    .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                    .size(
                        width = with(density) { rect.width().toDp() },
                        height = with(density) { rect.height().toDp() }
                    )
                    .graphicsLayer {
                        alpha = 1f
                        clip = true
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(lerpFloat(28, 24, f).dp)
                    }
            ) {
                BitmapImage(
                    key = coverPath,
                    contentDescription = currentSong?.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 1080,
                    targetHeight = 1080
                )
            }
        }
        if (mainSharedTransition) {
            val f = if (fromScene == PlayerSceneController.Scene.MAIN) progress.coerceIn(0f, 1f) else 1f - progress.coerceIn(0f, 1f)
            val rect = lerpRect(source, playerCoverRect, f)
            val sourceRadius = sourceCoverTarget.radiusDp
            val playerRadius = 28f
            Box(
                modifier = Modifier
                    .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                    .size(
                        width = with(density) { rect.width().toDp() },
                        height = with(density) { rect.height().toDp() }
                    )
                    .graphicsLayer {
                        scaleX = 1f
                        scaleY = 1f
                        this.alpha = 1f
                        clip = true
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                            lerpFloat(sourceRadius, playerRadius, f).dp
                        )
                    }
            ) {
                BitmapImage(
                    key = coverPath,
                    contentDescription = currentSong?.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 1080,
                    targetHeight = 1080
                )
            }
        }
    }
}

private fun lerpFloat(start: Int, stop: Int, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

private fun inferSharedCoverRadiusDp(rect: RectF, density: Float): Float {
    val sizeDp = (minOf(rect.width(), rect.height()) / density.coerceAtLeast(0.1f))
    return when {
        sizeDp <= 40f -> 8f
        sizeDp <= 96f -> 18f
        sizeDp <= 132f -> 24f
        else -> 24f
    }
}

private fun lerpRect(start: RectF, stop: RectF, fraction: Float): RectF {
    val f = fraction.coerceIn(0f, 1f)
    return RectF(
        start.left + (stop.left - start.left) * f,
        start.top + (stop.top - start.top) * f,
        start.right + (stop.right - start.right) * f,
        start.bottom + (stop.bottom - start.bottom) * f
    )
}

private fun RectF.toLocalRect(containerRootBounds: RectF?): RectF {
    val root = containerRootBounds ?: return RectF(this)
    return RectF(
        left - root.left,
        top - root.top,
        right - root.left,
        bottom - root.top
    )
}

private fun RectF.isUsableSource(widthPx: Float, heightPx: Float): Boolean {
    if (width() < 8f || height() < 8f) return false
    if (right < -widthPx || left > widthPx * 2f) return false
    if (bottom < -heightPx || top > heightPx * 2f) return false
    return true
}

private fun PlayerSceneController.Scene.toPlayerScene(): PlayerScene = when (this) {
    PlayerSceneController.Scene.MAIN -> PlayerScene.MAIN
    PlayerSceneController.Scene.PLAYER -> PlayerScene.PLAYER
    PlayerSceneController.Scene.LYRIC -> PlayerScene.LYRIC
    PlayerSceneController.Scene.QUEUE -> PlayerScene.QUEUE
    PlayerSceneController.Scene.ALBUM_DETAIL -> PlayerScene.ALBUM_DETAIL
}

private fun AudioFile?.resolveArtworkKey(fallback: String?): String? {
    val song = this
    return song?.albumArtPath?.takeIf { it.isNotBlank() }
        ?: fallback?.takeIf { it.isNotBlank() }
        ?: song?.path?.takeIf { it.isLocalArtworkSource() }
}

private fun String.isLocalArtworkSource(): Boolean {
    return isNotBlank() &&
        !startsWith("http://", ignoreCase = true) &&
        !startsWith("https://", ignoreCase = true)
}
