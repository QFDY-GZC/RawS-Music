package com.rawsmusic.core.ui.widget

import android.graphics.RectF
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.rememberPlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.player.AlbumDetailPanel
import com.rawsmusic.core.ui.widget.player.FullCoverPage
import com.rawsmusic.core.ui.widget.player.ImmersiveAlbumInfoPage
import com.rawsmusic.core.ui.widget.player.ImmersivePlayerHorizontalStack
import com.rawsmusic.core.ui.widget.player.ImmersiveLyricPage
import com.rawsmusic.core.ui.widget.player.ImmersivePlayerMainPage
import com.rawsmusic.core.ui.widget.player.LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.LyricPage
import com.rawsmusic.core.ui.widget.player.OriginalArtworkViewerDialog
import com.rawsmusic.core.ui.widget.player.PlayerMainPage
import com.rawsmusic.core.ui.widget.player.STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.StandardPlayerBackdrop
import com.rawsmusic.core.ui.widget.player.rememberCoverAccentColor
import com.rawsmusic.module.data.prefs.LyricLayoutPreferences
import com.rawsmusic.module.data.prefs.LyricTopLayoutStyle
import io.github.proify.lyricon.lyric.model.Song

// The ordinary player should keep its own artwork visible while returning to the main page.
// The list-to-player shared handoff made the default artwork disappear during the gesture.
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
    previousGestureArtworkKey: String? = null,
    nextGestureArtworkKey: String? = null,
    isPlaying: Boolean = false,
    currentPositionMs: Long = 0L,
    totalDurationMs: Long = 0L,
    audioVisualizerEnabled: Boolean = false,
    audioSpectrum: FloatArray = FloatArray(0),
    onAudioVisualizerDismiss: () -> Unit = {},
    onAudioVisualizerEnabledChange: (Boolean) -> Unit = {},
    onAudioVisualizerRuntimeActiveChange: (Boolean) -> Unit = {},
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
    onPrevious: () -> AudioFile? = { null },
    onPlayPause: () -> Unit = {},
    onNext: () -> AudioFile? = { null },
    onArtworkGesturePrevious: () -> AudioFile? = onPrevious,
    onArtworkGestureNext: () -> AudioFile? = onNext,
    onPlayMode: () -> Unit = {},
    onPlayModeLongPress: () -> Unit = {},
    onMore: () -> Unit = {},
    onOpenMetadata: () -> Unit = {},
    onOpenAudioEffects: () -> Unit = {},
    onLyricModifyAlbumArt: () -> Unit = {},
    sleepTimerSelection: Int = 0,
    onSleepTimerSelectionChange: (Int) -> Unit = {},
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onOpenLyric: () -> Unit = {},
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
    onLyricRomaToggle: () -> Unit = {},
    onSearchLyrico: () -> Unit = {},
    onOpenInLyrico: () -> Unit = {},
    isImmersiveEnabled: Boolean = false,
    overlaySuspended: Boolean = false,
    onClosePlayer: () -> Unit = { sceneState.backToMain() },
    onBackToPlayer: () -> Unit = { sceneState.backToPlayer() },
    onModalVisibleChange: (Boolean) -> Unit = {},
    onModalDismissActionChange: ((() -> Unit)?) -> Unit = {},
    controllerScene: PlayerSceneController.Scene? = null,
    controllerFromScene: PlayerSceneController.Scene? = null,
    controllerToScene: PlayerSceneController.Scene? = null,
    controllerProgress: Float = 0f,
    controllerIsTransitioning: Boolean = false,
    playerLyricsTransitionCoordinator: PlayerLyricsTransitionCoordinator? = null,
    sourceCoverTarget: CoverTransitionTarget? = null,
    modifier: Modifier = Modifier
) {
    val latestOnModalVisibleChange by rememberUpdatedState(onModalVisibleChange)
    val latestOnModalDismissActionChange by rememberUpdatedState(onModalDismissActionChange)
    DisposableEffect(Unit) {
        onDispose {
            latestOnModalVisibleChange(false)
            latestOnModalDismissActionChange(null)
        }
    }

    val resolvedCoverPath = currentSong.resolvePlaybackArtworkKey(coverPath)
    val resolvedAlbumCoverPath = albumCoverPath?.takeIf { it.isNotBlank() } ?: resolvedCoverPath
    val artworkTransitionState = rememberPlaybackArtworkTransitionState(
        currentKey = resolvedCoverPath,
        queueCurrentIndex = queueCurrentIndex,
        queueSize = queueSongs.size
    )
    LaunchedEffect(previousGestureArtworkKey, nextGestureArtworkKey) {
        artworkTransitionState.setGestureTargetKeys(
            previousKey = previousGestureArtworkKey,
            nextKey = nextGestureArtworkKey
        )
    }
    val previousArtworkKey = when {
        queueSongs.isEmpty() -> null
        queueCurrentIndex > 0 -> queueSongs[queueCurrentIndex - 1]
        queueCurrentIndex == 0 -> queueSongs.lastOrNull()
        else -> null
    }?.resolvePlaybackArtworkKey(null)
    val nextArtworkKey = when {
        queueSongs.isEmpty() -> null
        queueCurrentIndex in 0 until queueSongs.lastIndex -> queueSongs[queueCurrentIndex + 1]
        queueCurrentIndex == queueSongs.lastIndex -> queueSongs.firstOrNull()
        else -> null
    }?.resolvePlaybackArtworkKey(null)

    // Advance the speculative queue target immediately instead of recomputing each rapid tap from
    // a possibly stale player-reported index. Keep a speculative navigation index until the player
    // catches up, otherwise multiple fast Next taps keep requesting the same artwork identity.
    var requestedQueueIndex by remember(queueSongs) { mutableIntStateOf(queueCurrentIndex) }
    LaunchedEffect(queueCurrentIndex, queueSongs.size, resolvedCoverPath) {
        val playerBoundIsPreparedTarget = !resolvedCoverPath.isNullOrBlank() &&
            artworkTransitionState.foregroundTargetKey() == resolvedCoverPath
        if (!artworkTransitionState.hasPendingNavigation() ||
            queueCurrentIndex == requestedQueueIndex ||
            playerBoundIsPreparedTarget
        ) {
            requestedQueueIndex = queueCurrentIndex
        }
    }

    fun indexForSong(song: AudioFile): Int {
        return queueSongs.indexOfFirst {
            it.path == song.path &&
                it.cueOffsetMs == song.cueOffsetMs &&
                it.cueTrackIndex == song.cueTrackIndex
        }
    }

    fun adjacentRequestedIndex(direction: PlayerArtworkDirection): Int {
        if (queueSongs.isEmpty()) return -1
        val base = requestedQueueIndex.takeIf { it in queueSongs.indices }
            ?: queueCurrentIndex.takeIf { it in queueSongs.indices }
            ?: 0
        return when (direction) {
            PlayerArtworkDirection.Previous -> if (base > 0) base - 1 else queueSongs.lastIndex
            PlayerArtworkDirection.Next -> if (base < queueSongs.lastIndex) base + 1 else 0
        }
    }

    LaunchedEffect(previousArtworkKey, nextArtworkKey) {
        previousArtworkKey?.takeIf { it.isNotBlank() }?.let { BitmapProvider.warmPlaybackArt(it) }
        nextArtworkKey?.takeIf { it.isNotBlank() }?.let { BitmapProvider.warmPlaybackArt(it) }
    }

    val inlineQueueVisible = sceneState.isQueueOverlayVisible
    var audioVisualizerForeground by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(audioVisualizerEnabled, isImmersiveEnabled) {
        if (!audioVisualizerEnabled) {
            audioVisualizerForeground = false
        } else if (isImmersiveEnabled) {
            // Immersive artwork already fills the viewport, so it starts directly above the cover.
            audioVisualizerForeground = true
        } else if (!audioVisualizerForeground) {
            kotlinx.coroutines.delay(2_000L)
            if (audioVisualizerEnabled) audioVisualizerForeground = true
        }
    }
    val visualizerPageVisible = if (controllerIsTransitioning) {
        controllerFromScene == PlayerSceneController.Scene.PLAYER ||
            controllerToScene == PlayerSceneController.Scene.PLAYER
    } else {
        (controllerScene == PlayerSceneController.Scene.PLAYER) ||
            (controllerScene == null && sceneState.currentScene == PlayerScene.PLAYER)
    }
    val visualizerRuntimeActive = audioVisualizerEnabled &&
        visualizerPageVisible &&
        !inlineQueueVisible &&
        !overlaySuspended
    DisposableEffect(visualizerRuntimeActive) {
        onAudioVisualizerRuntimeActiveChange(visualizerRuntimeActive)
        onDispose { onAudioVisualizerRuntimeActiveChange(false) }
    }
    var childInteractionVisible by remember { mutableStateOf(false) }
    var childModalDismissAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var immersiveMoreRequested by rememberSaveable { mutableStateOf(false) }
    var artworkViewerVisible by rememberSaveable { mutableStateOf(false) }
    val immersiveMoreVisible = immersiveMoreRequested && !overlaySuspended
    val dismissArtworkViewer = remember { { artworkViewerVisible = false } }
    val openArtworkViewer = {
        if (currentSong != null) artworkViewerVisible = true
    }

    fun dismissInlineQueue() {
        sceneState.closeQueueOverlay()
    }
    val dismissInlineQueueAction = remember(sceneState) {
        { sceneState.closeQueueOverlay() }
    }

    val toggleInlineQueue = {
        sceneState.toggleQueueOverlay()
    }
    val playQueueSong: (AudioFile, Int) -> Unit = { song, index ->
        val base = requestedQueueIndex.takeIf { it in queueSongs.indices } ?: queueCurrentIndex
        val direction = if (base >= 0 && index < base) {
            PlayerArtworkDirection.Previous
        } else {
            PlayerArtworkDirection.Next
        }
        requestedQueueIndex = index
        artworkTransitionState.prepare(
            direction = direction,
            expectedKey = song.resolvePlaybackArtworkKey(null),
            expectedQueueIndex = index
        )
        onQueueSongClick(song, index)
    }

    fun issueTrackCommand(
        direction: PlayerArtworkDirection,
        command: () -> AudioFile?,
        gestureCommand: () -> AudioFile?
    ) {
        // The controller returns the song selected by this exact command before its asynchronous
        // decoder switch starts. Use that identity to supersede any older swipe/button transaction.
        val gestureTargetKey = artworkTransitionState.pendingGestureTarget(direction)
        val selectedSong = if (gestureTargetKey != null) gestureCommand() else command()
        val selectedKey = selectedSong.resolvePlaybackArtworkKey(null)
        if (selectedSong != null && !selectedKey.isNullOrBlank()) {
            artworkTransitionState.prepare(
                direction = direction,
                expectedKey = selectedKey,
                expectedQueueIndex = indexForSong(selectedSong)
            )
        } else {
            artworkTransitionState.expectConfirmedNavigation(direction)
        }
    }

    val previousFromPlayer = {
        issueTrackCommand(
            PlayerArtworkDirection.Previous,
            command = onPrevious,
            gestureCommand = onArtworkGesturePrevious
        )
    }
    val nextFromPlayer = {
        issueTrackCommand(
            PlayerArtworkDirection.Next,
            command = onNext,
            gestureCommand = onArtworkGestureNext
        )
    }
    val modalVisibilityChanged: (Boolean) -> Unit = { childVisible ->
        childInteractionVisible = childVisible
    }
    val modalDismissActionChanged: ((() -> Unit)?) -> Unit = { dismissAction ->
        childModalDismissAction = dismissAction
    }
    val effectiveModalDismissAction = dismissArtworkViewer.takeIf { artworkViewerVisible }
        ?: childModalDismissAction
        ?: dismissInlineQueueAction.takeIf { inlineQueueVisible }
    val effectiveModalVisible = childInteractionVisible || effectiveModalDismissAction != null

    LaunchedEffect(effectiveModalVisible) {
        latestOnModalVisibleChange(effectiveModalVisible)
    }
    LaunchedEffect(effectiveModalDismissAction) {
        latestOnModalDismissActionChange(effectiveModalDismissAction)
    }

    LaunchedEffect(sceneState.currentScene) {
        when (sceneState.currentScene) {
            PlayerScene.QUEUE -> {
                sceneState.backToPlayer()
                sceneState.openQueueOverlay()
            }
            PlayerScene.PLAYER -> Unit
            else -> dismissInlineQueue()
        }
    }
    LaunchedEffect(controllerScene) {
        if (controllerScene != null &&
            controllerScene != PlayerSceneController.Scene.PLAYER &&
            controllerScene != PlayerSceneController.Scene.QUEUE
        ) {
            dismissInlineQueue()
        }
    }
    BackHandler(enabled = inlineQueueVisible) {
        dismissInlineQueue()
    }

    if (isImmersiveEnabled) {
        ImmersiveComposePlayerContainer(
            sceneState = sceneState,
            currentSong = currentSong,
            coverPath = resolvedCoverPath,
            artworkTransitionState = artworkTransitionState,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            audioVisualizerEnabled = audioVisualizerEnabled,
            audioSpectrum = audioSpectrum,
            audioVisualizerForeground = audioVisualizerForeground,
            onAudioVisualizerDismiss = onAudioVisualizerDismiss,
            onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
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
            inlineQueueVisible = inlineQueueVisible,
            onToggleInlineQueue = toggleInlineQueue,
            onQueueSongClick = onQueueSongClick,
            onClearPriorityQueue = onClearPriorityQueue,
            onClosePlayer = onClosePlayer,
            morePanelVisible = immersiveMoreVisible,
            onMorePanelVisibleChangeState = { immersiveMoreRequested = it },
            onModalVisibleChange = modalVisibilityChanged,
            onModalDismissActionChange = modalDismissActionChanged,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            onPrevious = {
                onPrevious()
                Unit
            },
            onPlayPause = onPlayPause,
            onNext = {
                onNext()
                Unit
            },
            onPlayMode = onPlayMode,
            onLyricModifyAlbumArt = onLyricModifyAlbumArt,
            onSearchLyrico = onSearchLyrico,
            onOpenInLyrico = onOpenInLyrico,
            sleepTimerSelection = sleepTimerSelection,
            onSleepTimerSelectionChange = onSleepTimerSelectionChange,
            onOpenLyric = onOpenLyric,
            onArtworkLongPress = openArtworkViewer,
            audioInfoText = audioInfoText,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            onOpenMetadata = onOpenMetadata,
            onOpenAudioEffects = onOpenAudioEffects,
            onLyricSeek = onLyricSeek,
            onLyricTranslationToggle = onLyricTranslationToggle,
            onLyricRomaToggle = onLyricRomaToggle,
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
            artworkTransitionState = artworkTransitionState,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            audioVisualizerEnabled = audioVisualizerEnabled,
            audioSpectrum = audioSpectrum,
            audioVisualizerForeground = audioVisualizerForeground,
            onAudioVisualizerDismiss = onAudioVisualizerDismiss,
            onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
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
            onPrevious = previousFromPlayer,
            onPlayPause = onPlayPause,
            onNext = nextFromPlayer,
            onPlayMode = onPlayMode,
            onPlayModeLongPress = onPlayModeLongPress,
            onMore = onMore,
            onOpenMetadata = onOpenMetadata,
            onOpenAudioEffects = onOpenAudioEffects,
            onLyricModifyAlbumArt = onLyricModifyAlbumArt,
            sleepTimerSelection = sleepTimerSelection,
            onSleepTimerSelectionChange = onSleepTimerSelectionChange,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            onOpenLyric = onOpenLyric,
            onArtworkLongPress = openArtworkViewer,
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
            inlineQueueVisible = inlineQueueVisible,
            onToggleInlineQueue = toggleInlineQueue,
            onQueueSongClick = playQueueSong,
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
            onLyricRomaToggle = onLyricRomaToggle,
            onOpenInLyrico = onOpenInLyrico,
            onSearchLyrico = onSearchLyrico,
            onClosePlayer = onClosePlayer,
            onBackToPlayer = onBackToPlayer,
            controllerScene = controllerScene,
            controllerFromScene = controllerFromScene,
            controllerToScene = controllerToScene,
            controllerProgress = controllerProgress,
            controllerIsTransitioning = controllerIsTransitioning,
            playerLyricsTransitionCoordinator = playerLyricsTransitionCoordinator,
            sourceCoverTarget = sourceCoverTarget,
            overlaySuspended = overlaySuspended,
            onModalVisibleChange = modalVisibilityChanged,
            onModalDismissActionChange = modalDismissActionChanged,
            modifier = modifier
        )
    }

    OriginalArtworkViewerDialog(
        show = artworkViewerVisible,
        song = currentSong,
        coverKey = resolvedCoverPath,
        onDismiss = dismissArtworkViewer
    )
}

