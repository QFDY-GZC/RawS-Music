package com.rawsmusic.core.ui.widget

import android.content.res.Configuration
import android.graphics.RectF
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.systemui.rawReducedNavigationBottomPadding
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
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
private const val ENABLE_PLAYER_LIST_COVER_TRANSITION = true

// Keep the two measured geometry values local to the transition implementation.
// resource magnitudes centralized so they can be replaced directly if resources.arsc/dimens.xml
// becomes available. The behavior/formulas around them are source-exact.
private val AM_PLAYER_SHEET_TOP_RADIUS = 16.dp
private val AM_PLAYER_SHEET_SHADOW_HEIGHT = 12.dp
private const val AM_PLAYER_SHEET_SHADOW_START_ALPHA = 0.24f

// The player transition uses the same 500ms path interpolator for the regular
// transition and the shared-element handoff. Keep the Compose implementation on that curve so
// the measured artwork bounds and the page content arrive together.

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
    artworkTransitionState: PlaybackArtworkTransitionState,
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
    onOpenSpectrumAnalysis: () -> Unit = {},
    realtimeSeparationEnabled: Boolean = false,
    realtimeSeparationPreparing: Boolean = false,
    realtimeSeparationStem: Int = 0,
    realtimeSeparationStrength: Float = 1f,
    realtimeSeparationStatus: String = "",
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit = {},
    onRealtimeSeparationStemChange: (Int) -> Unit = {},
    onRealtimeSeparationStrengthChange: (Float) -> Unit = {},
    onPlayerStyleChange: (Boolean) -> Unit = {},
    onOpenLandscapePlayer: () -> Unit = {},
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
    onMainPlayerSheetGeometryChanged: (Float, Float) -> Unit = { _, _ -> },
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
    persistentBottomSheet: Boolean = false,
    overlaySuspended: Boolean = false,
    onClosePlayer: () -> Unit = { sceneState.backToMain() },
    onBackToPlayer: () -> Unit = { sceneState.backToPlayer() },
    onModalVisibleChange: (Boolean) -> Unit = {},
    onModalDismissActionChange: ((() -> Unit)?) -> Unit = {},
    controllerScene: PlayerSceneController.Scene? = null,
    controllerFromScene: PlayerSceneController.Scene? = null,
    controllerToScene: PlayerSceneController.Scene? = null,
    controllerProgress: Float = 0f,
    controllerProgressState: State<Float>? = null,
    controllerIsTransitioning: Boolean = false,
    controllerIsInteractiveGesture: Boolean = false,
    playerLyricsTransitionCoordinator: PlayerLyricsTransitionCoordinator? = null,
    sourceCoverTarget: CoverTransitionTarget? = null,
    /**
     * Live top edge of a floating MiniPlayer in root coordinates. Derive the collapsed sheet
     * geometry from the actual MiniPlayer/stacked chrome views instead of a synthetic screen
     * strip. Raw's floating style has no persistent BottomSheet child, so use its measured bar top
     * as the collapsed backdrop origin when available.
     */
    floatingMiniPlayerTopPx: Float? = null,
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
    val effectivePreviousGestureKey = previousGestureArtworkKey ?: previousArtworkKey
    val effectiveNextGestureKey = nextGestureArtworkKey ?: nextArtworkKey

    // Gesture recognition reads these values synchronously from pointer input. A LaunchedEffect
    // leaves one frame where a freshly committed queue still exposes the old neighbours, which can
    // start the next drag with another song's artwork. SideEffect publishes the current snapshot at
    // the same composition commit, while prefetch keeps an incoming card from changing identity
    // from a generic placeholder halfway through the drag.
    SideEffect {
        artworkTransitionState.setGestureTargetKeys(
            previousKey = effectivePreviousGestureKey,
            nextKey = effectiveNextGestureKey
        )
    }
    LaunchedEffect(effectivePreviousGestureKey, effectiveNextGestureKey) {
        artworkTransitionState.prefetchGestureTargets(
            previousKey = effectivePreviousGestureKey,
            nextKey = effectiveNextGestureKey
        )
    }

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
    LaunchedEffect(isImmersiveEnabled) {
        immersiveMoreRequested = false
        childInteractionVisible = false
        childModalDismissAction = null
    }
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
        val gestureTargetKey = artworkTransitionState.pendingGestureTarget(direction)
        if (gestureTargetKey != null) {
            val gestureTargetIndex = queueSongs.indexOfFirst {
                it.resolvePlaybackArtworkKey(null) == gestureTargetKey
            }
            if (gestureTargetIndex >= 0) requestedQueueIndex = gestureTargetIndex
            artworkTransitionState.expectPlayerBinding(gestureTargetKey, gestureTargetIndex)

            val dispatchedSong = gestureCommand()
            val dispatchedKey = dispatchedSong.resolvePlaybackArtworkKey(null)
            if (!dispatchedKey.isNullOrBlank() && dispatchedKey != gestureTargetKey) {
                // Shuffle/priority queues may advance between preview and transport dispatch. Keep
                // the correction inside the existing two-lane transaction instead of allowing
                // bindSong() to reset only the cover while the title is already on the real song.
                val dispatchedIndex = dispatchedSong?.let(::indexForSong) ?: -1
                if (dispatchedIndex >= 0) requestedQueueIndex = dispatchedIndex
                artworkTransitionState.prepare(
                    direction = direction,
                    expectedKey = dispatchedKey,
                    expectedQueueIndex = dispatchedIndex
                )
            }
            return
        }

        // "Previous" after the restart threshold seeks the current track to zero and must not
        // preview a different artwork. Every real button navigation otherwise completes the same
        // visual transaction used by a committed artwork swipe before changing playback.
        val restartsCurrentSong =
            direction == PlayerArtworkDirection.Previous && currentPositionMs > 3_000L
        val previewKey = artworkTransitionState.gestureTargetKey(direction)
        val previewIndex = previewKey?.let { key ->
            queueSongs.indexOfFirst { it.resolvePlaybackArtworkKey(null) == key }
        } ?: -1
        val animationStarted = !restartsCurrentSong &&
            artworkTransitionState.animateNavigation(
                direction = direction,
                expectedKey = previewKey,
                expectedQueueIndex = previewIndex
            ) {
                command()
            }
        if (!animationStarted) {
            artworkTransitionState.expectConfirmedNavigation(direction)
            command()
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
            onQueueSongClick = playQueueSong,
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
            onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
            realtimeSeparationEnabled = realtimeSeparationEnabled,
            realtimeSeparationPreparing = realtimeSeparationPreparing,
            realtimeSeparationStem = realtimeSeparationStem,
            realtimeSeparationStrength = realtimeSeparationStrength,
            realtimeSeparationStatus = realtimeSeparationStatus,
            onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
            onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
            onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
            onPlayerStyleChange = onPlayerStyleChange,
            onOpenLandscapePlayer = onOpenLandscapePlayer,
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
            onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
            realtimeSeparationEnabled = realtimeSeparationEnabled,
            realtimeSeparationPreparing = realtimeSeparationPreparing,
            realtimeSeparationStem = realtimeSeparationStem,
            realtimeSeparationStrength = realtimeSeparationStrength,
            realtimeSeparationStatus = realtimeSeparationStatus,
            onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
            onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
            onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
            onPlayerStyleChange = onPlayerStyleChange,
            onOpenLandscapePlayer = onOpenLandscapePlayer,
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
            onMainPlayerSheetGeometryChanged = onMainPlayerSheetGeometryChanged,
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
            controllerProgressState = controllerProgressState,
            controllerIsTransitioning = controllerIsTransitioning,
            controllerIsInteractiveGesture = controllerIsInteractiveGesture,
            persistentBottomSheet = persistentBottomSheet,
            playerLyricsTransitionCoordinator = playerLyricsTransitionCoordinator,
            sourceCoverTarget = sourceCoverTarget,
            floatingMiniPlayerTopPx = floatingMiniPlayerTopPx,
            overlaySuspended = overlaySuspended,
            // Modal visibility must not feed back into the child's own mount condition. It only
            // suspends the stationary PLAYER scene recognizers behind the modal surface.
            sceneGestureSuspended = overlaySuspended || effectiveModalVisible,
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
    onOpenSpectrumAnalysis: () -> Unit,
    realtimeSeparationEnabled: Boolean,
    realtimeSeparationPreparing: Boolean,
    realtimeSeparationStem: Int,
    realtimeSeparationStrength: Float,
    realtimeSeparationStatus: String,
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit,
    onRealtimeSeparationStemChange: (Int) -> Unit,
    onRealtimeSeparationStrengthChange: (Float) -> Unit,
    onPlayerStyleChange: (Boolean) -> Unit,
    onOpenLandscapePlayer: () -> Unit,
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
                    onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                    realtimeSeparationEnabled = realtimeSeparationEnabled,
                    realtimeSeparationPreparing = realtimeSeparationPreparing,
                    realtimeSeparationStem = realtimeSeparationStem,
                    realtimeSeparationStrength = realtimeSeparationStrength,
                    realtimeSeparationStatus = realtimeSeparationStatus,
                    onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                    onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                    onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                    isImmersiveEnabled = true,
                    onPlayerStyleChange = onPlayerStyleChange,
                    onOpenLandscapePlayer = onOpenLandscapePlayer,
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
                currentSong = currentSong,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                coverPath = coverPath,
                title = currentSong?.title ?: "",
                onQueueSongClick = onQueueSongClick,
                onBack = { sceneState.backToPlayer() },
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
    onOpenSpectrumAnalysis: () -> Unit,
    realtimeSeparationEnabled: Boolean,
    realtimeSeparationPreparing: Boolean,
    realtimeSeparationStem: Int,
    realtimeSeparationStrength: Float,
    realtimeSeparationStatus: String,
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit,
    onRealtimeSeparationStemChange: (Int) -> Unit,
    onRealtimeSeparationStrengthChange: (Float) -> Unit,
    onPlayerStyleChange: (Boolean) -> Unit,
    onOpenLandscapePlayer: () -> Unit,
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
    onMainPlayerSheetGeometryChanged: (Float, Float) -> Unit,
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
    controllerProgressState: State<Float>?,
    controllerIsTransitioning: Boolean,
    controllerIsInteractiveGesture: Boolean = false,
    persistentBottomSheet: Boolean,
    playerLyricsTransitionCoordinator: PlayerLyricsTransitionCoordinator?,
    sourceCoverTarget: CoverTransitionTarget?,
    floatingMiniPlayerTopPx: Float?,
    overlaySuspended: Boolean,
    sceneGestureSuspended: Boolean,
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
    if (scene == PlayerScene.MAIN && !persistentBottomSheet) return

    // A seek gesture is a child-owned interaction. Publish that ownership from DOWN until
    // seek completion so the stationary Initial-pass BottomSheet recognizer can decline the same
    // pointer stream before vertical finger drift crosses touchSlop.
    val timelineSeekGestureActive = remember { mutableStateOf(false) }
    val playerSeekStart = {
        timelineSeekGestureActive.value = true
        onSeekStart()
    }
    val playerSeekStop: (Float) -> Unit = { fraction ->
        timelineSeekGestureActive.value = false
        onSeekStop(fraction)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (
            scene == PlayerScene.PLAYER ||
            scene == PlayerScene.LYRIC ||
            (persistentBottomSheet && scene == PlayerScene.MAIN)
        ) {
            val from = (controllerFromScene ?: scene.toControllerScene()).inlineQueueHostScene()
            val to = (controllerToScene ?: scene.toControllerScene()).inlineQueueHostScene()
            val mainPlayerPair =
                (from == PlayerSceneController.Scene.MAIN && to == PlayerSceneController.Scene.PLAYER) ||
                    (from == PlayerSceneController.Scene.PLAYER && to == PlayerSceneController.Scene.MAIN)
            // Do not read the per-frame sheet state in composition. StandardPlayerLyricStack
            // consumes the stable State object from graphicsLayer callbacks, which limits each
            // frame to RenderNode property updates instead of recomposing the player hierarchy.
            val progress = if (mainPlayerPair && controllerProgressState != null) {
                0f
            } else {
                controllerProgress
            }.coerceIn(0f, 1f)
            val drawerProgress = when {
                controllerIsTransitioning && from == PlayerSceneController.Scene.MAIN && to != PlayerSceneController.Scene.MAIN -> progress
                controllerIsTransitioning && from != PlayerSceneController.Scene.MAIN && to == PlayerSceneController.Scene.MAIN -> 1f - progress
                else -> 1f
            }.coerceIn(0f, 1f)
            val mainPlayerTransition = mainPlayerPair
            StandardPlayerLyricStack(
                currentScene = (controllerScene ?: scene.toControllerScene()).inlineQueueHostScene(),
                fromScene = from,
                toScene = to,
                progress = progress,
                mainPlayerExpansionState = controllerProgressState,
                isTransitioning = controllerIsTransitioning,
                isInteractiveGesture = controllerIsInteractiveGesture,
                persistentBottomSheet = persistentBottomSheet,
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
                seekGestureActiveState = timelineSeekGestureActive,
                onSeekStart = playerSeekStart,
                onSeekStop = playerSeekStop,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onPlayModeLongPress = onPlayModeLongPress,
                onMore = onMore,
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                realtimeSeparationEnabled = realtimeSeparationEnabled,
                realtimeSeparationPreparing = realtimeSeparationPreparing,
                realtimeSeparationStem = realtimeSeparationStem,
                realtimeSeparationStrength = realtimeSeparationStrength,
                realtimeSeparationStatus = realtimeSeparationStatus,
                onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                onPlayerStyleChange = onPlayerStyleChange,
                onOpenLandscapePlayer = onOpenLandscapePlayer,
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
                onMainPlayerSheetGeometryChanged = onMainPlayerSheetGeometryChanged,
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
                floatingMiniPlayerTopPx = floatingMiniPlayerTopPx,
                overlaySuspended = overlaySuspended,
                sceneGestureSuspended = sceneGestureSuspended,
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
                onSeekStart = playerSeekStart,
                onSeekStop = playerSeekStop,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onPlayModeLongPress = onPlayModeLongPress,
                onMore = onMore,
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                realtimeSeparationEnabled = realtimeSeparationEnabled,
                realtimeSeparationPreparing = realtimeSeparationPreparing,
                realtimeSeparationStem = realtimeSeparationStem,
                realtimeSeparationStrength = realtimeSeparationStrength,
                realtimeSeparationStatus = realtimeSeparationStatus,
                onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                isImmersiveEnabled = false,
                onPlayerStyleChange = onPlayerStyleChange,
                onOpenLandscapePlayer = onOpenLandscapePlayer,
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
                onPlayPause = onPlayPause,
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
                currentSong = currentSong,
                queueSongs = queueSongs,
                queueCurrentIndex = queueCurrentIndex,
                coverPath = coverPath,
                title = currentSong?.title ?: "",
                onQueueSongClick = onQueueSongClick,
                onBack = { sceneState.backToPlayer() },
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
    mainPlayerExpansionState: State<Float>?,
    isTransitioning: Boolean,
    isInteractiveGesture: Boolean = false,
    persistentBottomSheet: Boolean,
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
    seekGestureActiveState: State<Boolean>,
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
    onOpenSpectrumAnalysis: () -> Unit,
    realtimeSeparationEnabled: Boolean,
    realtimeSeparationPreparing: Boolean,
    realtimeSeparationStem: Int,
    realtimeSeparationStrength: Float,
    realtimeSeparationStatus: String,
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit,
    onRealtimeSeparationStemChange: (Int) -> Unit,
    onRealtimeSeparationStrengthChange: (Float) -> Unit,
    onPlayerStyleChange: (Boolean) -> Unit,
    onOpenLandscapePlayer: () -> Unit,
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
    onMainPlayerSheetGeometryChanged: (Float, Float) -> Unit,
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
    floatingMiniPlayerTopPx: Float?,
    overlaySuspended: Boolean,
    sceneGestureSuspended: Boolean,
    onModalVisibleChange: (Boolean) -> Unit,
    onModalDismissActionChange: ((() -> Unit)?) -> Unit,
    drawerProgress: Float,
    modifier: Modifier = Modifier
) {
    var containerRootBounds by remember { mutableStateOf<RectF?>(null) }
    var playerArtworkAnchor by remember(coverPath) {
        mutableStateOf<PlayerLyricsArtworkAnchor?>(null)
    }
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
        val configuration = LocalConfiguration.current
        val sheetTopRadius = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            0.dp
        } else {
            AM_PLAYER_SHEET_TOP_RADIUS
        }

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
            // AlbumArtCard is square. Keeping the fallback square is important while the
            // measured anchor is not available: a 1.02 aspect-ratio fallback visibly squeezes
            // the cover during the first frames of the shared handoff.
            val coverHeight = coverWidth
            RectF(side, top, side + coverWidth, top + coverHeight)
        }
        // PlayerMainPage reports the real AlbumArtCard bounds. Prefer them over the old
        // synthetic full-width rectangle; this is the Compose equivalent of a
        // ChangeImageTransform capturing the source and destination views.
        val measuredPlayerCoverRect = playerArtworkAnchor
            ?.takeIf { it.artworkKey == coverPath && it.rect?.isUsable == true }
            ?.rect
            ?.toLocalPlayerLyricsRect(containerRootBounds)
            ?.takeIf { it.isUsable }
            ?.let { RectF(it.left, it.top, it.right, it.bottom) }
        // The measured cover sits inside the translated player sheet, so boundsInRoot changes on
        // every frame while MAIN and PLAYER move. Interpolating toward that moving target squeezed
        // and shook the artwork. The portrait target is deterministic from PlayerMainPage's own
        // status-bar padding, 20dp top inset and 14dp horizontal inset; use that frozen rectangle
        // for the sheet handoff and retain the measured anchor only for PLAYER/LYRIC transitions.
        val playerCoverRect = if (mainPlayerTransition) {
            fallbackPlayerCoverRect
        } else {
            measuredPlayerCoverRect ?: fallbackPlayerCoverRect
        }
        val source = sourceCoverTarget?.bounds
            ?.toLocalRect(containerRootBounds)
            ?.takeIf { it.isUsableSource(widthPx, heightPx) }
        val returningPlayerToMain = mainPlayerTransition &&
            fromScene == PlayerSceneController.Scene.PLAYER &&
            toScene == PlayerSceneController.Scene.MAIN
        val mainSharedTransition = ENABLE_PLAYER_LIST_COVER_TRANSITION &&
            mainPlayerTransition &&
            !coverPath.isNullOrBlank() &&
            source != null &&
            // Never let an entry-time ListCover survive into PLAYER -> MAIN. If a stale
            // composition still has that source, keep the real player artwork visible until the
            // MiniPlayer snapshot is supplied instead of animating toward the list.
            (!returningPlayerToMain ||
                sourceCoverTarget?.source == CoverTransitionTarget.Source.MiniPlayer)
        // The MAIN <-> PLAYER shared artwork owns only the sheet handoff itself. Keeping this
        // static overlay alive in stable PLAYER hides PlaybackArtworkTransition, so horizontal
        // swipes still move the title pager while the visible cover remains frozen. PlayerMainPage
        // stays mounted throughout the handoff and already owns the same PlaybackArtworkTransition
        // state, therefore stable PLAYER must immediately return artwork ownership to it.
        val mainArtworkOwnerActive = mainSharedTransition

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

        // Shared artwork is a newly composed node. Hiding the real player/lyric artwork in the
        // same composition that mounts this node can expose one empty raster frame before
        // BitmapImage has produced its first draw, which looks like a flash exactly when the
        // zoom gesture captures. Keep the endpoint views underneath for one rendered frame; the
        // overlay starts at the exact same frozen rect, so the overlap is invisible. On the next
        // frame ownership moves exclusively to the frozen overlay.
        var artworkOverlayReadySessionId by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(artworkOverlayActive, session?.id) {
            val id = session?.id
            if (!artworkOverlayActive || id == null) {
                artworkOverlayReadySessionId = null
                return@LaunchedEffect
            }
            artworkOverlayReadySessionId = null
            withFrameNanos { }
            if (playerLyricsTransitionCoordinator?.activeSession?.id == id) {
                artworkOverlayReadySessionId = id
            }
        }
        val artworkOverlayOwnsEndpoints = artworkOverlayActive &&
            artworkOverlayReadySessionId == session?.id

        val sceneMotion = if (playerLyricTransition) {
            resolvePlayerLyricsSceneMotion(
                session = session,
                lyricsFraction = lyricsFraction
            )
        } else {
            PlayerLyricsSceneMotion(
                // MAIN keeps the committed scene until the transition finishes. Keep the player
                // content visible while its whole surface follows the drawer, rather than popping
                // it in only on the final frame.
                playerAlpha = if (
                    currentScene == PlayerSceneController.Scene.PLAYER || mainPlayerTransition
                ) 1f else 0f,
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

        // Keep the player page as one bottom sheet. The sheet is laid out at its final size
        // and enters by translation from the bottom; the cover is a separate shared element.
        fun currentPlayerRevealFraction(): Float = when {
            mainPlayerTransition && mainPlayerExpansionState != null ->
                mainPlayerExpansionState.value.coerceIn(0f, 1f)
            mainPlayerTransition && fromScene == PlayerSceneController.Scene.MAIN ->
                progress.coerceIn(0f, 1f)
            mainPlayerTransition -> 1f - progress.coerceIn(0f, 1f)
            persistentBottomSheet && currentScene == PlayerSceneController.Scene.MAIN -> 0f
            else -> 1f
        }

        fun currentPlayerContentAlpha(): Float {
            if (!mainPlayerTransition &&
                !(persistentBottomSheet && currentScene == PlayerSceneController.Scene.MAIN)
            ) return 1f
            val reveal = currentPlayerRevealFraction()
            return if (reveal <= 0f) 0f
            else kotlin.math.max(0f, 1f + 0.5f * kotlin.math.ln(reveal))
        }

        fun currentPlayerBackdropAlpha(): Float {
            if (!mainPlayerTransition &&
                !(persistentBottomSheet && currentScene == PlayerSceneController.Scene.MAIN)
            ) return 1f
            val reveal = currentPlayerRevealFraction()
            // The backdrop itself is opaque inside the sheet. RawS Music still needs a tiny
            // ownership handoff at the collapsed endpoint because its MiniPlayer is composed in a
            // sibling tree above MAIN rather than as a child/elevation layer of the BottomSheet.
            // Keep this endpoint-only guard until those roots are physically merged; do not reuse
            // it as a general player-content animation curve.
            return if (reveal <= 0f) 0f else (1f - kotlin.math.exp(-300f * reveal)).coerceIn(0f, 1f)
        }
        val reducedNavigationBottomPadding = rawReducedNavigationBottomPadding(reduceBy = 12.dp)
        val playerPeekHeightPx = with(density) {
            (122.dp + reducedNavigationBottomPadding).toPx()
        }
        val measuredFloatingCollapsedTop = floatingMiniPlayerTopPx
            ?.takeIf { it.isFinite() && it > 0f && it < heightPx }
        val playerSheetTravelPx = if (!persistentBottomSheet && measuredFloatingCollapsedTop != null) {
            measuredFloatingCollapsedTop
        } else {
            (heightPx - playerPeekHeightPx).coerceAtLeast(0f)
        }
        // Both NORMAL and floating player variants must measure drag deltas in the stationary
        // parent coordinate space. In floating mode the old PLAYER -> MAIN recognizer lived on
        // AlbumArtCard, which is a descendant of the translated sheet. Every translation changed
        // that pointer node's local Y coordinates, feeding the sheet movement back into the next
        // drag sample and producing the remaining oscillation/jitter. The sheet behavior /
        // ViewDragHelper always reads MotionEvents in CoordinatorLayout coordinates and moves the
        // sheet child separately. Treat a valid floating collapsed top as the same root-owned
        // draggable sheet geometry instead of falling back to the moving artwork recognizer.
        val rootOwnsMainPlayerSheetDownGesture =
            persistentBottomSheet || measuredFloatingCollapsedTop != null
        LaunchedEffect(playerSheetTravelPx, widthPx) {
            onMainPlayerSheetGeometryChanged(playerSheetTravelPx, widthPx)
        }

        // Keep the whole Now Playing root attached to one sheet behavior. Once PLAYER is
        // expanded, a downward drag can start from any ordinary player area; while the sheet is
        // settling, a new pointer may recapture it and reverse direction. Keep that ownership on
        // this persistent sheet instead of limiting PLAYER -> MAIN to AlbumArtCard. Scrollable
        // queue content remains authoritative while it is visible; matching nested-scroll
        // handoff for that child can be layered on separately without stealing its scrolling.
        val context = LocalContext.current
        val sheetFlingVelocityBoundsPx = remember(context) {
            ViewConfiguration.get(context).let { config ->
                config.scaledMinimumFlingVelocity.toFloat() to
                    config.scaledMaximumFlingVelocity.toFloat()
            }
        }
        val minimumSheetFlingVelocityPx = sheetFlingVelocityBoundsPx.first
        val maximumSheetFlingVelocityPx = sheetFlingVelocityBoundsPx.second
        val latestSheetCurrentScene by rememberUpdatedState(currentScene)
        val latestMainPlayerTransition by rememberUpdatedState(mainPlayerTransition)
        val latestPlayerLyricTransition by rememberUpdatedState(playerLyricTransition)
        val latestQueueVisible by rememberUpdatedState(queueVisible)
        val latestSceneGestureSuspended by rememberUpdatedState(sceneGestureSuspended)
        val latestSheetDragStart by rememberUpdatedState(onPlayerCoverSwipeDownStart)
        val latestSheetDragProgress by rememberUpdatedState(onPlayerCoverSwipeDownProgress)
        val latestSheetDragEnd by rememberUpdatedState(onPlayerCoverSwipeDownEnd)
        // The drag helper only receives a DOWN that actually lands inside the sheet child.
        // Our stationary parent pointer node intentionally avoids the translated-child coordinate
        // feedback loop, but keeping that node active across a non-interactive PLAYER -> MAIN
        // settle would make the *whole screen* a sheet hit target. Then an early list scroll on the
        // already-exposed MAIN area can recapture the sheet and reopen PLAYER. During a committed
        // collapse settle, drop the parent recognizer entirely so the revealed MAIN immediately
        // owns touches, matching CoordinatorLayout child hit testing.
        val settlingFromPlayer =
            rootOwnsMainPlayerSheetDownGesture &&
                mainPlayerTransition &&
                !isInteractiveGesture &&
                currentScene == PlayerSceneController.Scene.PLAYER
        val playerSheetDragModifier = if (
            !rootOwnsMainPlayerSheetDownGesture ||
            playerSheetTravelPx <= 0f ||
            settlingFromPlayer
        ) {
            Modifier
        } else {
            Modifier.pointerInput(
                playerSheetTravelPx,
                minimumSheetFlingVelocityPx,
                maximumSheetFlingVelocityPx
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    val expansionAtDown = mainPlayerExpansionState?.value?.coerceIn(0f, 1f)
                        ?: if (latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER) 1f else 0f
                    val sheetTopAtDown = playerSheetTravelPx * (1f - expansionAtDown)
                    // A real BottomSheetBehavior cannot capture a DOWN above the child's current
                    // top. Preserve the stationary coordinate space for drag math, but restore
                    // that hit-region rule so MAIN content revealed during a transition remains
                    // immediately scrollable instead of becoming a full-screen recapture zone.
                    val downHitsCurrentSheet = down.position.y >= sheetTopAtDown
                    val enabledAtDown =
                        !latestPlayerLyricTransition &&
                            !latestQueueVisible &&
                            !latestSceneGestureSuspended &&
                            downHitsCurrentSheet &&
                            (latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER ||
                                latestMainPlayerTransition)
                    if (!enabledAtDown) return@awaitEachGesture

                    val pointerId = down.id
                    val startPosition = down.position
                    val tracker = VelocityTracker().apply {
                        addPosition(down.uptimeMillis, down.position)
                    }
                    var dragStartExpansion = mainPlayerExpansionState?.value?.coerceIn(0f, 1f)
                        ?: if (latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER) 1f else 0f
                    var axis = 0 // 0 undecided, 1 vertical sheet, 2 rejected
                    val stableExpandedPlayerAtDown =
                        latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER &&
                            !latestMainPlayerTransition
                    var lastPointerY = startPosition.y
                    var draggedDy = 0f
                    var finishedNormally = false

                    try {
                        while (true) {
                            // CoordinatorLayout/BottomSheetBehavior gets first chance to
                            // intercept a vertical move before ordinary children. Initial pass
                            // gives this persistent sheet the same ownership timing; horizontal
                            // gestures remain unconsumed and continue to artwork/controls.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                                ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)

                            val dx = change.position.x - startPosition.x
                            val dy = change.position.y - startPosition.y
                            val absX = kotlin.math.abs(dx)
                            val absY = kotlin.math.abs(dy)

                            // SeekBar owns its pointer stream from DOWN to stopTrackingTouch.
                            // Our parent is intentionally observing Initial pass, so explicitly
                            // abandon capture before evaluating touchSlop once the child has started
                            // seeking. This prevents small vertical drift while scrubbing from
                            // collapsing the PLAYER sheet.
                            if (axis == 0 && seekGestureActiveState.value) {
                                axis = 2
                                continue
                            }

                            // The sheet and artwork carousel observe the same stream at Initial.
                            // Waiting only for vertical touchSlop lets ordinary finger jitter start
                            // PLAYER -> MAIN while the user is clearly switching artwork. Resolve
                            // the axis once and never recapture a stream rejected as horizontal.
                            // Ambiguous diagonals remain undecided until one direction dominates.
                            if (axis == 0 &&
                                (absX > viewConfiguration.touchSlop ||
                                    absY > viewConfiguration.touchSlop)
                            ) {
                                if (absX > absY * 1.10f) {
                                    axis = 2
                                    continue
                                }
                                if (absY <= absX * 1.20f) {
                                    continue
                                }
                                // Stable PLAYER reserves upward motion for PLAYER -> LYRICS. The
                                // parent sheet only owns downward collapse there. During an actual
                                // MAIN <-> PLAYER drag/recapture, both directions still belong to
                                // the sheet, just like ViewDragHelper. This prevents the Initial-
                                // pass parent from stealing the same upward pointer that the
                                // artwork uses to enter lyrics.
                                if (stableExpandedPlayerAtDown && dy < 0f) {
                                    axis = 2
                                    continue
                                }
                                axis = 1
                                // Preserve the distance *beyond* touchSlop on the capture frame.
                                // The old code rebased to the current pointer and discarded the
                                // whole pre-capture delta, so the finger permanently led the sheet
                                // by one event (often 15-30 px on high-density devices). A real
                                // drag should have only the touchSlop dead-zone: any overshoot must
                                // move the sheet immediately on the same MotionEvent.
                                val slop = viewConfiguration.touchSlop
                                val captureOverslop = when {
                                    dy > slop -> dy - slop
                                    dy < -slop -> dy + slop
                                    else -> 0f
                                }
                                lastPointerY = change.position.y
                                draggedDy = captureOverslop
                                dragStartExpansion = mainPlayerExpansionState?.value?.coerceIn(0f, 1f)
                                    ?: if (latestSheetCurrentScene == PlayerSceneController.Scene.PLAYER) 1f else 0f
                                latestSheetDragStart()
                                if (captureOverslop != 0f) {
                                    latestSheetDragProgress(captureOverslop / playerSheetTravelPx)
                                }
                                // CoordinatorLayout intercepts at this boundary and cancels the
                                // child stream. Consume the capture event after applying overslop
                                // so there is no one-frame stationary pause at capture.
                                change.consume()
                            } else if (axis == 1) {
                                val frameDy = change.position.y - lastPointerY
                                lastPointerY = change.position.y
                                draggedDy += frameDy
                                latestSheetDragProgress(draggedDy / playerSheetTravelPx)
                                change.consume()
                            }

                            if (!change.pressed) {
                                finishedNormally = true
                                if (axis == 1) {
                                    val rawVelocity = tracker.calculateVelocity()
                                    fun clampFlingVelocity(value: Float): Float = when {
                                        kotlin.math.abs(value) < minimumSheetFlingVelocityPx -> 0f
                                        value > maximumSheetFlingVelocityPx -> maximumSheetFlingVelocityPx
                                        value < -maximumSheetFlingVelocityPx -> -maximumSheetFlingVelocityPx
                                        else -> value
                                    }
                                    val velocityX = clampFlingVelocity(rawVelocity.x)
                                    val velocityY = clampFlingVelocity(rawVelocity.y)
                                    val predictedExpansion =
                                        (dragStartExpansion - draggedDy / playerSheetTravelPx)
                                            .coerceIn(0f, 1f)
                                    // BottomSheetBehavior.onViewReleased(): an upward Y fling always
                                    // expands. A downward fling collapses only when Y is at least as
                                    // dominant as X. Zero-Y or horizontal-dominant release uses the
                                    // nearest anchor instead of treating a tiny diagonal as a fling.
                                    val shouldClose = when {
                                        velocityY < 0f -> false
                                        velocityY == 0f || kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY) ->
                                            predictedExpansion < 0.5f
                                        else -> true
                                    }
                                    // ViewDragHelper.computeAxisDuration uses parentWidth / 2 as
                                    // its distance influence even for vertical settling. Normalize
                                    // physical Y velocity by the parent width, not the sheet travel,
                                    // so PlayerSceneController's dimensionless formula is identical.
                                    latestSheetDragEnd(shouldClose, velocityY / widthPx)
                                }
                                break
                            }
                        }
                    } finally {
                        if (!finishedNormally && axis == 1) {
                            val predictedExpansion =
                                (dragStartExpansion - draggedDy / playerSheetTravelPx)
                                    .coerceIn(0f, 1f)
                            latestSheetDragEnd(predictedExpansion < 0.5f, 0f)
                        }
                    }
                }
            }
        }

        // PLAYER -> LYRICS upward navigation must use the same stationary parent coordinate
        // space. AlbumArtCard and ArtworkTitleInfoPager are both transformed by sceneMotion while
        // the transition is interactive; deriving dy from either moving child feeds the transform
        // back into the next pointer sample and can flash/duplicate the whole PLAYER/LYRIC stack.
        // Owning the gesture here also covers the title and the otherwise-empty band between the
        // artwork and mini lyrics with one physical drag owner.
        val latestPlayerLyricUpScene by rememberUpdatedState(currentScene)
        val latestPlayerLyricUpTransition by rememberUpdatedState(isTransitioning)
        val latestPlayerLyricUpQueueVisible by rememberUpdatedState(queueVisible)
        val latestPlayerLyricUpSceneGestureSuspended by rememberUpdatedState(sceneGestureSuspended)
        val latestPlayerLyricUpStart by rememberUpdatedState(onPlayerCoverSwipeUpStart)
        val latestPlayerLyricUpProgress by rememberUpdatedState(onPlayerCoverSwipeUpProgress)
        val latestPlayerLyricUpEnd by rememberUpdatedState(onPlayerCoverSwipeUpEnd)
        val playerLyricSwipeUpModifier = Modifier.pointerInput(widthPx) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val enabledAtDown =
                    latestPlayerLyricUpScene == PlayerSceneController.Scene.PLAYER &&
                        !latestPlayerLyricUpTransition &&
                        !latestPlayerLyricUpQueueVisible &&
                        !latestPlayerLyricUpSceneGestureSuspended &&
                        !seekGestureActiveState.value
                if (!enabledAtDown) return@awaitEachGesture

                val pointerId = down.id
                val start = down.position
                val tracker = VelocityTracker().apply {
                    addPosition(down.uptimeMillis, down.position)
                }
                var axis = 0 // 0 undecided, 1 PLAYER -> LYRIC up, 2 rejected
                var effectiveDy = 0f
                var finishedNormally = false

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)

                        val dx = change.position.x - start.x
                        val dy = change.position.y - start.y
                        if (axis == 0 && (
                                seekGestureActiveState.value ||
                                    latestPlayerLyricUpQueueVisible ||
                                    latestPlayerLyricUpSceneGestureSuspended ||
                                    latestPlayerLyricUpTransition ||
                                    latestPlayerLyricUpScene != PlayerSceneController.Scene.PLAYER
                            )
                        ) {
                            axis = 2
                        }
                        if (axis == 0 &&
                            (kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                kotlin.math.abs(dy) > viewConfiguration.touchSlop)
                        ) {
                            axis = if (
                                dy < 0f &&
                                kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f
                            ) {
                                1
                            } else {
                                2
                            }
                            if (axis == 1) latestPlayerLyricUpStart()
                        }

                        if (axis == 1) {
                            effectiveDy = (dy + viewConfiguration.touchSlop)
                                .coerceIn(-widthPx, 0f)
                            latestPlayerLyricUpProgress((-effectiveDy / widthPx).coerceIn(0f, 1f))
                            change.consume()
                        }

                        if (!change.pressed) {
                            finishedNormally = true
                            if (axis == 1) {
                                val ratio = (-effectiveDy / widthPx).coerceIn(0f, 1f)
                                val velocityY = tracker.calculateVelocity().y
                                val commit = PlayerLyricsTransitionCoordinator.shouldCommit(
                                    progress = ratio,
                                    velocityPxPerSecond = velocityY,
                                    density = density.density,
                                    expectedVelocitySign = -1
                                )
                                val settleVelocity =
                                    PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                                        progress = ratio,
                                        commit = commit,
                                        velocityPxPerSecond = velocityY,
                                        travelDistancePx = widthPx,
                                        expectedVelocitySign = -1
                                    )
                                latestPlayerLyricUpEnd(commit, settleVelocity)
                            }
                            break
                        }
                    }
                } finally {
                    if (!finishedNormally && axis == 1) {
                        latestPlayerLyricUpEnd(false, 0f)
                    }
                }
            }
        }

        // PLAYER <-> LYRICS horizontal navigation must also live in this stationary parent.
        // LyricPage itself is translated/scaled during the shared scene motion; reading
        // change.position from that moving subtree feeds the scene transform back into the next
        // pointer sample. The error grows with drag distance, which is why the right half of the
        // display could produce a full-frame flash/duplicate surface while swiping back.
        val latestLyricScene by rememberUpdatedState(currentScene)
        val latestAnyTransition by rememberUpdatedState(isTransitioning)
        val latestLyricSwipeStart by rememberUpdatedState(onLyricCoverSwipeDownStart)
        val latestLyricSwipeProgress by rememberUpdatedState(onLyricCoverSwipeDownProgress)
        val latestLyricSwipeEnd by rememberUpdatedState(onLyricCoverSwipeDownEnd)
        val lyricSwipeBackModifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val enabledAtDown =
                    latestLyricScene == PlayerSceneController.Scene.LYRIC &&
                        !latestAnyTransition &&
                        !latestSceneGestureSuspended
                if (!enabledAtDown) return@awaitEachGesture

                val pointerId = down.id
                val start = down.position
                val tracker = VelocityTracker().apply {
                    addPosition(down.uptimeMillis, down.position)
                }
                var axis = 0 // 0 undecided, 1 right-swipe back, 2 rejected
                var effectiveDx = 0f
                var finishedNormally = false

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)

                        val dx = change.position.x - start.x
                        val dy = change.position.y - start.y
                        if (axis == 0 &&
                            (kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                kotlin.math.abs(dy) > viewConfiguration.touchSlop)
                        ) {
                            axis = if (
                                dx > 0f &&
                                kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f
                            ) {
                                1
                            } else {
                                2
                            }
                            if (axis == 1) {
                                latestLyricSwipeStart()
                            }
                        }

                        if (axis == 1) {
                            // Keep only the ordinary touchSlop dead-zone; all movement beyond it
                            // is mapped in the stationary parent coordinate system.
                            effectiveDx = (dx - viewConfiguration.touchSlop)
                                .coerceIn(0f, widthPx)
                            latestLyricSwipeProgress(effectiveDx / widthPx)
                            change.consume()
                        }

                        if (!change.pressed) {
                            finishedNormally = true
                            if (axis == 1) {
                                val progress = (effectiveDx / widthPx).coerceIn(0f, 1f)
                                val velocityX = tracker.calculateVelocity().x
                                val commit = PlayerLyricsTransitionCoordinator.shouldCommit(
                                    progress = progress,
                                    velocityPxPerSecond = velocityX,
                                    density = density.density,
                                    expectedVelocitySign = 1
                                )
                                val settleVelocity =
                                    PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                                        progress = progress,
                                        commit = commit,
                                        velocityPxPerSecond = velocityX,
                                        travelDistancePx = widthPx,
                                        expectedVelocitySign = 1
                                    )
                                latestLyricSwipeEnd(commit, settleVelocity)
                            }
                            break
                        }
                    }
                } finally {
                    if (!finishedNormally && axis == 1) {
                        latestLyricSwipeEnd(false, 0f)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(lyricSwipeBackModifier)
                .then(playerLyricSwipeUpModifier)
                // Keep gesture tracking in the stationary parent coordinate space. The
                // ViewDragHelper reads MotionEvent coordinates from CoordinatorLayout and moves
                // the captured sheet child separately. Attaching pointerInput to the translated
                // sheet creates a feedback loop: moving the sheet changes the pointer's local Y,
                // which then changes the next expansion sample and makes the surface shiver.
                .then(playerSheetDragModifier)
        ) {
            if (persistentBottomSheet) {
                // Draw the behavior scrim behind the sheet. The
                // stacked holder writes the raw slide offset here, so p=0 is clear and p=1 is
                // fully black. The sheet itself covers the screen at p=1; the scrim is primarily
                // visible above it during the drag and through the two rounded top corners.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = currentPlayerRevealFraction()
                        }
                        .background(Color.Black)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = if (
                            mainPlayerTransition ||
                            (persistentBottomSheet && currentScene == PlayerSceneController.Scene.MAIN)
                        ) {
                            playerSheetTravelPx * (1f - currentPlayerRevealFraction())
                        } else {
                            0f
                        }
                    }
            ) {
                StandardPlayerBackdrop(
                    coverPath = coverPath,
                    accent = rememberCoverAccentColor(coverPath),
                    artworkTransitionState = artworkTransitionState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = currentPlayerBackdropAlpha()
                            clip = sheetTopRadius.value > 0f
                            shape = RoundedCornerShape(
                                topStart = sheetTopRadius,
                                topEnd = sheetTopRadius,
                                bottomStart = 0.dp,
                                bottomEnd = 0.dp,
                            )
                        }
                )

                // C12192q0.k.mo14251a() shrinks the dedicated top-edge shadow view with
                // shadow_height * exp(-100p). A fixed gradient scaled from its top edge is the
                // same raster result without recomposing layout height every drag frame.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AM_PLAYER_SHEET_SHADOW_HEIGHT)
                        .graphicsLayer {
                            scaleY = kotlin.math.exp(-100f * currentPlayerRevealFraction())
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = AM_PLAYER_SHEET_SHADOW_START_ALPHA),
                                    Color.Transparent,
                                )
                            )
                        )
                )

            // Keep both target layouts mounted before creating the zoom transition. The player
            // surface follows the same sheet progress as the shared cover.
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
                onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                realtimeSeparationEnabled = realtimeSeparationEnabled,
                realtimeSeparationPreparing = realtimeSeparationPreparing,
                realtimeSeparationStem = realtimeSeparationStem,
                realtimeSeparationStrength = realtimeSeparationStrength,
                realtimeSeparationStatus = realtimeSeparationStatus,
                onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                isImmersiveEnabled = false,
                onPlayerStyleChange = onPlayerStyleChange,
                onOpenLandscapePlayer = onOpenLandscapePlayer,
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
                mainPlayerSheetTransitionActive = mainPlayerTransition,
                rootOwnsPlayerSheetDownGesture = rootOwnsMainPlayerSheetDownGesture,
                rootOwnsPlayerLyricUpGesture = true,
                onArtworkAnchorChanged = {
                    playerArtworkAnchor = it
                    playerLyricsTransitionCoordinator?.updatePlayerCandidate(it)
                },
                showAlbumArt = !mainArtworkOwnerActive && !artworkOverlayOwnsEndpoints,
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
                contentAlpha = if (mainPlayerTransition) 1f else currentPlayerContentAlpha(),
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .graphicsLayer {
                        alpha = if (mainPlayerTransition) {
                            currentPlayerContentAlpha()
                        } else {
                            sceneMotion.playerAlpha
                        }
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
                onPlayPause = onPlayPause,
                onTranslationToggle = onLyricTranslationToggle,
                onRomaToggle = onLyricRomaToggle,
                onModifyAlbumArt = onLyricModifyAlbumArt,
                onSearchLyrico = onSearchLyrico,
                onOpenInLyrico = onOpenInLyrico,
                onModalVisibleChange = onModalVisibleChange,
                onModalDismissActionChange = onModalDismissActionChange,
                onBack = onBackToPlayer,
                interactiveHorizontalSwipe = false,
                parentOwnsHorizontalSwipe = true,
                onCoverSwipeDownStart = onLyricCoverSwipeDownStart,
                onCoverSwipeDownProgress = onLyricCoverSwipeDownProgress,
                onCoverSwipeDownEnd = onLyricCoverSwipeDownEnd,
                showHeaderCover = !artworkOverlayOwnsEndpoints,
                onHeaderCoverVisibilityChange = { },
                onArtworkAnchorChanged = {
                    playerLyricsTransitionCoordinator?.updateLyricCandidate(it)
                },
                artworkTransitionState = artworkTransitionState,
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
        }

        if (mainArtworkOwnerActive) {
            val mainSourceRect = requireNotNull(source)
            val mainSourceTarget = requireNotNull(sourceCoverTarget)
            val sourceRadius = mainSourceTarget.radiusDp
            val playerRadius = playerArtworkAnchor
                ?.takeIf { it.artworkKey == coverPath }
                ?.cornerRadiusDp
                ?: STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP
            // Both endpoints are square. A uniform transform matches ChangeImageTransform and
            // avoids the one-frame squeeze produced by independent X/Y scaling during entry.
            Box(
                modifier = Modifier
                    .zIndex(3f)
                    .offset {
                        IntOffset(
                            playerCoverRect.left.toInt(),
                            playerCoverRect.top.toInt()
                        )
                    }
                    .size(
                        width = with(density) { playerCoverRect.width().toDp() },
                        height = with(density) { playerCoverRect.height().toDp() }
                    )
                    .graphicsLayer {
                        val f = currentPlayerRevealFraction()
                        val rect = resolvePlayerArtworkHandoffRect(
                            sourceLeft = mainSourceRect.left,
                            sourceTop = mainSourceRect.top,
                            sourceWidth = mainSourceRect.width(),
                            sourceHeight = mainSourceRect.height(),
                            targetLeft = playerCoverRect.left,
                            targetTop = playerCoverRect.top,
                            targetWidth = playerCoverRect.width(),
                            targetHeight = playerCoverRect.height(),
                            fraction = f,
                        )
                        val scale = (rect.width / playerCoverRect.width()).coerceAtLeast(0.001f)
                        val localRadiusDp = lerpFloat(sourceRadius, playerRadius, f) / scale
                        translationX = rect.left - playerCoverRect.left
                        translationY = rect.top - playerCoverRect.top
                        this.scaleX = scale
                        this.scaleY = scale
                        // The real mini-player stays mounted at the collapsed anchor. Cross-fade
                        // ownership instead of replacing it on the first committed frame. This is
                        // especially important for the vinyl source, whose sleeve + record cannot
                        // be represented by the single shared bitmap node. On return the same curve
                        // hands the final frame back to the real mini-player with no shape pop.
                        alpha = (1f - kotlin.math.exp(-300f * f)).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0f, 0f)
                        clip = true
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                            localRadiusDp.dp
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