@Composable
private fun ImmersiveComposePlayerContainer(
    sceneState: PlayerSceneState,
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit,
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
    inlineQueueVisible: Boolean,
    onToggleInlineQueue: () -> Unit,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onClosePlayer: () -> Unit,
    morePanelVisible: Boolean,
    onMorePanelVisibleChangeState: (Boolean) -> Unit,
    onModalVisibleChange: (Boolean) -> Unit,
    onModalDismissActionChange: ((() -> Unit)?) -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onLyricModifyAlbumArt: () -> Unit,
    onSearchLyrico: () -> Unit,
    onOpenInLyrico: () -> Unit,
    sleepTimerSelection: Int,
    onSleepTimerSelectionChange: (Int) -> Unit,
    onOpenLyric: () -> Unit,
    onArtworkLongPress: () -> Unit,
    audioInfoText: String = "",
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onOpenMetadata: () -> Unit,
    onOpenAudioEffects: () -> Unit,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onLyricRomaToggle: () -> Unit,
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
    val auxiliaryScene = sceneState.currentScene.takeIf {
        it == PlayerScene.QUEUE || it == PlayerScene.ALBUM_DETAIL || it == PlayerScene.FULL_COVER
    }
    val resolvedScene = auxiliaryScene
        ?: controllerSceneForVisibility?.toPlayerScene()
        ?: sceneState.currentScene
    val scene = if (resolvedScene == PlayerScene.QUEUE) PlayerScene.PLAYER else resolvedScene
    if (scene == PlayerScene.MAIN) return

    Box(modifier = modifier.fillMaxSize()) {
        if (scene in setOf(PlayerScene.PLAYER, PlayerScene.LYRIC, PlayerScene.ALBUM_DETAIL)) {
            val from = (controllerFromScene ?: scene.toControllerScene()).inlineQueueHostScene()
            val to = (controllerToScene ?: scene.toControllerScene()).inlineQueueHostScene()
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
                    currentScene = (controllerScene ?: scene.toControllerScene()).inlineQueueHostScene(),
                    fromScene = from,
                    toScene = to,
                    progress = controllerProgress,
                    isTransitioning = controllerIsTransitioning,
                    currentSong = currentSong,
                    coverPath = coverPath,
                    artworkTransitionState = artworkTransitionState,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
                    audioVisualizerEnabled = audioVisualizerEnabled,
                    audioSpectrum = audioSpectrum,
                    audioVisualizerForeground = audioVisualizerForeground,
                    onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                    onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
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
                    queueVisible = inlineQueueVisible,
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onQueueSongClick = onQueueSongClick,
                    onClearPriorityQueue = onClearPriorityQueue,
                    onToggleQueue = onToggleInlineQueue,
                    onBack = onClosePlayer,
                    onSeekStart = onSeekStart,
                    onSeekStop = onSeekStop,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPlayMode = onPlayMode,
                    onAudioQuality = onAudioQuality,
                    onAudioQualityLongPress = onAudioQualityLongPress,
                    onOpenMetadata = onOpenMetadata,
                    onOpenAudioEffects = onOpenAudioEffects,
                    showPlayerMore = morePanelVisible,
                    onShowPlayerMoreChange = onMorePanelVisibleChangeState,
                    onMorePanelVisibleChange = onModalVisibleChange,
                    onModalDismissActionChange = onModalDismissActionChange,
                    sleepTimerSelection = sleepTimerSelection,
                    onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                    onLyricModifyAlbumArt = onLyricModifyAlbumArt,
                    onSearchLyrico = onSearchLyrico,
                    onOpenInLyrico = onOpenInLyrico,
                    onOpenLyric = onOpenLyric,
                    onArtworkLongPress = onArtworkLongPress,
                    onLyricSeek = onLyricSeek,
                    onLyricTranslationToggle = onLyricTranslationToggle,
                    onLyricRomaToggle = onLyricRomaToggle,
                    onAlbumSongClick = onAlbumSongClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = size.height * (1f - drawerProgress)
                            alpha = 1f
                            clip = true
                        }
                        .clipToBounds()
                )
            }
            return@Box
        }

        AnimatedVisibility(
            visible = scene == PlayerScene.FULL_COVER,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            FullCoverPage(
                coverPath = coverPath,
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
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit,
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
    onOpenMetadata: () -> Unit,
    onOpenAudioEffects: () -> Unit,
    onLyricModifyAlbumArt: () -> Unit,
    sleepTimerSelection: Int,
    onSleepTimerSelectionChange: (Int) -> Unit,
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit,
    onOpenLyric: () -> Unit,
    onArtworkLongPress: () -> Unit,
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
    inlineQueueVisible: Boolean,
    onToggleInlineQueue: () -> Unit,
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
    onLyricRomaToggle: () -> Unit,
    onSearchLyrico: () -> Unit,
    onOpenInLyrico: () -> Unit,
    onClosePlayer: () -> Unit,
    onBackToPlayer: () -> Unit,
    controllerScene: PlayerSceneController.Scene?,
    controllerFromScene: PlayerSceneController.Scene?,
    controllerToScene: PlayerSceneController.Scene?,
    controllerProgress: Float,
    controllerIsTransitioning: Boolean,
    playerLyricsTransitionCoordinator: PlayerLyricsTransitionCoordinator?,
    sourceCoverTarget: CoverTransitionTarget?,
    overlaySuspended: Boolean,
    onModalVisibleChange: (Boolean) -> Unit,
    onModalDismissActionChange: ((() -> Unit)?) -> Unit,
    modifier: Modifier
) {
    val controllerSceneForVisibility = if (controllerIsTransitioning) {
        val from = controllerFromScene ?: PlayerSceneController.Scene.MAIN
        val to = controllerToScene ?: from
        if (to != PlayerSceneController.Scene.MAIN) to else from
    } else {
        controllerScene
    }
    val auxiliaryScene = sceneState.currentScene.takeIf {
        it == PlayerScene.QUEUE || it == PlayerScene.ALBUM_DETAIL || it == PlayerScene.FULL_COVER
    }
    val resolvedScene = auxiliaryScene
        ?: controllerSceneForVisibility?.toPlayerScene()
        ?: sceneState.currentScene
    val scene = if (resolvedScene == PlayerScene.QUEUE) PlayerScene.PLAYER else resolvedScene
    if (scene == PlayerScene.MAIN) return

    Box(modifier = modifier.fillMaxSize()) {
        if (scene == PlayerScene.PLAYER || scene == PlayerScene.LYRIC) {
            val from = (controllerFromScene ?: scene.toControllerScene()).inlineQueueHostScene()
            val to = (controllerToScene ?: scene.toControllerScene()).inlineQueueHostScene()
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
                currentScene = (controllerScene ?: scene.toControllerScene()).inlineQueueHostScene(),
                fromScene = from,
                toScene = to,
                progress = progress,
                isTransitioning = controllerIsTransitioning,
                playerLyricsTransitionCoordinator = playerLyricsTransitionCoordinator,
                currentSong = currentSong,
                coverPath = coverPath,
                artworkTransitionState = artworkTransitionState,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                audioVisualizerEnabled = audioVisualizerEnabled,
                audioSpectrum = audioSpectrum,
                audioVisualizerForeground = audioVisualizerForeground,
                onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
                lyricSong = lyricSong,
                lyricPositionMs = lyricPositionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
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
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onLyricModifyAlbumArt = onLyricModifyAlbumArt,
                sleepTimerSelection = sleepTimerSelection,
                onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                onAudioQuality = onAudioQuality,
                onAudioQualityLongPress = onAudioQualityLongPress,
                onOpenLyric = onOpenLyric,
                onArtworkLongPress = onArtworkLongPress,
                onPlayerCoverSwipeUpStart = onPlayerCoverSwipeUpStart,
                onPlayerCoverSwipeUpProgress = onPlayerCoverSwipeUpProgress,
                onPlayerCoverSwipeUpEnd = onPlayerCoverSwipeUpEnd,
                onPlayerCoverSwipeDownStart = onPlayerCoverSwipeDownStart,
                onPlayerCoverSwipeDownProgress = onPlayerCoverSwipeDownProgress,
                onPlayerCoverSwipeDownEnd = onPlayerCoverSwipeDownEnd,
                onLyricCoverSwipeDownStart = onLyricCoverSwipeDownStart,
                onLyricCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
                onLyricCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
                queueVisible = inlineQueueVisible,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                onQueueSongClick = onQueueSongClick,
                onClearPriorityQueue = onClearPriorityQueue,
                onToggleQueue = onToggleInlineQueue,
                onLyricSeek = onLyricSeek,
                onLyricTranslationToggle = onLyricTranslationToggle,
                onLyricRomaToggle = onLyricRomaToggle,
                onSearchLyrico = onSearchLyrico,
                onOpenInLyrico = onOpenInLyrico,
                onClosePlayer = onClosePlayer,
                onBackToPlayer = onBackToPlayer,
                sourceCoverTarget = sourceCoverTarget,
                overlaySuspended = overlaySuspended,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
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
                artworkTransitionState = artworkTransitionState,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                audioVisualizerEnabled = audioVisualizerEnabled,
                audioSpectrum = audioSpectrum,
                audioVisualizerForeground = audioVisualizerForeground,
                onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
                lyricSong = lyricSong,
                lyricPositionMs = lyricPositionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
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
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onAudioQuality = onAudioQuality,
                onAudioQualityLongPress = onAudioQualityLongPress,
                onOpenLyric = onOpenLyric,
                onArtworkLongPress = onArtworkLongPress,
                queueVisible = inlineQueueVisible,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                onQueueSongClick = onQueueSongClick,
                onClearPriorityQueue = onClearPriorityQueue,
                onToggleQueue = onToggleInlineQueue,
                sleepTimerSelection = sleepTimerSelection,
                onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                overlaySuspended = overlaySuspended,
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
                isPlaying = isPlaying,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                moreIconRes = moreIconRes,
                onSeek = onLyricSeek,
                onTranslationToggle = onLyricTranslationToggle,
                onRomaToggle = onLyricRomaToggle,
                onOpenInLyrico = onOpenInLyrico,
                onSearchLyrico = onSearchLyrico,
                onModifyAlbumArt = onLyricModifyAlbumArt,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onBack = onBackToPlayer
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
                coverPath = coverPath,
                title = currentSong?.title ?: "",
                onBack = { sceneState.backToPlayer() }
            )
        }
    }
}

private fun PlayerSceneController.Scene.inlineQueueHostScene(): PlayerSceneController.Scene =
    if (this == PlayerSceneController.Scene.QUEUE) PlayerSceneController.Scene.PLAYER else this

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
    playerLyricsTransitionCoordinator: PlayerLyricsTransitionCoordinator?,
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit,
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
    onOpenMetadata: () -> Unit,
    onOpenAudioEffects: () -> Unit,
    onLyricModifyAlbumArt: () -> Unit,
    sleepTimerSelection: Int,
    onSleepTimerSelectionChange: (Int) -> Unit,
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit,
    onOpenLyric: () -> Unit,
    onArtworkLongPress: () -> Unit,
    onPlayerCoverSwipeUpStart: () -> Unit,
    onPlayerCoverSwipeUpProgress: (Float) -> Unit,
    onPlayerCoverSwipeUpEnd: (Boolean, Float) -> Unit,
    onPlayerCoverSwipeDownStart: () -> Unit,
    onPlayerCoverSwipeDownProgress: (Float) -> Unit,
    onPlayerCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    onLyricCoverSwipeDownStart: () -> Unit,
    onLyricCoverSwipeDownProgress: (Float) -> Unit,
    onLyricCoverSwipeDownEnd: (Boolean, Float) -> Unit,
    queueVisible: Boolean,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onToggleQueue: () -> Unit,
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onLyricRomaToggle: () -> Unit,
    onSearchLyrico: () -> Unit,
    onOpenInLyrico: () -> Unit,
    onClosePlayer: () -> Unit,
    onBackToPlayer: () -> Unit,
    sourceCoverTarget: CoverTransitionTarget?,
    overlaySuspended: Boolean,
    onModalVisibleChange: (Boolean) -> Unit,
    onModalDismissActionChange: ((() -> Unit)?) -> Unit,
    drawerProgress: Float,
    modifier: Modifier = Modifier
) {
    var containerRootBounds by remember { mutableStateOf<RectF?>(null) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                containerRootBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current

        val session = playerLyricsTransitionCoordinator?.activeSession
        val frozenSessionProgress = playerLyricsTransitionCoordinator?.progress
            ?: progress.coerceIn(0f, 1f)
        val playerLyricTransition = isTransitioning &&
            session != null &&
            PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(
                session.from,
                session.to
            )
        val lyricsFraction = when {
            playerLyricTransition -> PlayerLyricsTransitionCoordinator.absoluteLyricsFraction(
                session = requireNotNull(session),
                transitionProgress = frozenSessionProgress
            )
            currentScene == PlayerSceneController.Scene.LYRIC -> 1f
            else -> 0f
        }.coerceIn(0f, 1f)

        val mainPlayerTransition = isTransitioning &&
            (
                (fromScene == PlayerSceneController.Scene.MAIN &&
                    toScene == PlayerSceneController.Scene.PLAYER) ||
                    (fromScene == PlayerSceneController.Scene.PLAYER &&
                        toScene == PlayerSceneController.Scene.MAIN)
                )

        val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
        val fallbackPlayerCoverRect = remember(widthPx, statusTopPx, density.density) {
            val side = 14.dp.value * density.density
            val top = statusTopPx + 20.dp.value * density.density
            val coverWidth = widthPx - side * 2f
            val coverHeight = coverWidth / 1.02f
            RectF(side, top, side + coverWidth, top + coverHeight)
        }
        val source = sourceCoverTarget?.bounds
            ?.toLocalRect(containerRootBounds)
            ?.takeIf { it.isUsableSource(widthPx, heightPx) }
        val mainSharedTransition = ENABLE_PLAYER_LIST_COVER_TRANSITION &&
            mainPlayerTransition &&
            !coverPath.isNullOrBlank() &&
            source != null

        val transitionRoute = session?.route ?: PlayerLyricsTransitionRoute.OffscreenSceneZoom
        val sessionPlayerRect = session?.playerArtworkRect
            ?.toLocalPlayerLyricsRect(containerRootBounds)
        val sessionLyricRect = session?.lyricArtworkRect
            ?.toLocalPlayerLyricsRect(containerRootBounds)
        // There is deliberately no synthetic artwork route. The overlay may only exist when
        // both real endpoints were visible and frozen at session creation.
        val artworkOverlayActive = playerLyricTransition &&
            transitionRoute == PlayerLyricsTransitionRoute.VisibleArtworkAligned &&
            !session?.artworkKey.isNullOrBlank() &&
            sessionPlayerRect?.isUsable == true &&
            sessionLyricRect?.isUsable == true

        val sceneMotion = if (playerLyricTransition) {
            resolvePlayerLyricsSceneMotion(
                session = session,
                lyricsFraction = lyricsFraction
            )
        } else {
            PlayerLyricsSceneMotion(
                playerAlpha = if (currentScene == PlayerSceneController.Scene.PLAYER) 1f else 0f,
                playerScale = 1f,
                playerTranslationX = 0f,
                playerTranslationY = 0f,
                lyricAlpha = if (currentScene == PlayerSceneController.Scene.LYRIC) 1f else 0f,
                lyricScale = 1f,
                lyricTranslationX = 0f,
                lyricTranslationY = 0f,
                useTopLeftTransformOrigin = false
            )
        }

        val playerSurfaceOffsetY = if (mainPlayerTransition) {
            heightPx * (1f - drawerProgress.coerceIn(0f, 1f))
        } else {
            0f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height
                    ) {
                        translate(top = playerSurfaceOffsetY) {
                            this@drawWithContent.drawContent()
                        }
                    }
                }
        ) {
            StandardPlayerBackdrop(
                coverPath = coverPath,
                accent = rememberCoverAccentColor(coverPath),
                artworkTransitionState = artworkTransitionState,
                modifier = Modifier.fillMaxSize()
            )

            // Keep both target layouts mounted before creating the zoom transition
            // transition. The hidden target continues to report stable artwork geometry, but its
            // route can no longer change the active immutable session.
            PlayerMainPage(
                currentSong = currentSong,
                coverPath = coverPath,
                artworkTransitionState = artworkTransitionState,
                isPlaying = isPlaying && (
                    currentScene == PlayerSceneController.Scene.PLAYER || playerLyricTransition
                    ),
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                audioVisualizerEnabled = audioVisualizerEnabled && sceneMotion.playerAlpha > 0f,
                audioSpectrum = audioSpectrum,
                audioVisualizerForeground = audioVisualizerForeground,
                onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
                lyricSong = lyricSong,
                lyricPositionMs = lyricPositionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
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
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onAudioQuality = onAudioQuality,
                onOpenLyric = onOpenLyric,
                onArtworkLongPress = onArtworkLongPress,
                onClosePlayer = onClosePlayer,
                onCoverSwipeUpStart = onPlayerCoverSwipeUpStart,
                onCoverSwipeUpProgress = onPlayerCoverSwipeUpProgress,
                onCoverSwipeUpEnd = onPlayerCoverSwipeUpEnd,
                onCoverSwipeDownStart = onPlayerCoverSwipeDownStart,
                onCoverSwipeDownProgress = onPlayerCoverSwipeDownProgress,
                onCoverSwipeDownEnd = onPlayerCoverSwipeDownEnd,
                onArtworkAnchorChanged = {
                    playerLyricsTransitionCoordinator?.updatePlayerCandidate(it)
                },
                showAlbumArt = !mainSharedTransition && !artworkOverlayActive,
                renderBackdrop = false,
                audioInfoText = audioInfoText,
                onAudioQualityLongPress = onAudioQualityLongPress,
                queueVisible = queueVisible,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                onQueueSongClick = onQueueSongClick,
                onClearPriorityQueue = onClearPriorityQueue,
                onToggleQueue = onToggleQueue,
                sleepTimerSelection = sleepTimerSelection,
                onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                overlaySuspended = overlaySuspended,
                contentAlpha = 1f,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .graphicsLayer {
                        alpha = sceneMotion.playerAlpha
                        scaleX = sceneMotion.playerScale
                        scaleY = sceneMotion.playerScale
                        translationX = sceneMotion.playerTranslationX
                        translationY = sceneMotion.playerTranslationY
                        transformOrigin = if (sceneMotion.useTopLeftTransformOrigin) {
                            TransformOrigin(0f, 0f)
                        } else {
                            TransformOrigin.Center
                        }
                    }
            )

            LyricPage(
                currentSong = currentSong,
                coverPath = coverPath,
                song = lyricSong,
                positionMs = lyricPositionMs,
                isPlaying = isPlaying && (
                    currentScene == PlayerSceneController.Scene.LYRIC || playerLyricTransition
                    ),
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                moreIconRes = moreIconRes,
                onSeek = onLyricSeek,
                onTranslationToggle = onLyricTranslationToggle,
                onRomaToggle = onLyricRomaToggle,
                onModifyAlbumArt = onLyricModifyAlbumArt,
                onSearchLyrico = onSearchLyrico,
                onOpenInLyrico = onOpenInLyrico,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onBack = onBackToPlayer,
                interactiveHorizontalSwipe = true,
                onCoverSwipeDownStart = onLyricCoverSwipeDownStart,
                onCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
                onCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
                showHeaderCover = !artworkOverlayActive,
                onHeaderCoverVisibilityChange = { },
                onArtworkAnchorChanged = {
                    playerLyricsTransitionCoordinator?.updateLyricCandidate(it)
                },
                renderBackdrop = false,
                contentAlpha = 1f,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(
                        when {
                            currentScene == PlayerSceneController.Scene.LYRIC -> 1f
                            playerLyricTransition -> 1f
                            else -> -1f
                        }
                    )
                    .graphicsLayer {
                        alpha = sceneMotion.lyricAlpha
                        scaleX = sceneMotion.lyricScale
                        scaleY = sceneMotion.lyricScale
                        translationX = sceneMotion.lyricTranslationX
                        translationY = sceneMotion.lyricTranslationY
                        transformOrigin = if (sceneMotion.useTopLeftTransformOrigin) {
                            TransformOrigin(0f, 0f)
                        } else {
                            TransformOrigin.Center
                        }
                    }
            )
        }

        if (mainSharedTransition) {
            val mainSourceRect = requireNotNull(source)
            val mainSourceTarget = requireNotNull(sourceCoverTarget)
            val f = if (fromScene == PlayerSceneController.Scene.MAIN) {
                progress.coerceIn(0f, 1f)
            } else {
                1f - progress.coerceIn(0f, 1f)
            }
            val rect = lerpRect(mainSourceRect, fallbackPlayerCoverRect, f)
            val sourceRadius = mainSourceTarget.radiusDp
            val playerRadius = STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP
            Box(
                modifier = Modifier
                    .zIndex(3f)
                    .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                    .size(
                        width = with(density) { rect.width().toDp() },
                        height = with(density) { rect.height().toDp() }
                    )
                    .graphicsLayer {
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
                    targetHeight = 1080,
                    priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                    surface = ArtworkSurface.Playback,
                    fadeInMillis = 0,
                    holdPreviousOnKeyChange = false,
                    fadeOnBitmapChange = false
                )
            }
        }

        if (artworkOverlayActive) {
            val playerRect = requireNotNull(sessionPlayerRect)
            val lyricRect = requireNotNull(sessionLyricRect)
            val absoluteArtworkProgress = lyricsFraction
            val rect = playerRect.lerpTo(lyricRect, absoluteArtworkProgress)
            val overlayAlpha = 1f
            val radiusDp = lerpFloat(
                session?.playerRadiusDp ?: STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP,
                session?.lyricRadiusDp ?: LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP,
                absoluteArtworkProgress
            )
            Box(
                modifier = Modifier
                    .zIndex(4f)
                    .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                    .size(
                        width = with(density) { rect.width.toDp() },
                        height = with(density) { rect.height.toDp() }
                    )
                    .graphicsLayer {
                        alpha = overlayAlpha
                        clip = true
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(radiusDp.dp)
                    }
            ) {
                BitmapImage(
                    key = session?.artworkKey.orEmpty(),
                    contentDescription = currentSong?.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 1080,
                    targetHeight = 1080,
                    priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                    surface = ArtworkSurface.Playback,
                    fadeInMillis = 0,
                    holdPreviousOnKeyChange = true,
                    fadeOnBitmapChange = false,
                    freezeBitmapUpdates = true
                )
            }
        }
    }
}


private fun PlayerLyricsRect.toLocalPlayerLyricsRect(
    containerRootBounds: RectF?
): PlayerLyricsRect {
    val root = containerRootBounds ?: return this
    return PlayerLyricsRect(
        left = left - root.left,
        top = top - root.top,
        right = right - root.left,
        bottom = bottom - root.top
    )
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
