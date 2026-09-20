package com.rawsmusic.core.ui.widget.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.PlayerLyricsArtworkAnchor
import com.rawsmusic.core.ui.widget.PlayerLyricsArtworkSource
import com.rawsmusic.core.ui.widget.PlayerLyricsArtworkVisibility
import com.rawsmusic.core.ui.widget.PlayerLyricsRect
import com.rawsmusic.core.ui.widget.PlayerLyricsScrollDirection
import com.rawsmusic.core.ui.widget.PlayerLyricsTransitionCoordinator
import com.rawsmusic.core.ui.widget.PlayerArtworkHandoffRect
import com.rawsmusic.core.ui.widget.resolvePlayerArtworkContentRect
import com.rawsmusic.core.ui.widget.resolvePlayerArtworkAspectRect
import com.rawsmusic.core.ui.widget.scalePlayerArtworkHandoffRectAroundCenter
import com.rawsmusic.core.ui.widget.flow.rememberCurrentRawFlowMode
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.flow.RawFlowTransitionBackground
import com.rawsmusic.core.ui.widget.flow.RawFlowTuningState
import com.rawsmusic.core.ui.widget.flow.RawBackgroundStyle
import com.rawsmusic.core.ui.widget.background.CustomMediaBackground
import com.rawsmusic.core.ui.widget.background.CustomMediaBackgroundState
import com.rawsmusic.core.ui.widget.flow.rememberStaticAlbumAccent
import com.rawsmusic.core.ui.widget.bitmaps.AlbumArtTiers
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkBitmapRuntime
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransition
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkPerfEvent
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkPerfTrace
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkAnimationStyle
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkItemRole
import com.rawsmusic.core.ui.widget.bitmaps.playerArtworkTitleTransform
import com.rawsmusic.core.ui.widget.bitmaps.ReferenceArtworkPageStepPx
import com.rawsmusic.core.ui.widget.bitmaps.resolveArtworkGesturePageStepPx
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.AudioInfoCapsulePreferences
import com.rawsmusic.module.data.prefs.PlayerProgressPreferences
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding

private data class StandardPlayerTone(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val icon: Color,
    val iconSoft: Color,
    val chipBackground: Color,
    val chipText: Color
)

@Composable
private fun rememberStandardPlayerTone(): StandardPlayerTone {
    val scheme = MiuixTheme.colorScheme
    val useLightForeground =
        RawFlowTuningState.style == RawBackgroundStyle.STATIC || scheme.background.luminance() < 0.5f
    return StandardPlayerTone(
        primary = if (useLightForeground) Color.White else scheme.onBackground.copy(alpha = 0.88f),
        secondary = if (useLightForeground) Color.White.copy(alpha = 0.86f) else scheme.onBackground.copy(alpha = 0.68f),
        tertiary = if (useLightForeground) Color.White.copy(alpha = 0.62f) else scheme.onBackground.copy(alpha = 0.48f),
        icon = if (useLightForeground) Color.White else scheme.onBackground.copy(alpha = 0.84f),
        iconSoft = if (useLightForeground) Color.White.copy(alpha = 0.86f) else scheme.onBackground.copy(alpha = 0.64f),
        chipBackground = if (useLightForeground) Color.Black.copy(alpha = 0.28f) else scheme.surfaceContainerHigh.copy(alpha = 0.72f),
        chipText = if (useLightForeground) Color.White.copy(alpha = 0.85f) else scheme.onSurface.copy(alpha = 0.82f)
    )
}

@Composable
fun PlayerMainPage(
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    artworkPlaybackActive: Boolean = isPlaying,
    currentPositionMs: Long,
    currentPositionState: State<Long>? = null,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean = false,
    audioSpectrum: FloatArray = FloatArray(0),
    audioSpectrumState: State<FloatArray>? = null,
    audioVisualizerForeground: Boolean = false,
    onAudioVisualizerDismiss: () -> Unit = {},
    onAudioVisualizerEnabledChange: (Boolean) -> Unit = {},
    lyricSong: Song? = null,
    lyricPositionMs: Long = 0L,
    lyricPositionState: State<Long>? = null,
    displayTranslation: Boolean = false,
    displayRoma: Boolean = false,
    @DrawableRes previousIconRes: Int,
    @DrawableRes playIconRes: Int,
    @DrawableRes pauseIconRes: Int,
    @DrawableRes nextIconRes: Int,
    @DrawableRes playModeIconRes: Int,
    @DrawableRes moreIconRes: Int,
    @DrawableRes audioQualityIconRes: Int,
    audioInfoText: String = "",
    audioInfoPlaybackChainText: String = "",
    smartTransitionVisible: Boolean = false,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onPlayModeLongPress: () -> Unit,
    onMore: () -> Unit,
    onOpenMetadata: () -> Unit = {},
    onOpenAudioEffects: () -> Unit = {},
    onOpenSpectrumAnalysis: () -> Unit = {},
    onPlaybackSpeedChange: (Float) -> Unit = {},
    realtimeSeparationEnabled: Boolean = false,
    realtimeSeparationPreparing: Boolean = false,
    realtimeSeparationStem: Int = 0,
    realtimeSeparationStrength: Float = 1f,
    realtimeSeparationStatus: String = "",
    aiPerformanceState: PlayerAiPerformanceUiState = PlayerAiPerformanceUiState(),
    onAiPerformanceModeChange: (PlayerAiPerformanceMode) -> Unit = {},
    onAiPerformanceInstrumentChange: (String) -> Unit = {},
    onAiPerformanceCancel: () -> Unit = {},
    onAiPerformanceImportMelodyModel: (String) -> Unit = {},
    onAiPerformanceImportInstrumentPack: (String) -> Unit = {},
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit = {},
    onRealtimeSeparationStemChange: (Int) -> Unit = {},
    onRealtimeSeparationStrengthChange: (Float) -> Unit = {},
    isImmersiveEnabled: Boolean = false,
    onPlayerStyleChange: (Boolean) -> Unit = {},
    onOpenLandscapePlayer: () -> Unit = {},
    onModalVisibleChange: (Boolean) -> Unit = {},
    onModalDismissActionChange: ((() -> Unit)?) -> Unit = {},
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onOpenLyric: () -> Unit,
    onArtworkLongPress: () -> Unit = {},
    queueVisible: Boolean,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onToggleQueue: () -> Unit,
    onClosePlayer: () -> Unit = {},
    onCoverSwipeUpStart: () -> Unit = {},
    onCoverSwipeUpProgress: (Float) -> Unit = {},
    onCoverSwipeUpEnd: (Boolean, Float) -> Unit = { _, _ -> },
    onCoverSwipeDownStart: () -> Unit = {},
    onCoverSwipeDownProgress: (Float) -> Unit = {},
    onCoverSwipeDownEnd: (Boolean, Float) -> Unit = { _, _ -> },
    mainPlayerSheetTransitionActive: Boolean = false,
    mainPlayerContentOffsetProvider: (() -> Float)? = null,
    mainPlayerSheetTravelPx: Float? = null,
    rootOwnsPlayerSheetDownGesture: Boolean = false,
    rootOwnsPlayerLyricUpGesture: Boolean = false,
    onArtworkAnchorChanged: (PlayerLyricsArtworkAnchor?) -> Unit = {},
    onLyricSwipeTitleBoundsChanged: (Rect?) -> Unit = {},
    artworkCornerRadiusDp: Float = STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP,
    showAlbumArt: Boolean = true,
    renderBackdrop: Boolean = true,
    contentAlpha: Float = 1f,
    sleepTimerSelection: Int = 0,
    onSleepTimerSelectionChange: (Int) -> Unit = {},
    overlaySuspended: Boolean = false,
    modifier: Modifier = Modifier
) {
    SideEffect {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PLAYER_MAIN_COMPOSE_COMMIT)
        }
    }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val visualizerStyle = LocalAudioVisualizerStyle.current
    val standardArtworkCornerRadiusDp = artworkCornerRadiusDp.coerceIn(0f, 48f)
    val baseColor = rememberCoverAccentColor(coverPath)
    val videoCoverState by com.rawsmusic.module.data.prefs.VideoCoverPreferences.state.collectAsState()
    val videoCoverSongKey = currentSong?.path.orEmpty()
    val videoCoverUri = videoCoverState.resolve(videoCoverSongKey)
    LaunchedEffect(videoCoverSongKey) {
        com.rawsmusic.module.data.prefs.VideoCoverPreferences.onSongChanged(videoCoverSongKey)
    }
    var showAudioChain by remember { mutableStateOf(false) }
    var showMoreSheetRequested by rememberSaveable { mutableStateOf(false) }
    val showMoreSheet = showMoreSheetRequested && !overlaySuspended
    var playerMoreOverlayMounted by remember { mutableStateOf(false) }
    val playerMoreSheetActive = showMoreSheet || playerMoreOverlayMounted
    var selectedSleepTimer by remember(sleepTimerSelection) { mutableStateOf(sleepTimerSelection) }
    val artworkAnimationStyleValue by PersonalizationPreferences.playerArtworkAnimationStyle.collectAsState()
    val artworkAnimationStyle = PlayerArtworkAnimationStyle.from(artworkAnimationStyleValue)
    val progressStyleValue by PlayerProgressPreferences.progressStyle.collectAsState()
    val progressStyle = ImmersiveProgressStyle.from(progressStyleValue)
    val climaxEnabled by PlayerProgressPreferences.climaxEnabled.collectAsState()
    val waveformBarCount by PlayerProgressPreferences.waveformBarCount.collectAsState()
    val waveformColorMode by PlayerProgressPreferences.waveformColorMode.collectAsState()
    val customWaveformRemainingColorInt by PlayerProgressPreferences.remainingColor.collectAsState()
    val customWaveformPlayedColorInt by PlayerProgressPreferences.playedColor.collectAsState()
    val waveformClimaxColorInt by PlayerProgressPreferences.climaxColor.collectAsState()
    val waveformRemainingColor = if (waveformColorMode == PlayerProgressPreferences.COLOR_MODE_ALBUM_ART) {
        baseColor.copy(alpha = 0.90f)
    } else {
        Color(customWaveformRemainingColorInt)
    }
    val waveformPlayedColor = if (waveformColorMode == PlayerProgressPreferences.COLOR_MODE_ALBUM_ART) {
        baseColor.copy(alpha = 0.30f)
    } else {
        Color(customWaveformPlayedColorInt)
    }
    var queueFullscreen by remember { mutableStateOf(false) }
    var songInfoLongPressGestureActive by remember { mutableStateOf(false) }
    FullCoverPredictiveBackHandler(
        enabled = queueVisible && queueFullscreen,
        onProgress = {},
        onCancelled = {},
        onCompleted = { queueFullscreen = false }
    )
    var playerRootBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    var playerArtworkBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var queueAnchorBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val fullscreenQueueTopPx = with(density) {
        rawStableStatusBarTopPadding().toPx() + 8.dp.toPx()
    }
    val initialHalfQueueTopPx = with(density) {
        (LocalConfiguration.current.screenHeightDp.dp * 0.48f).toPx()
    }
    val queueAnchorInPlayer = remember(playerRootBoundsInWindow, queueAnchorBoundsInWindow) {
        val root = playerRootBoundsInWindow
        val anchor = queueAnchorBoundsInWindow
        if (root == null || anchor == null) {
            null
        } else {
            Rect(
                left = anchor.left - root.left,
                top = anchor.top - root.top,
                right = anchor.right - root.left,
                bottom = anchor.bottom - root.top
            )
        }
    }
    val animatedQueueTopPx by animateFloatAsState(
        targetValue = if (queueFullscreen) {
            fullscreenQueueTopPx
        } else {
            queueAnchorInPlayer?.top ?: initialHalfQueueTopPx
        },
        animationSpec = tween(
            durationMillis = 750,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        ),
        label = "player-queue-top"
    )
    val queueArtworkAlpha by animateFloatAsState(
        targetValue = if (queueFullscreen) 0f else 1f,
        animationSpec = tween(
            durationMillis = 750,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        ),
        label = "player-queue-artwork-alpha"
    )
    val queueTone = rememberStandardPlayerTone()

    LaunchedEffect(queueVisible) {
        if (!queueVisible) queueFullscreen = false
    }

    fun saveSleepTimerSelection(index: Int) {
        selectedSleepTimer = index
        onSleepTimerSelectionChange(index)
    }

    fun saveArtworkAnimationStyle(style: PlayerArtworkAnimationStyle) {
        PersonalizationPreferences.playerArtworkAnimationStyleValue = style.value
    }

    fun saveProgressStyle(style: ImmersiveProgressStyle) {
        PlayerProgressPreferences.progressStyleValue = style.value
    }

    fun closeUnifiedMoreSheet() {
        showMoreSheetRequested = false
    }

    fun openUnifiedMoreSheet() {
        showMoreSheetRequested = true
    }

    LaunchedEffect(showMoreSheet, playerMoreOverlayMounted, queueFullscreen, queueVisible) {
        if (queueVisible && queueFullscreen) {
            onModalDismissActionChange { queueFullscreen = false }
            onModalVisibleChange(true)
        } else if (showMoreSheet) {
            onModalDismissActionChange(::closeUnifiedMoreSheet)
            onModalVisibleChange(true)
        } else if (playerMoreOverlayMounted) {
            // The reverse scene is still on screen, but its back action has already been consumed.
            // Do not expose the same dismiss lambda again while the card is leaving.
            onModalDismissActionChange(null)
            onModalVisibleChange(true)
        } else {
            onModalDismissActionChange(null)
            onModalVisibleChange(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            songInfoLongPressGestureActive = false
            onModalDismissActionChange(null)
            onModalVisibleChange(false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The LYRIC scene stays composed behind PLAYER so its layout/anchor can be reused for
            // the vertical scene transition. Make the visible PLAYER root a full-size pointer
            // target even in visually empty regions (notably below the progress control). This
            // does not consume child gestures; it only prevents hit testing from falling through
            // to the hidden lyric sibling's line-click/double-tap handlers.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                if (playerRootBoundsInWindow != bounds) playerRootBoundsInWindow = bounds
            }
    ) {
        if (renderBackdrop) {
            StandardPlayerBackdrop(
                coverPath = coverPath,
                artworkTransitionState = artworkTransitionState,
                motionEnabled = isPlaying ||
                    artworkTransitionState.isGestureActive ||
                    artworkTransitionState.isSettling,
            )
        }

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .rawStableStatusBarsPadding()
                    .rawStableNavigationBarsPadding()
                    .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
                    .padding(horizontal = 34.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtCard(
                    coverPath = coverPath,
                    currentSong = currentSong,
                    videoCoverUri = videoCoverUri,
                    artworkTransitionState = artworkTransitionState,
                    artworkAnimationStyle = artworkAnimationStyle,
                    title = currentSong?.displayName.orEmpty(),
                    artworkCornerRadiusDp = standardArtworkCornerRadiusDp,
                    onLongPress = onArtworkLongPress,
                    onSwipeUp = onOpenLyric,
                    onSwipeDown = onClosePlayer,
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    onSwipeDownStart = onCoverSwipeDownStart,
                    onSwipeDownProgress = onCoverSwipeDownProgress,
                    onSwipeDownEnd = onCoverSwipeDownEnd,
                    mainPlayerSheetTransitionActive = mainPlayerSheetTransitionActive,
                    mainPlayerSheetTravelPx = mainPlayerSheetTravelPx,
                    rootOwnsPlayerSheetDownGesture = rootOwnsPlayerSheetDownGesture,
                    rootOwnsPlayerLyricUpGesture = rootOwnsPlayerLyricUpGesture,
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onGestureActiveChange = { active ->
                        onModalVisibleChange(active || playerMoreSheetActive || showAudioChain)
                    },
                    onArtworkAnchorChanged = onArtworkAnchorChanged,
                    onArtworkBoundsChanged = { bounds ->
                        if (playerArtworkBoundsInRoot != bounds) playerArtworkBoundsInRoot = bounds
                    },
                    audioVisualizerEnabled =
                        audioVisualizerEnabled && visualizerStyle == AudioVisualizerStyle.Spectrum,
                    audioSpectrum = audioSpectrum,
                    audioSpectrumState = audioSpectrumState,
                    audioVisualizerForeground = audioVisualizerForeground,
                    onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                    isPlaying = isPlaying,
                    artworkPlaybackActive = artworkPlaybackActive,
                    showArt = showAlbumArt && !playerMoreSheetActive,
                    modifier = Modifier
                        .weight(0.46f)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            alpha = queueArtworkAlpha
                        }
                )
                Spacer(Modifier.width(28.dp))
                StandardPlayerBody(
                    currentSong = currentSong,
                    artworkTransitionState = artworkTransitionState,
                    artworkAnimationStyle = artworkAnimationStyle,
                    isPlaying = isPlaying,
                    audioVisualizerEnabled = audioVisualizerEnabled,
                    currentPositionMs = currentPositionMs,
                    currentPositionState = currentPositionState,
                    totalDurationMs = totalDurationMs,
                    audioSpectrum = audioSpectrum,
                    audioSpectrumState = audioSpectrumState,
                    lyricSong = lyricSong,
                    lyricPositionMs = lyricPositionMs,
                    lyricPositionState = lyricPositionState,
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
            audioInfoPlaybackChainText = audioInfoPlaybackChainText,
                    smartTransitionVisible = smartTransitionVisible,
                    onSeekStart = onSeekStart,
                    onSeekStop = onSeekStop,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPlayMode = onPlayMode,
                    onPlayModeLongPress = onPlayModeLongPress,
                    onMore = ::openUnifiedMoreSheet,
                    progressStyle = progressStyle,
                    climaxEnabled = climaxEnabled,
                    waveformBarCount = waveformBarCount,
                            waveformRemainingColor = waveformRemainingColor,
                    waveformPlayedColor = waveformPlayedColor,
                    waveformClimaxColor = Color(waveformClimaxColorInt),
                    onAudioQuality = onAudioQuality,
                    onAudioQualityLongPress = onAudioQualityLongPress,
                    onOpenLyric = onOpenLyric,
                    coverPath = coverPath,
                    queueVisible = queueVisible,
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onQueueSongClick = onQueueSongClick,
                    onClearPriorityQueue = onClearPriorityQueue,
                    onToggleQueue = {
                        if (!queueVisible) queueFullscreen = false
                        onToggleQueue()
                    },
                    onQueueBoundsChanged = { bounds ->
                        if (queueAnchorBoundsInWindow != bounds) queueAnchorBoundsInWindow = bounds
                    },
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    rootOwnsPlayerLyricUpGesture = rootOwnsPlayerLyricUpGesture,
                    onLyricSwipeTitleBoundsChanged = onLyricSwipeTitleBoundsChanged,
                    onSongInfoLongPressGestureActiveChange = { active ->
                        songInfoLongPressGestureActive = active
                        onModalVisibleChange(active || playerMoreSheetActive || showAudioChain)
                    },
                    alignLeftTitleToArtwork = false,
                    mainPlayerSheetTransitionActive = mainPlayerSheetTransitionActive,
                    mainPlayerContentOffsetProvider = mainPlayerContentOffsetProvider,
                    modifier = Modifier.weight(0.54f)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .rawStableStatusBarsPadding()
                    .rawStableNavigationBarsPadding()
                    .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
                    .padding(start = 0.dp, end = 0.dp, top = 0.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AlbumArtCard(
                    coverPath = coverPath,
                    currentSong = currentSong,
                    videoCoverUri = videoCoverUri,
                    artworkTransitionState = artworkTransitionState,
                    artworkAnimationStyle = artworkAnimationStyle,
                    title = currentSong?.displayName.orEmpty(),
                    artworkCornerRadiusDp = standardArtworkCornerRadiusDp,
                    onLongPress = onArtworkLongPress,
                    onSwipeUp = onOpenLyric,
                    onSwipeDown = onClosePlayer,
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    onSwipeDownStart = onCoverSwipeDownStart,
                    onSwipeDownProgress = onCoverSwipeDownProgress,
                    onSwipeDownEnd = onCoverSwipeDownEnd,
                    mainPlayerSheetTransitionActive = mainPlayerSheetTransitionActive,
                    mainPlayerSheetTravelPx = mainPlayerSheetTravelPx,
                    rootOwnsPlayerSheetDownGesture = rootOwnsPlayerSheetDownGesture,
                    rootOwnsPlayerLyricUpGesture = rootOwnsPlayerLyricUpGesture,
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onGestureActiveChange = { active ->
                        onModalVisibleChange(active || playerMoreSheetActive || showAudioChain)
                    },
                    onArtworkAnchorChanged = onArtworkAnchorChanged,
                    onArtworkBoundsChanged = { bounds ->
                        if (playerArtworkBoundsInRoot != bounds) playerArtworkBoundsInRoot = bounds
                    },
                    audioVisualizerEnabled =
                        audioVisualizerEnabled && visualizerStyle == AudioVisualizerStyle.Spectrum,
                    audioSpectrum = audioSpectrum,
                    audioSpectrumState = audioSpectrumState,
                    audioVisualizerForeground = audioVisualizerForeground,
                    onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                    isPlaying = isPlaying,
                    artworkPlaybackActive = artworkPlaybackActive,
                    showArt = showAlbumArt && !playerMoreSheetActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer {
                            alpha = queueArtworkAlpha
                        }
                )
                // Reference TopArtworkBounds attaches the ArtworkPager item directly to the top inset; title
                // content follows the item without an extra 20dp gap. The ArtworkImageNode's own 8dp
                // inset + 0.975 local scale creates the visible breathing room instead.
                Spacer(Modifier.height(0.dp))
                StandardPlayerBody(
                    currentSong = currentSong,
                    artworkTransitionState = artworkTransitionState,
                    artworkAnimationStyle = artworkAnimationStyle,
                    isPlaying = isPlaying,
                    audioVisualizerEnabled = audioVisualizerEnabled,
                    currentPositionMs = currentPositionMs,
                    currentPositionState = currentPositionState,
                    totalDurationMs = totalDurationMs,
                    audioSpectrum = audioSpectrum,
                    audioSpectrumState = audioSpectrumState,
                    lyricSong = lyricSong,
                    lyricPositionMs = lyricPositionMs,
                    lyricPositionState = lyricPositionState,
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
            audioInfoPlaybackChainText = audioInfoPlaybackChainText,
                    smartTransitionVisible = smartTransitionVisible,
                    onSeekStart = onSeekStart,
                    onSeekStop = onSeekStop,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPlayMode = onPlayMode,
                    onPlayModeLongPress = onPlayModeLongPress,
                    onMore = ::openUnifiedMoreSheet,
                    progressStyle = progressStyle,
                    climaxEnabled = climaxEnabled,
                    waveformBarCount = waveformBarCount,
                            waveformRemainingColor = waveformRemainingColor,
                    waveformPlayedColor = waveformPlayedColor,
                    waveformClimaxColor = Color(waveformClimaxColorInt),
                    onAudioQuality = onAudioQuality,
                    onAudioQualityLongPress = onAudioQualityLongPress,
                    onOpenLyric = onOpenLyric,
                    coverPath = coverPath,
                    queueVisible = queueVisible,
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onQueueSongClick = onQueueSongClick,
                    onClearPriorityQueue = onClearPriorityQueue,
                    onToggleQueue = {
                        if (!queueVisible) queueFullscreen = false
                        onToggleQueue()
                    },
                    onQueueBoundsChanged = { bounds ->
                        if (queueAnchorBoundsInWindow != bounds) queueAnchorBoundsInWindow = bounds
                    },
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    rootOwnsPlayerLyricUpGesture = rootOwnsPlayerLyricUpGesture,
                    onLyricSwipeTitleBoundsChanged = onLyricSwipeTitleBoundsChanged,
                    onSongInfoLongPressGestureActiveChange = { active ->
                        songInfoLongPressGestureActive = active
                        onModalVisibleChange(active || playerMoreSheetActive || showAudioChain)
                    },
                    alignLeftTitleToArtwork = true,
                    mainPlayerSheetTransitionActive = mainPlayerSheetTransitionActive,
                    mainPlayerContentOffsetProvider = mainPlayerContentOffsetProvider,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
            }
        }

        val queueBounds = queueAnchorInPlayer
        if (queueBounds != null) {
            val queueWidth = with(density) { queueBounds.width.toDp() }
            val queueHeight = with(density) {
                (queueBounds.bottom - animatedQueueTopPx).coerceAtLeast(1f).toDp()
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = queueVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(140)),
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = queueBounds.left.roundToInt(),
                            y = animatedQueueTopPx.roundToInt()
                        )
                    }
                    .width(queueWidth)
                    .height(queueHeight)
                    .clipToBounds()
            ) {
                InlinePlayerQueue(
                    songs = queueSongs,
                    currentIndex = queueCurrentIndex,
                    currentSong = currentSong,
                    currentCoverPath = coverPath,
                    colors = InlinePlayerQueueColors(
                        primaryText = queueTone.primary,
                        secondaryText = queueTone.secondary,
                        accent = queueTone.icon,
                        icon = queueTone.iconSoft,
                        currentBackground = queueTone.chipBackground,
                        artworkPlaceholder = queueTone.chipBackground.copy(alpha = 0.72f)
                    ),
                    onSongClick = onQueueSongClick,
                    onClearPriorityQueue = onClearPriorityQueue,
                    fullscreen = queueFullscreen,
                    onFullscreenChange = { queueFullscreen = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        PlayerMoreViewOverlay(
            show = showMoreSheet,
            sourceArtworkBounds = playerArtworkBoundsInRoot,
            artwork = artworkTransitionState.foregroundArtworkBitmap(),
            sourceArtworkRadiusDp = standardArtworkCornerRadiusDp,
            onDismiss = ::closeUnifiedMoreSheet,
            onMountedChange = { playerMoreOverlayMounted = it },
        ) { artworkAlpha, onArtworkBoundsChanged ->
            ImmersiveMoreSheet(
                currentSong = currentSong,
                coverPath = coverPath,
                artworkTransitionState = artworkTransitionState,
                artworkAlpha = artworkAlpha,
                onArtworkBoundsChanged = onArtworkBoundsChanged,
                progressStyle = progressStyle,
                onProgressStyleChange = ::saveProgressStyle,
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
                onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                onPlaybackSpeedChange = onPlaybackSpeedChange,
                realtimeSeparationEnabled = realtimeSeparationEnabled,
                realtimeSeparationPreparing = realtimeSeparationPreparing,
                realtimeSeparationStem = realtimeSeparationStem,
                realtimeSeparationStrength = realtimeSeparationStrength,
                realtimeSeparationStatus = realtimeSeparationStatus,
                aiPerformanceState = aiPerformanceState,
                onAiPerformanceModeChange = onAiPerformanceModeChange,
                onAiPerformanceInstrumentChange = onAiPerformanceInstrumentChange,
                onAiPerformanceCancel = onAiPerformanceCancel,
                onAiPerformanceImportMelodyModel = onAiPerformanceImportMelodyModel,
                onAiPerformanceImportInstrumentPack = onAiPerformanceImportInstrumentPack,
                onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                isImmersiveEnabled = isImmersiveEnabled,
                onPlayerStyleChange = onPlayerStyleChange,
                onOpenLandscapePlayer = onOpenLandscapePlayer,
                audioVisualizerEnabled = audioVisualizerEnabled,
                onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
                artworkAnimationStyle = artworkAnimationStyle,
                onArtworkAnimationStyleChange = ::saveArtworkAnimationStyle,
                sleepTimerSelection = selectedSleepTimer,
                onSleepTimerSelectionChange = ::saveSleepTimerSelection,
            )
        }
    }
}

@Composable
fun StandardPlayerBackdrop(
    coverPath: String?,
    @Suppress("UNUSED_PARAMETER") accent: Color = Color.Unspecified,
    artworkTransitionState: PlaybackArtworkTransitionState? = null,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    SideEffect {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PLAYER_BACKDROP_COMPOSE_COMMIT)
        }
    }
    val context = LocalContext.current
    CustomMediaBackgroundState.ensureInitialized(context)
    @Suppress("UNUSED_VARIABLE")
    val customBackgroundRevision = CustomMediaBackgroundState.revision
    if (CustomMediaBackgroundState.enabled && CustomMediaBackgroundState.showOnPlayer) {
        CustomMediaBackground(
            active = true,
            playbackActive = motionEnabled,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    // Background follows the same exact artwork identities as the foreground transition. Auto
    // Crossfade used to disable these layers and fall back to coverPath; when the committed song
    // changed that made RawFlow jump palettes in one frame. Keep the outgoing background opaque
    // underneath and smoothly fade the prepared incoming layer over it instead.
    val layers = artworkTransitionState?.backgroundLayers().orEmpty()
    val currentLayerKey = artworkTransitionState?.foregroundCurrentKey()
    val currentLayerArtwork = artworkTransitionState?.let {
        it.backgroundArtwork(it.foregroundCurrentToken())
    }
    val flowMode = rememberCurrentRawFlowMode()
    val transitionMotionActive = artworkTransitionState?.isGestureActive == true ||
        artworkTransitionState?.isSettling == true
    val effectiveMotionEnabled =
        RawFlowTuningState.style == RawBackgroundStyle.FLOW || motionEnabled || transitionMotionActive
    val currentBackgroundLayer = layers.firstOrNull {
        it.role == com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkBackgroundRole.Current
    }
    val targetBackgroundLayer = layers.firstOrNull {
        it.role == com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkBackgroundRole.Target
    }
    // FLOW keeps one renderer alive from idle -> transition -> commit. In 9A15 the renderer existed
    // only while a target layer was present, so clearing targetToken at commit replaced the whole
    // background subtree for one frame and could expose the previous/fallback palette.
    val persistentArtworkRenderer = RawFlowTuningState.style != RawBackgroundStyle.SIMPLE &&
        artworkTransitionState != null &&
        currentBackgroundLayer != null

    LaunchedEffect(
        RawFlowTuningState.style,
        currentBackgroundLayer?.key,
        targetBackgroundLayer?.key,
        transitionMotionActive,
    ) {
        PlaybackArtworkPerfTrace.backgroundIdentity(
            style = RawFlowTuningState.style.name,
            currentKey = currentBackgroundLayer?.key ?: currentLayerKey ?: coverPath,
            targetKey = targetBackgroundLayer?.key,
            moving = transitionMotionActive,
        )
    }

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        when {
            layers.isEmpty() -> {
                RawFlowBackground(
                    mode = flowMode,
                    sourceCoverKey = coverPath,
                    sourceArtwork = currentLayerArtwork,
                    motionEnabled = effectiveMotionEnabled,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    surface = com.rawsmusic.core.ui.widget.flow.RawBackgroundSurface.PLAYER
                )
            }

            persistentArtworkRenderer -> {
                // One persistent artwork renderer owns both FLOW and STATIC surfaces while idle and
                // during track changes. A target only prepares the secondary endpoint; commit
                // promotes that slot instead of replacing the whole background subtree.
                val outgoingLayer = requireNotNull(currentBackgroundLayer)
                val transitionState = requireNotNull(artworkTransitionState)
                val incomingLayer = targetBackgroundLayer
                RawFlowTransitionBackground(
                    mode = flowMode,
                    currentCoverKey = outgoingLayer.key,
                    targetCoverKey = incomingLayer?.key ?: outgoingLayer.key,
                    currentArtwork = transitionState.backgroundArtwork(outgoingLayer.token),
                    targetArtwork = incomingLayer?.let { transitionState.backgroundArtwork(it.token) }
                        ?: transitionState.backgroundArtwork(outgoingLayer.token),
                    progressProvider = {
                        if (incomingLayer == null) {
                            0f
                        } else {
                            transitionState.backgroundLayerAlpha(
                                com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkBackgroundRole.Target
                            )
                        }
                    },
                    motionEnabled = effectiveMotionEnabled,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    prefetchPreviousCoverKey = transitionState.foregroundPreviousParkedKey().takeIf { it.isNotBlank() },
                    prefetchPreviousArtwork = transitionState
                        .foregroundPreviousParkedToken()
                        .takeIf { it != 0 }
                        ?.let(transitionState::backgroundArtwork),
                    prefetchNextCoverKey = transitionState.foregroundNextParkedKey().takeIf { it.isNotBlank() },
                    prefetchNextArtwork = transitionState
                        .foregroundNextParkedToken()
                        .takeIf { it != 0 }
                        ?.let(transitionState::backgroundArtwork),
                    // Palette/source preparation is allowed while the retained pager is idle so
                    // the real neighbours stay warm. Once artwork motion owns the frame budget,
                    // consume only already-prepared cache entries; starting a new next-next
                    // extraction here produced 55-76 ms CPU bursts in the switch trace.
                    deferHeavyEndpointWork = transitionMotionActive,
                )
            }

            else -> {
                // SIMPLE/fallback surfaces keep the legacy per-layer renderer. FLOW and STATIC
                // are handled by the persistent artwork owner above.
                layers.forEach { layer ->
                    androidx.compose.runtime.key(layer.token) {
                        RawFlowBackground(
                            mode = flowMode,
                            sourceCoverKey = layer.key,
                            fallbackSourceCoverKey = currentLayerKey?.takeIf { it != layer.key },
                            sourceArtwork = artworkTransitionState?.visual(layer.token),
                            motionEnabled = effectiveMotionEnabled,
                            surface = com.rawsmusic.core.ui.widget.flow.RawBackgroundSurface.PLAYER,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .graphicsLayer {
                                    alpha = artworkTransitionState
                                        ?.backgroundLayerAlpha(layer.role)
                                        ?.coerceIn(0f, 1f)
                                        ?: 1f
                                    clip = true
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumArtCard(
    coverPath: String?,
    currentSong: AudioFile?,
    videoCoverUri: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    artworkAnimationStyle: PlayerArtworkAnimationStyle,
    title: String,
    artworkCornerRadiusDp: Float,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    onSwipeDownStart: () -> Unit,
    onSwipeDownProgress: (Float) -> Unit,
    onSwipeDownEnd: (Boolean, Float) -> Unit,
    mainPlayerSheetTransitionActive: Boolean,
    mainPlayerSheetTravelPx: Float?,
    rootOwnsPlayerSheetDownGesture: Boolean,
    rootOwnsPlayerLyricUpGesture: Boolean,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGestureActiveChange: (Boolean) -> Unit,
    onArtworkAnchorChanged: (PlayerLyricsArtworkAnchor?) -> Unit,
    onArtworkBoundsChanged: (Rect?) -> Unit,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioSpectrumState: State<FloatArray>?,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    isPlaying: Boolean,
    artworkPlaybackActive: Boolean,
    showArt: Boolean,
    modifier: Modifier = Modifier
) {
    SideEffect {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PLAYER_ART_CARD_COMPOSE_COMMIT)
        }
    }
    val resolvedAudioSpectrum = audioSpectrumState?.value ?: audioSpectrum
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var unscaledArtworkBoundsInRoot by remember { mutableStateOf<PlayerLyricsRect?>(null) }
    var cardBoundsInRoot by remember { mutableStateOf<PlayerLyricsRect?>(null) }
    val hasCover = !coverPath.isNullOrBlank()
    val hiResBadgeSettings by PersonalizationPreferences.playerHiResBadgeSettings.collectAsState()
    val hiResArtworkKeys = remember(queueSongs, currentSong) {
        buildSet<String> {
            queueSongs.asSequence()
                .filter { it.isHiRes }
                .mapNotNull { it.resolvePlaybackArtworkKey(null)?.takeIf(String::isNotBlank) }
                .forEach(::add)
            currentSong
                ?.takeIf { it.isHiRes }
                ?.resolvePlaybackArtworkKey(null)
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }

    // Gesture progress updates both the native artwork state and the scene controller. Those updates
    // recompose this page every frame. Keep the pointer-input node keyed only by the stable artwork
    // state and read changing queue/callback values through rememberUpdatedState; otherwise each
    // recomposition cancels the active drag immediately after the first movement.
    val latestQueueSongs by rememberUpdatedState(queueSongs)
    val latestQueueCurrentIndex by rememberUpdatedState(queueCurrentIndex)
    val latestOnPrevious by rememberUpdatedState(onPrevious)
    val latestOnNext by rememberUpdatedState(onNext)
    val latestOnGestureActiveChange by rememberUpdatedState(onGestureActiveChange)
    val latestOnSwipeUpStart by rememberUpdatedState(onSwipeUpStart)
    val latestOnSwipeUpProgress by rememberUpdatedState(onSwipeUpProgress)
    val latestOnSwipeUpEnd by rememberUpdatedState(onSwipeUpEnd)
    val latestOnSwipeDownStart by rememberUpdatedState(onSwipeDownStart)
    val latestOnSwipeDownProgress by rememberUpdatedState(onSwipeDownProgress)
    val latestOnSwipeDownEnd by rememberUpdatedState(onSwipeDownEnd)
    val latestMainPlayerSheetTransitionActive by rememberUpdatedState(mainPlayerSheetTransitionActive)
    val latestRootOwnsPlayerSheetDownGesture by rememberUpdatedState(rootOwnsPlayerSheetDownGesture)
    val latestRootOwnsPlayerLyricUpGesture by rememberUpdatedState(rootOwnsPlayerLyricUpGesture)
    val latestOnArtworkAnchorChanged by rememberUpdatedState(onArtworkAnchorChanged)
    val latestOnArtworkBoundsChanged by rememberUpdatedState(onArtworkBoundsChanged)
    val densityValue = LocalDensity.current.density
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val effectivePlayerSheetTravelPx = mainPlayerSheetTravelPx
        ?.takeIf { it.isFinite() && it > 0f }
        ?: (windowHeightPx - with(LocalDensity.current) { 134.dp.toPx() }).coerceAtLeast(1f)
    val systemBackEdgeGuardPx = with(LocalDensity.current) { 24.dp.toPx() }
    val artworkContentInsetPx = with(LocalDensity.current) {
        STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP.dp.toPx()
    }
    val visibleArtworkBitmap = artworkTransitionState.foregroundArtworkBitmap()
        ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    val visibleArtworkWidth = visibleArtworkBitmap?.width ?: 0
    val visibleArtworkHeight = visibleArtworkBitmap?.height ?: 0
    // The retained artwork state is the authoritative raster owner.  During renderer-confirmed
    // transport commit the public AudioFile/coverPath publication can lag the already accepted
    // artwork wrapper by a frame; never unmount the physical ArtworkPagerView just because that
    // secondary identity sample is temporarily blank.  If the backdrop can still consume the
    // current wrapper, the player endpoint must stay mounted too.
    val hasRetainedArtwork = visibleArtworkBitmap != null ||
        artworkTransitionState.foregroundCurrentKey().isNotBlank()
    val hasArtworkVisual = hasCover || hasRetainedArtwork || !videoCoverUri.isNullOrBlank()
    val playbackHolderScale by animateFloatAsState(
        targetValue = if (hasArtworkVisual && !artworkPlaybackActive) {
            STANDARD_PLAYER_ARTWORK_PAUSED_HOLDER_SCALE
        } else {
            1f
        },
        animationSpec = tween(
            durationMillis = STANDARD_PLAYER_ARTWORK_PLAY_STATE_TRANSITION_MS,
            easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
        ),
        label = "standard-player-aa-small-transition",
    )

    LaunchedEffect(unscaledArtworkBoundsInRoot, playbackHolderScale) {
        val visible = unscaledArtworkBoundsInRoot
        if (visible?.isUsable != true) {
            cardBoundsInRoot = null
            latestOnArtworkBoundsChanged(null)
            return@LaunchedEffect
        }
        val scaledRect = scalePlayerArtworkHandoffRectAroundCenter(
            rect = PlayerArtworkHandoffRect(
                left = visible.left,
                top = visible.top,
                width = visible.width,
                height = visible.height,
            ),
            scale = playbackHolderScale,
        )
        val scaled = PlayerLyricsRect(
            left = scaledRect.left,
            top = scaledRect.top,
            right = scaledRect.left + scaledRect.width,
            bottom = scaledRect.top + scaledRect.height,
        )
        cardBoundsInRoot = scaled
        latestOnArtworkBoundsChanged(
            Rect(scaled.left, scaled.top, scaled.right, scaled.bottom)
        )
    }

    LaunchedEffect(
        coverPath,
        cardBoundsInRoot,
        artworkCornerRadiusDp,
        visibleArtworkWidth,
        visibleArtworkHeight,
        playbackHolderScale,
        artworkTransitionState.isGestureActive,
        artworkTransitionState.isSettling
    ) {
        val rect = cardBoundsInRoot
        latestOnArtworkAnchorChanged(
            if (coverPath.isNullOrBlank() || rect?.isUsable != true) {
                null
            } else {
                PlayerLyricsArtworkAnchor(
                    source = PlayerLyricsArtworkSource.Player,
                    artworkKey = coverPath,
                    rect = rect,
                    visibility = PlayerLyricsArtworkVisibility.Visible,
                    visibleFraction = 1f,
                    offscreenDistancePx = 0f,
                    scrollDirection = PlayerLyricsScrollDirection.Still,
                    isScrollInProgress = false,
                    stable = !artworkTransitionState.hasPendingNavigation(),
                    cornerRadiusDp = artworkCornerRadiusDp,
                    updatedAtUptimeMs = android.os.SystemClock.uptimeMillis()
                )
            }
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged { cardSize = it }
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                val envelope = resolvePlayerArtworkContentRect(
                    outerLeft = bounds.left,
                    outerTop = bounds.top,
                    outerWidth = bounds.width,
                    outerHeight = bounds.height,
                    contentInsetPx = artworkContentInsetPx,
                    contentScale = STANDARD_PLAYER_ARTWORK_CONTENT_SCALE,
                )
                val visible = resolvePlayerArtworkAspectRect(
                    envelope = envelope,
                    sourceWidth = visibleArtworkWidth,
                    sourceHeight = visibleArtworkHeight,
                )
                // Layout owns the unscaled artwork image view rect. A separate state effect applies the
                // animated AASmall holder scale so graphics-layer-only frames still publish exact
                // shared-element geometry without forcing this layout node to resize.
                unscaledArtworkBoundsInRoot = PlayerLyricsRect(
                    left = visible.left,
                    top = visible.top,
                    right = visible.left + visible.width,
                    bottom = visible.top + visible.height,
                )
            }
            .observeArtworkLongPress(onLongPress)
            .then(
                if (hasCover) Modifier else Modifier.background(Color.White.copy(alpha = 0.11f))
            )
            .pointerInput(artworkTransitionState) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    val pointerId = down.id
                    val startPosition = down.position
                    val velocityTracker = VelocityTracker().apply {
                        addPosition(down.uptimeMillis, down.position)
                    }
                    var axis = 0 // 0 undecided, 1 horizontal track, 2 vertical scene, 3 rejected
                    var verticalDirection = 0 // -1 lyric/up, +1 close/down
                    var horizontalDirection: PlayerArtworkDirection? = null
                    var horizontalStarted = false
                    var horizontalSlopOffset = 0f
                    var horizontalInterceptionReported = false
                    var finishedNormally = false
                    val sheetGesture = latestMainPlayerSheetTransitionActive

                    // Freeze the transport neighbours for the complete pointer sequence. The
                    // player publishes requestedSong/currentSong and queue cursor in separate
                    // frames; reading those live after crossing the centre can otherwise replace
                    // the opposite lane with a neighbour from another queue generation.
                    val gestureSongs = latestQueueSongs.toList()
                    val logicalCenterKey = artworkTransitionState.gestureLogicalCenterKey()
                    val logicalCenterIndex = logicalCenterKey.takeIf { it.isNotBlank() }?.let { key ->
                        gestureSongs.indexOfFirst { it.resolvePlaybackArtworkKey(null) == key }
                    } ?: -1
                    val gestureCenterIndex = logicalCenterIndex
                        .takeIf { it in gestureSongs.indices }
                        ?: latestQueueCurrentIndex.takeIf { it in gestureSongs.indices }
                        ?: 0

                    fun queueAdjacentKey(direction: PlayerArtworkDirection): String? {
                        if (gestureSongs.isEmpty()) return null
                        val song = when (direction) {
                            PlayerArtworkDirection.Previous -> when {
                                gestureCenterIndex > 0 -> gestureSongs[gestureCenterIndex - 1]
                                gestureCenterIndex == 0 -> gestureSongs.lastOrNull()
                                else -> null
                            }
                            PlayerArtworkDirection.Next -> when {
                                gestureCenterIndex in 0 until gestureSongs.lastIndex ->
                                    gestureSongs[gestureCenterIndex + 1]
                                gestureCenterIndex == gestureSongs.lastIndex -> gestureSongs.firstOrNull()
                                else -> null
                            }
                        }
                        return song?.resolvePlaybackArtworkKey(null)
                    }

                    val gesturePreviousKey =
                        artworkTransitionState.gestureTargetKey(PlayerArtworkDirection.Previous)
                            ?.takeIf { it != logicalCenterKey }
                            ?: queueAdjacentKey(PlayerArtworkDirection.Previous)
                    val gestureNextKey =
                        artworkTransitionState.gestureTargetKey(PlayerArtworkDirection.Next)
                            ?.takeIf { it != logicalCenterKey }
                            ?: queueAdjacentKey(PlayerArtworkDirection.Next)

                    fun adjacentKey(direction: PlayerArtworkDirection): String? {
                        return when (direction) {
                            PlayerArtworkDirection.Previous -> gesturePreviousKey
                            PlayerArtworkDirection.Next -> gestureNextKey
                        }
                    }

                    fun horizontalPageStepPx(): Float = resolveArtworkGesturePageStepPx(
                        holderExtentPx = artworkTransitionState.foregroundItemExtentPx(),
                        fallbackGestureExtentPx = cardSize.width.toFloat(),
                    )

                    fun horizontalSignedPosition(dx: Float): Float {
                        val adjustedDx = dx - horizontalSlopOffset
                        return (adjustedDx / horizontalPageStepPx()).coerceIn(-1f, 1f)
                    }

                    fun finishHorizontal(dx: Float, velocityX: Float) {
                        if (!horizontalStarted) return
                        val commitDirection = artworkTransitionState.direction
                        val step = horizontalPageStepPx()
                        val adjustedDx = dx - horizontalSlopOffset
                        artworkTransitionState.endGesture(
                            progress = artworkTransitionState.ratio,
                            velocityPxPerSecond = velocityX,
                            artworkWidthPx = step,
                            physicalEndpointReached = kotlin.math.abs(adjustedDx) >= step - 0.5f,
                            gestureTravelProgress = (kotlin.math.abs(adjustedDx) / step).coerceIn(0f, 1f),
                        ) {
                            when (commitDirection) {
                                PlayerArtworkDirection.Previous -> latestOnPrevious()
                                PlayerArtworkDirection.Next -> latestOnNext()
                            }
                        }
                    }

                    fun finishVertical(dy: Float, velocityY: Float, cancelled: Boolean) {
                        if (axis != 2 || verticalDirection == 0) return
                        val height = if (verticalDirection > 0 || sheetGesture) {
                            effectivePlayerSheetTravelPx
                        } else {
                            cardSize.height.toFloat().coerceAtLeast(1f)
                        }
                        val progress = when (verticalDirection) {
                            -1 -> (-dy / height).coerceIn(0f, 1f)
                            else -> (dy / height).coerceIn(0f, 1f)
                        }
                        if (verticalDirection < 0) {
                            val commit = !cancelled &&
                                PlayerLyricsTransitionCoordinator.shouldCommit(
                                    progress = progress,
                                    velocityPxPerSecond = velocityY,
                                    density = densityValue,
                                    expectedVelocitySign = -1
                                )
                            val settleVelocity = if (cancelled) {
                                0f
                            } else {
                                PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                                    progress = progress,
                                    commit = commit,
                                    velocityPxPerSecond = velocityY,
                                    travelDistancePx = height,
                                    expectedVelocitySign = -1
                                )
                            }
                            latestOnSwipeUpEnd(commit, settleVelocity)
                        } else {
                            val commit = !cancelled && when {
                                velocityY >= 120f -> true
                                velocityY <= -120f -> false
                                else -> progress >= 0.5f
                            }
                            // The scene controller settles in normalized sheet progress. Keep
                            // the release velocity in the same unit as the cover travel so a
                            // short drag cannot be treated as an extremely fast fling.
                            val settleVelocity = if (cancelled) 0f else velocityY / height
                            latestOnSwipeDownEnd(commit, settleVelocity)
                        }
                    }

                    try {
                        while (true) {
                            // The long-press layer also observes this artwork. Read at Initial so a
                            // horizontal switch cannot be lost before this axis lock runs.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                                ?: break
                            velocityTracker.addPosition(change.uptimeMillis, change.position)

                            val dx = change.position.x - startPosition.x
                            val dy = change.position.y - startPosition.y
                            val absX = kotlin.math.abs(dx)
                            val absY = kotlin.math.abs(dy)

                            if (axis == 0 && (absX > viewConfiguration.touchSlop || absY > viewConfiguration.touchSlop)) {
                                when {
                                    absX > absY * 1.20f -> {
                                        // Keep the platform's predictive-back edge authoritative.
                                        // The artwork switch recognizer used to lock horizontal
                                        // motion at Initial pass even when the gesture began at a
                                        // system edge, making side-back unreliable.
                                        if (startPosition.x <= systemBackEdgeGuardPx ||
                                            startPosition.x >= cardSize.width - systemBackEdgeGuardPx
                                        ) {
                                            axis = 3
                                            continue
                                        }
                                        val candidate = if (dx < 0f) {
                                            PlayerArtworkDirection.Next
                                        } else {
                                            PlayerArtworkDirection.Previous
                                        }
                                        val targetKey = adjacentKey(candidate)
                                        if (artworkTransitionState.beginGesture(candidate, targetKey)) {
                                            axis = 1
                                            horizontalDirection = candidate
                                            horizontalStarted = true
                                            // Axis locking still waits for touchSlop, but after capture Reference's
                                            // direct-manipulation lane tracks the physical pointer position. Raw used
                                            // to subtract one full touchSlop forever, so the finger stayed 15-30 px
                                            // ahead of the artwork for the complete drag and felt viscous/late. The
                                            // capture frame may advance by that small already-travelled distance; after
                                            // that the holder follows the finger 1:1 in the 0.75-page coordinate.
                                            horizontalSlopOffset = 0f
                                            horizontalInterceptionReported = true
                                            // Only horizontal track switching blocks the root scene interceptor.
                                            // Vertical drags must stay available for player -> lyric/main transitions.
                                            latestOnGestureActiveChange(true)
                                        } else {
                                            axis = 3
                                        }
                                    }
                                    absY > absX * 1.20f -> {
                                        val candidateDirection = if (dy < 0f) -1 else 1
                                        // In the persistent player sheet, the parent owns
                                        // PLAYER -> MAIN and settling recapture. Keep only the
                                        // stable PLAYER upward artwork gesture here for lyrics.
                                        if (
                                            (candidateDirection < 0 && latestRootOwnsPlayerLyricUpGesture) ||
                                            (latestRootOwnsPlayerSheetDownGesture &&
                                                (candidateDirection > 0 || latestMainPlayerSheetTransitionActive))
                                        ) {
                                            axis = 3
                                        } else {
                                            axis = 2
                                            verticalDirection = candidateDirection
                                            if (verticalDirection < 0) {
                                                latestOnSwipeUpStart()
                                            } else {
                                                latestOnSwipeDownStart()
                                            }
                                        }
                                    }
                                }
                            }

                            when (axis) {
                                1 -> {
                                    artworkTransitionState.updateContinuousGesture(
                                        signedDragPosition = horizontalSignedPosition(dx),
                                        previousKey = adjacentKey(PlayerArtworkDirection.Previous),
                                        nextKey = adjacentKey(PlayerArtworkDirection.Next),
                                    )
                                    horizontalDirection = artworkTransitionState.direction
                                    change.consume()
                                }
                                2 -> {
                                    val height = if (verticalDirection > 0 || sheetGesture) {
                                        effectivePlayerSheetTravelPx
                                    } else {
                                        cardSize.height.toFloat().coerceAtLeast(1f)
                                    }
                                    val progress = when (verticalDirection) {
                                        -1 -> (-dy / height).coerceIn(0f, 1f)
                                        else -> (dy / height).coerceIn(0f, 1f)
                                    }
                                    if (verticalDirection < 0) {
                                        latestOnSwipeUpProgress(progress)
                                    } else {
                                        latestOnSwipeDownProgress(progress)
                                    }
                                    change.consume()
                                }
                            }

                            if (!change.pressed) {
                                val velocity = velocityTracker.calculateVelocity()
                                // Mark completion before callbacks: committing a track/scene can synchronously
                                // recompose and cancel this pointerInput coroutine.
                                finishedNormally = true
                                when (axis) {
                                    1 -> finishHorizontal(dx, velocity.x)
                                    2 -> finishVertical(dy, velocity.y, cancelled = false)
                                }
                                break
                            }
                        }
                    } finally {
                        // A pointer cancellation does not always deliver a regular up event.
                        if (!finishedNormally && axis == 1 && artworkTransitionState.isGestureActive) {
                            artworkTransitionState.cancelGesture()
                        } else if (!finishedNormally && axis == 2) {
                            val lastVelocity = velocityTracker.calculateVelocity()
                            val dy = 0f
                            finishVertical(dy, lastVelocity.y, cancelled = true)
                        }
                        if (horizontalInterceptionReported) latestOnGestureActiveChange(false)
                    }
                }
            }
    ) {
        if (hasArtworkVisual) {
            val behindVisible = audioVisualizerEnabled && showArt && !audioVisualizerForeground
            val foregroundVisible = audioVisualizerEnabled && showArt && audioVisualizerForeground
            val visualizerArtworkAlpha by animateFloatAsState(
                targetValue = if (behindVisible) 0.78f else 1f,
                animationSpec = tween(durationMillis = 320),
                label = "album-art-visualizer-depth"
            )
            val artworkAlpha = if (showArt) visualizerArtworkAlpha else 0f

            // Keep the reference implementation's child artwork image view parameters unchanged; play/pause scales the outer
            // visual holder so artwork, video, visualizer overlay and the 4dp shadow move together.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = playbackHolderScale
                        scaleY = playbackHolderScale
                    }
            ) {
            AlbumArtworkSpectrumOverlay(
                spectrum = resolvedAudioSpectrum,
                visible = behindVisible,
                isPlaying = isPlaying,
                layer = AudioVisualizerLayer.BehindArtwork,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP.dp)
                    .graphicsLayer {
                        scaleX = STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
                        scaleY = STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
                    }
                    .clip(RoundedCornerShape(artworkCornerRadiusDp.dp))
            )
            PlaybackArtworkTransition(
                state = artworkTransitionState,
                animationStyle = artworkAnimationStyle,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = artworkAlpha },
                contentScale = ContentScale.Fit,
                cornerRadius = artworkCornerRadiusDp.dp,
                contentInset = STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP.dp,
                contentScaleFactor = STANDARD_PLAYER_ARTWORK_CONTENT_SCALE,
                contentElevation = STANDARD_PLAYER_ARTWORK_ELEVATION_DP.dp,
                hiResBadgeSettings = hiResBadgeSettings,
                hiResArtworkKeys = hiResArtworkKeys,
            )
            val videoVisible = !videoCoverUri.isNullOrBlank() && showArt
            val videoAlpha by animateFloatAsState(
                targetValue = if (videoVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 260),
                label = "standard-video-cover-alpha"
            )
            if (!videoCoverUri.isNullOrBlank() && videoAlpha > 0f) {
                FfmpegVideoCover(
                    uri = videoCoverUri,
                    active = videoVisible,
                    cornerRadiusDp = artworkCornerRadiusDp,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP.dp)
                        .graphicsLayer {
                            alpha = videoAlpha
                            scaleX = STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
                            scaleY = STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
                        }
                )
            }
            AlbumArtworkSpectrumOverlay(
                spectrum = resolvedAudioSpectrum,
                visible = foregroundVisible,
                isPlaying = isPlaying,
                layer = AudioVisualizerLayer.Foreground,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP.dp)
                    .graphicsLayer {
                        scaleX = 1.08f * STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
                        scaleY = STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
                        clip = true
                        shape = RoundedCornerShape(artworkCornerRadiusDp.dp)
                    }
            )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.player_no_song),
                    color = rememberStandardPlayerTone().tertiary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StandardPlayerBody(
    currentSong: AudioFile?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    artworkAnimationStyle: PlayerArtworkAnimationStyle,
    isPlaying: Boolean,
    audioVisualizerEnabled: Boolean,
    currentPositionMs: Long,
    currentPositionState: State<Long>?,
    totalDurationMs: Long,
    audioSpectrum: FloatArray,
    audioSpectrumState: State<FloatArray>?,
    lyricSong: Song?,
    lyricPositionMs: Long,
    lyricPositionState: State<Long>?,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    @DrawableRes previousIconRes: Int,
    @DrawableRes playIconRes: Int,
    @DrawableRes pauseIconRes: Int,
    @DrawableRes nextIconRes: Int,
    @DrawableRes playModeIconRes: Int,
    @DrawableRes moreIconRes: Int,
    @DrawableRes audioQualityIconRes: Int,
    audioInfoText: String,
    audioInfoPlaybackChainText: String,
    smartTransitionVisible: Boolean,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onPlayModeLongPress: () -> Unit,
    onMore: () -> Unit,
    progressStyle: ImmersiveProgressStyle,
    climaxEnabled: Boolean,
    waveformBarCount: Int,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit,
    onOpenLyric: () -> Unit,
    coverPath: String?,
    queueVisible: Boolean,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onToggleQueue: () -> Unit,
    onQueueBoundsChanged: (Rect) -> Unit,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    rootOwnsPlayerLyricUpGesture: Boolean,
    onLyricSwipeTitleBoundsChanged: (Rect?) -> Unit,
    onSongInfoLongPressGestureActiveChange: (Boolean) -> Unit,
    alignLeftTitleToArtwork: Boolean = false,
    mainPlayerSheetTransitionActive: Boolean = false,
    mainPlayerContentOffsetProvider: (() -> Float)? = null,
    modifier: Modifier = Modifier
) {
    SideEffect {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PLAYER_BODY_COMPOSE_COMMIT)
        }
    }
    val tone = rememberStandardPlayerTone()
    val playerTitlePosition = LyricTextPosition.from(AppPreferences.UI.playerTitleAlignment)
    val miniLyricPosition = LyricTextPosition.from(AppPreferences.UI.miniLyricAlignment)
    // MAIN <-> PLAYER ownership is truly fused: this body keeps a clearly visible upward travel,
    // but it is rendered inside ComposePlayerContainer's dynamic PLAYER-surface clip. The player
    // surface is therefore the hard visual boundary, so information can only emerge from inside
    // that surface instead of becoming a separate screen-space layer. Artwork stays independent.
    val latestMainPlayerSheetTransitionActive by rememberUpdatedState(mainPlayerSheetTransitionActive)
    val latestMainPlayerContentOffsetProvider by rememberUpdatedState(mainPlayerContentOffsetProvider)
    val audioInfoCapsuleVisible by AudioInfoCapsulePreferences.visible.collectAsState()
    val audioInfoSlotVisible = audioInfoCapsuleVisible || smartTransitionVisible
    val footerReserve by animateDpAsState(
        targetValue = when {
            !audioInfoSlotVisible -> 0.dp
            queueVisible -> 4.dp
            else -> 26.dp
        },
        animationSpec = tween(durationMillis = 180),
        label = "standard-player-queue-footer"
    )
    Column(
        modifier = modifier.graphicsLayer {
            translationY = if (latestMainPlayerSheetTransitionActive) {
                latestMainPlayerContentOffsetProvider?.invoke()?.coerceAtLeast(0f) ?: 0f
            } else {
                0f
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !queueVisible,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(140)),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-4).dp)
                    ) {
                        // AlbumArtCard renders inside an 8dp inset and then applies a 0.975 local
                        // scale around its centre. For left-aligned titles the old 8dp body inset
                        // therefore started several pixels left of the *visible* artwork edge.
                        // Derive the remaining half-scale inset from the actual body width instead
                        // of hard-coding a phone-specific dp correction.
                        val artworkLeftCorrection = if (
                            alignLeftTitleToArtwork &&
                            playerTitlePosition == LyricTextPosition.Left
                        ) {
                            maxWidth * ((1f - STANDARD_PLAYER_ARTWORK_CONTENT_SCALE) * 0.5f)
                        } else {
                            0.dp
                        }
                        ArtworkTitleInfoPager(
                            artworkTransitionState = artworkTransitionState,
                            artworkAnimationStyle = artworkAnimationStyle,
                            currentSong = currentSong,
                            queueSongs = queueSongs,
                            queueCurrentIndex = queueCurrentIndex,
                            moreIconRes = moreIconRes,
                            onMore = onMore,
                            onSwipeUpStart = onSwipeUpStart,
                            onSwipeUpProgress = onSwipeUpProgress,
                            onSwipeUpEnd = onSwipeUpEnd,
                            rootOwnsPlayerLyricUpGesture = rootOwnsPlayerLyricUpGesture,
                            onBoundsChanged = onLyricSwipeTitleBoundsChanged,
                            onLongPressGestureActiveChange =
                                onSongInfoLongPressGestureActiveChange,
                            textPosition = playerTitlePosition,
                            leftContentInset = artworkLeftCorrection,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    StandardPlayerTimelineLane(
                        currentSong = currentSong,
                        currentPositionMs = currentPositionMs,
                        currentPositionState = currentPositionState,
                        totalDurationMs = totalDurationMs,
                        audioVisualizerEnabled = audioVisualizerEnabled,
                        audioSpectrum = audioSpectrum,
                        audioSpectrumState = audioSpectrumState,
                        lyricSong = lyricSong,
                        lyricPositionMs = lyricPositionMs,
                        lyricPositionState = lyricPositionState,
                        isPlaying = isPlaying,
                        displayTranslation = displayTranslation,
                        displayRoma = displayRoma,
                        progressStyle = progressStyle,
                        textPosition = miniLyricPosition,
                        waveformRemainingColor = waveformRemainingColor,
                        waveformPlayedColor = waveformPlayedColor,
                        waveformClimaxColor = waveformClimaxColor,
                        climaxEnabled = climaxEnabled,
                        waveformBarCount = waveformBarCount,
                        onOpenLyric = onOpenLyric,
                        onSeekStart = onSeekStart,
                        onSeekStop = onSeekStop,
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = queueVisible,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(140)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            onQueueBoundsChanged(coordinates.boundsInWindow())
                        }
                )
            }
        }
        if (audioInfoSlotVisible) {
            Spacer(Modifier.height(4.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(footerReserve)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = audioInfoSlotVisible && !queueVisible,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(140)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AudioInfoCapsule(
                        song = currentSong,
                        coverPath = coverPath,
                        text = audioInfoText,
                        playbackChainText = audioInfoPlaybackChainText,
                        smartTransitionVisible = smartTransitionVisible,
                        onClick = onAudioQuality,
                        onLongClick = onAudioQualityLongPress
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = 6.dp)
        ) {
            StandardTransportButtons(
                isPlaying = isPlaying,
                previousIconRes = previousIconRes,
                playIconRes = playIconRes,
                pauseIconRes = pauseIconRes,
                nextIconRes = nextIconRes,
                playModeIconRes = playModeIconRes,
                queueVisible = queueVisible,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPlayMode = onPlayMode,
                onQueuePlaceholder = onToggleQueue
            )
        }
    }
}

/**
 * The renderer clock is intentionally consumed only by this small lane. Keeping the State object
 * stable lets the progress/mini-lyric visuals update at the exact same cadence and with the exact
 * same millisecond values without invalidating the artwork, backdrop, title or transport tree.
 */
@Composable
private fun ColumnScope.StandardPlayerTimelineLane(
    currentSong: AudioFile?,
    currentPositionMs: Long,
    currentPositionState: State<Long>?,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioSpectrumState: State<FloatArray>?,
    lyricSong: Song?,
    lyricPositionMs: Long,
    lyricPositionState: State<Long>?,
    isPlaying: Boolean,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    progressStyle: ImmersiveProgressStyle,
    textPosition: LyricTextPosition,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    climaxEnabled: Boolean,
    waveformBarCount: Int,
    onOpenLyric: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
) {
    SideEffect {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PLAYER_TIMELINE_LANE_COMPOSE_COMMIT)
        }
    }
    val resolvedPositionMs = currentPositionState?.value ?: currentPositionMs
    val resolvedLyricPositionMs = lyricPositionState?.value ?: lyricPositionMs

    when (progressStyle) {
        ImmersiveProgressStyle.Classic -> {
            StandardMiniLyric(
                song = lyricSong,
                positionMs = resolvedLyricPositionMs,
                isPlaying = isPlaying,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                progressStyle = progressStyle,
                textPosition = textPosition,
                onClick = onOpenLyric,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
            ClassicTimelineProgress(
                currentPositionMs = resolvedPositionMs,
                totalDurationMs = totalDurationMs,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop,
                modifier = Modifier.fillMaxWidth().offset(y = 6.dp)
            )
        }
        ImmersiveProgressStyle.Waveform -> {
            StandardMiniLyric(
                song = lyricSong,
                positionMs = resolvedLyricPositionMs,
                isPlaying = isPlaying,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                progressStyle = progressStyle,
                textPosition = textPosition,
                onClick = onOpenLyric,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(4.dp))
            WindowWaveformTimelineProgress(
                currentSong = currentSong,
                currentPositionMs = resolvedPositionMs,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                waveformRemainingColor = waveformRemainingColor,
                waveformPlayedColor = waveformPlayedColor,
                waveformClimaxColor = waveformClimaxColor,
                climaxEnabled = climaxEnabled,
                waveformBarCount = waveformBarCount,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop,
                modifier = Modifier.fillMaxWidth().offset(y = 6.dp)
            )
        }
        ImmersiveProgressStyle.Seconds -> {
            StandardMiniLyric(
                song = lyricSong,
                positionMs = resolvedLyricPositionMs,
                isPlaying = isPlaying,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                progressStyle = progressStyle,
                textPosition = textPosition,
                onClick = onOpenLyric,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(3.dp))
            SecondSpectrumTimelineProgress(
                currentSong = currentSong,
                currentPositionMs = resolvedPositionMs,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                waveformRemainingColor = waveformRemainingColor,
                waveformPlayedColor = waveformPlayedColor,
                waveformClimaxColor = waveformClimaxColor,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop,
                modifier = Modifier.fillMaxWidth().offset(y = 6.dp)
            )
        }
        ImmersiveProgressStyle.MusicSpine -> {
            StandardMiniLyric(
                song = lyricSong,
                positionMs = resolvedLyricPositionMs,
                isPlaying = isPlaying,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                progressStyle = progressStyle,
                textPosition = textPosition,
                onClick = onOpenLyric,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(3.dp))
            MusicSpineTimelineProgress(
                currentPositionMs = resolvedPositionMs,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                spectrum = audioSpectrum,
                spectrumState = audioSpectrumState,
                playedColor = waveformPlayedColor,
                remainingColor = waveformRemainingColor,
                timeColor = rememberStandardPlayerTone().tertiary,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop,
                modifier = Modifier.fillMaxWidth().offset(y = 6.dp),
            )
        }
    }
}

@Composable
private fun StandardMiniLyric(
    song: Song?,
    positionMs: Long,
    isPlaying: Boolean,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    progressStyle: ImmersiveProgressStyle,
    textPosition: LyricTextPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = rememberStandardPlayerTone()
    val lyricLines = song?.lyrics.orEmpty()
    var viewportHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val verticalOverscan = 18.dp
    val hasSecondaryText = remember(lyricLines, displayTranslation, displayRoma) {
        lyricLines.any { line ->
            (displayRoma && !line.roma.isNullOrBlank()) ||
                (displayTranslation && !line.translation.isNullOrBlank()) ||
                !line.secondary.isNullOrBlank() ||
                (displayTranslation && !line.backgroundTranslation.isNullOrBlank())
        }
    }
    val maxVisibleRows = remember(
        viewportHeightPx,
        density.density,
        hasSecondaryText,
        verticalOverscan,
    ) {
        val rowHeightPx = with(density) {
            (if (hasSecondaryText) 34.dp else 25.dp).toPx()
        }.coerceAtLeast(1f)
        val overscanHeightPx = with(density) { (verticalOverscan * 2).toPx() }
        // Keep the adjacent rows composed even when only one line physically fits. A one-row
        // window replaced its only keyed child on every line change, which produced a blank
        // offscreen layer for one frame. The viewport and edge mask still decide what is visible.
        ((viewportHeightPx + overscanHeightPx) / rowHeightPx).toInt().coerceIn(3, 5)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .onSizeChanged { viewportHeightPx = it.height }
            .miniLyricShortEdgeFade()
            .padding(horizontal = 2.dp)
    ) {
        ComposeLyricView(
            song = song,
            positionMs = positionMs,
            isPlaying = isPlaying,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            textColor = tone.primary.copy(alpha = 0.94f),
            dimColor = tone.tertiary.copy(alpha = 0.72f),
            secondaryColor = tone.secondary,
            fontSizeSp = if (progressStyle == ImmersiveProgressStyle.Classic) 16 else 15,
            textPosition = textPosition,
            zoomGestureEnabled = false,
            blurEnabled = false,
            karaokeGlowEnabled = AppPreferences.UI.lyricKaraokeGlowEnabled,
            karaokeLiftEnabled = AppPreferences.UI.lyricKaraokeLiftEnabled,
            primaryFontSizeRange = 12..20,
            secondaryFontSizeRange = 10..14,
            lineHorizontalPadding = 0.dp,
            compactLineSpacing = 4.dp,
            compactTrailingPullEnabled = false,
            duetAlignmentEnabled = false,
            enforceCompactInferredLineLimit = false,
            maxPrimaryVisibleLines = maxVisibleRows,
            onLineClick = { onClick() },
            modifier = Modifier
                .fillMaxSize()
                .miniLyricVerticalOverscan(verticalOverscan)
        )
    }
}

@Composable
private fun StandardTransportButtons(
    isPlaying: Boolean,
    @DrawableRes previousIconRes: Int,
    @DrawableRes playIconRes: Int,
    @DrawableRes pauseIconRes: Int,
    @DrawableRes nextIconRes: Int,
    @DrawableRes playModeIconRes: Int,
    queueVisible: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onQueuePlaceholder: () -> Unit
) {
    val tone = rememberStandardPlayerTone()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerTransportIcon(iconRes = playModeIconRes, size = 40.dp, iconSize = 30.dp, tint = tone.tertiary, onClick = onPlayMode)
        Spacer(Modifier.width(10.dp))
        PlayerTransportIcon(iconRes = previousIconRes, size = 48.dp, iconSize = 40.dp, tint = tone.icon, onClick = onPrevious)
        Spacer(Modifier.width(16.dp))
        PlayerTransportIcon(
            iconRes = if (isPlaying) pauseIconRes else playIconRes,
            size = 56.dp,
            iconSize = 42.dp,
            tint = tone.icon,
            onClick = onPlayPause
        )
        Spacer(Modifier.width(16.dp))
        PlayerTransportIcon(iconRes = nextIconRes, size = 48.dp, iconSize = 40.dp, tint = tone.icon, onClick = onNext)
        Spacer(Modifier.width(10.dp))
        PlayerQueuePlaceholder(size = 40.dp, tint = if (queueVisible) tone.icon else tone.tertiary, selected = queueVisible, onClick = onQueuePlaceholder)
    }
}

@Composable
private fun PlayerQueuePlaceholder(
    size: androidx.compose.ui.unit.Dp,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) tint.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.ListView,
            contentDescription = stringResource(R.string.player_queue_description),
            tint = tint,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun PlayerTransportIcon(
    @DrawableRes iconRes: Int,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (iconRes != 0) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun ArtworkTitleInfoPager(
    artworkTransitionState: PlaybackArtworkTransitionState,
    artworkAnimationStyle: PlayerArtworkAnimationStyle,
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    rootOwnsPlayerLyricUpGesture: Boolean,
    onBoundsChanged: (Rect?) -> Unit,
    onLongPressGestureActiveChange: (Boolean) -> Unit,
    textPosition: LyricTextPosition,
    leftContentInset: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val currentKey = artworkTransitionState.foregroundCurrentKey()
    val targetKey = artworkTransitionState.foregroundTargetKey()
    val automaticArtworkFade = artworkTransitionState.automaticFadeOnly
    val resolvedCurrent = remember(currentKey, queueSongs, currentSong, queueCurrentIndex) {
        resolvePlayerArtworkSong(
            key = currentKey,
            queueSongs = queueSongs,
            fallback = currentSong,
            centerIndex = queueCurrentIndex,
        )
    }
    val resolvedTarget = remember(targetKey, queueSongs, currentSong, queueCurrentIndex) {
        resolvePlayerArtworkSong(
            key = targetKey,
            queueSongs = queueSongs,
            fallback = currentSong,
            centerIndex = queueCurrentIndex,
        )
    }
    var pagerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragY by remember { mutableStateOf(0f) }
    val latestOnSwipeUpStart by rememberUpdatedState(onSwipeUpStart)
    val latestOnSwipeUpProgress by rememberUpdatedState(onSwipeUpProgress)
    val latestOnSwipeUpEnd by rememberUpdatedState(onSwipeUpEnd)
    val latestRootOwnsPlayerLyricUpGesture by rememberUpdatedState(rootOwnsPlayerLyricUpGesture)
    val latestOnLongPressGestureActiveChange by
        rememberUpdatedState(onLongPressGestureActiveChange)
    var titleLongPressActive by remember { mutableStateOf(false) }
    val latestTitleLongPressActive by rememberUpdatedState(titleLongPressActive)
    val densityValue = LocalDensity.current.density

    DisposableEffect(Unit) {
        onDispose {
            onBoundsChanged(null)
            if (titleLongPressActive) {
                latestOnLongPressGestureActiveChange(false)
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { pagerSize = it }
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(coordinates.boundsInRoot())
            }
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Main
                    )
                    val pointerId = down.id
                    val start = down.position
                    val tracker = VelocityTracker().apply {
                        addPosition(down.uptimeMillis, down.position)
                    }
                    var accepted = false
                    var rejected = false
                    var finished = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dx = change.position.x - start.x
                        val dy = change.position.y - start.y

                        // Once the title long-press has fired, this complete pointer sequence belongs
                        // to clipboard interaction. Suppress both local title-page swipe and the
                        // outer player scene intercept until UP/CANCEL. The child also consumes the
                        // drag, but this parent deliberately listens with requireUnconsumed=false.
                        if (latestTitleLongPressActive) {
                            if (accepted) {
                                latestOnSwipeUpEnd(false, 0f)
                                accepted = false
                            }
                            rejected = true
                            dragY = 0f
                            change.consume()
                            if (!change.pressed) {
                                finished = true
                                break
                            }
                            continue
                        }
                        if (!accepted && !rejected &&
                            (
                                kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                    kotlin.math.abs(dy) > viewConfiguration.touchSlop
                                )
                        ) {
                            if (dy < 0f &&
                                kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f &&
                                !latestRootOwnsPlayerLyricUpGesture
                            ) {
                                accepted = true
                                latestOnSwipeUpStart()
                            } else {
                                rejected = true
                            }
                        }
                        if (accepted) {
                            dragY = dy
                            val height = pagerSize.height.toFloat().coerceAtLeast(1f)
                            val ratio = (-dy / height).coerceIn(0f, 1f)
                            latestOnSwipeUpProgress(ratio)
                            change.consume()
                        }
                        if (!change.pressed) {
                            if (accepted) {
                                val height = pagerSize.height.toFloat().coerceAtLeast(1f)
                                val ratio = (-dy / height).coerceIn(0f, 1f)
                                val velocityY = tracker.calculateVelocity().y
                                val commit = PlayerLyricsTransitionCoordinator.shouldCommit(
                                    progress = ratio,
                                    velocityPxPerSecond = velocityY,
                                    density = densityValue,
                                    expectedVelocitySign = -1
                                )
                                val settleVelocity =
                                    PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                                        progress = ratio,
                                        commit = commit,
                                        velocityPxPerSecond = velocityY,
                                        travelDistancePx = height,
                                        expectedVelocitySign = -1
                                    )
                                latestOnSwipeUpEnd(commit, settleVelocity)
                            }
                            finished = true
                            break
                        }
                    }
                    if (!finished && accepted) {
                        latestOnSwipeUpEnd(false, 0f)
                    }
                    dragY = 0f
                }
            }
    ) {
        val itemExtentPx = pagerSize.width.toFloat().coerceAtLeast(1f)

        @Composable
        fun TitleItem(
            song: AudioFile?,
            role: PlayerArtworkItemRole,
            parkedDirection: PlayerArtworkDirection? = null,
            motionPaused: Boolean,
        ) {
            if (song == null) return
            PlayerTitlePage(
                song = song,
                moreIconRes = moreIconRes,
                onMore = onMore,
                onLongPressGestureActiveChange = { active ->
                    titleLongPressActive = active
                    latestOnLongPressGestureActiveChange(active)
                },
                textPosition = textPosition,
                leftContentInset = leftContentInset,
                motionPaused = motionPaused,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // Reference transforms the complete ArtworkItemNode (art + title + line2/meta)
                        // through one ArtworkPager item extent. Reuse the artwork extent here instead of
                        // the wider title pager width, otherwise text and cover follow two tracks.
                        val sharedExtent = artworkTransitionState.foregroundItemExtentPx()
                            .takeIf { it > 1f } ?: itemExtentPx
                        val transform = if (parkedDirection != null) {
                            playerArtworkTitleTransform(
                                style = artworkAnimationStyle,
                                role = PlayerArtworkItemRole.Target,
                                direction = parkedDirection,
                                progress = 0f,
                                itemExtentPx = sharedExtent,
                                density = densityValue,
                            )
                        } else {
                            val progress = if (artworkTransitionState.automaticFadeOnly) {
                                0f
                            } else {
                                artworkTransitionState.ratio.coerceIn(0f, 1f)
                            }
                            playerArtworkTitleTransform(
                                style = artworkAnimationStyle,
                                role = role,
                                direction = artworkTransitionState.direction,
                                progress = progress,
                                itemExtentPx = sharedExtent,
                                density = densityValue,
                            )
                        }
                        translationX = transform.translationX
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                        rotationZ = transform.rotationZ
                        rotationX = transform.rotationX
                        rotationY = transform.rotationY
                        alpha = transform.alpha
                        transform.cameraDistance?.let { cameraDistance = it }
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                            pivotFractionX = transform.pivotFractionX,
                            pivotFractionY = transform.pivotFractionY
                        )
                    }
            )
        }

        // Keep the title on the same logical holder as the artwork during an automatic fade.
        // currentSong is promoted as soon as the pending track enters the renderer timeline, while
        // the artwork holder is promoted only at the handoff boundary. Reading currentSong here
        // made the new title briefly render on the old cover, then recreated the holder at commit.
        // Reference updates the ArtworkItemNode payload on the holder that owns the current artwork and
        // promotes both payloads together, so resolve the current title from the artwork key.
        val currentTitleSong = resolvedCurrent ?: currentSong
        val currentTitleKey = currentKey.ifBlank {
            currentTitleSong?.path?.takeIf { it.isNotBlank() } ?: "player-title-empty"
        }
        val currentToken = artworkTransitionState.foregroundCurrentToken()
        val targetToken = artworkTransitionState.foregroundTargetToken()
        val previousToken = artworkTransitionState.foregroundPreviousParkedToken()
        val nextToken = artworkTransitionState.foregroundNextParkedToken()
        val previousKey = artworkTransitionState.foregroundPreviousParkedKey()
        val nextKey = artworkTransitionState.foregroundNextParkedKey()
        val resolvedPrevious = remember(previousKey, queueSongs, queueCurrentIndex) {
            resolvePlayerArtworkSong(
                key = previousKey,
                queueSongs = queueSongs,
                fallback = null,
                centerIndex = queueCurrentIndex,
            )
        }
        val resolvedNext = remember(nextKey, queueSongs, queueCurrentIndex) {
            resolvePlayerArtworkSong(
                key = nextKey,
                queueSongs = queueSongs,
                fallback = null,
                centerIndex = queueCurrentIndex,
            )
        }

        data class TitleHolder(
            val token: Int,
            val key: String,
            val song: AudioFile?,
            val role: PlayerArtworkItemRole,
            val parkedDirection: PlayerArtworkDirection? = null,
        )

        val currentHolder = TitleHolder(
            token = currentToken,
            key = currentTitleKey,
            song = currentTitleSong,
            role = PlayerArtworkItemRole.Current,
        )
        val targetHolder = TitleHolder(
            token = targetToken,
            key = targetKey,
            song = resolvedTarget,
            role = PlayerArtworkItemRole.Target,
        )
        val previousHolder = TitleHolder(
            token = previousToken,
            key = previousKey,
            song = resolvedPrevious,
            role = PlayerArtworkItemRole.Target,
            parkedDirection = PlayerArtworkDirection.Previous,
        )
        val nextHolder = TitleHolder(
            token = nextToken,
            key = nextKey,
            song = resolvedNext,
            role = PlayerArtworkItemRole.Target,
            parkedDirection = PlayerArtworkDirection.Next,
        )
        val activeHolders = if (artworkTransitionState.direction == PlayerArtworkDirection.Next) {
            listOf(currentHolder, targetHolder)
        } else {
            listOf(targetHolder, currentHolder)
        }
        val titleMotionActive = targetKey.isNotBlank() ||
            artworkTransitionState.isGestureActive || artworkTransitionState.isSettling
        val holders = if (automaticArtworkFade) {
            listOf(currentHolder)
        } else {
            (listOf(previousHolder, nextHolder) + activeHolders)
                .filter { it.song != null && (it.token != 0 || it.key.isNotBlank()) }
                .distinctBy { if (it.token != 0) "token:${it.token}" else "key:${it.key}" }
        }

        // Reference has three complete ArtworkItemNodes, not three images plus a two-page text pager.
        // Keep previous/current/next title trees attached with the same artwork token so beginning
        // a swipe/button transition only changes layer properties; no title composition is inserted
        // on the first motion frame and no outgoing text tree is destroyed at commit.
        holders.forEach { holder ->
            val physicalIdentity: Any = holder.token.takeIf { it != 0 } ?: holder.key
            key(physicalIdentity) {
                TitleItem(
                    // The physical holder is keyed by token/key so it still survives motion, but
                    // its metadata must not be cached by that key. Two tracks can share one
                    // artwork holder; Reference updates ArtworkItemNode's text payload in the same bind
                    // that keeps/replaces ArtworkImageNode's wrapper. Reading the current payload here
                    // fixes stale-title/flicker after a same-cover track switch.
                    song = holder.song,
                    role = holder.role,
                    parkedDirection = holder.parkedDirection,
                    motionPaused = titleMotionActive || holder.parkedDirection != null,
                )
            }
        }

    }
}

@Composable
private fun PlayerTitlePage(
    song: AudioFile?,
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    onLongPressGestureActiveChange: (Boolean) -> Unit,
    textPosition: LyricTextPosition,
    leftContentInset: androidx.compose.ui.unit.Dp = 0.dp,
    motionPaused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tone = rememberStandardPlayerTone()
    val context = LocalContext.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Keep centered title text centered against the physical player surface rather than the
        // reduced lane left after the trailing action button. Immersive player uses the same
        // symmetric action-rail reserve; standard player needs the matching leading reserve.
        if (textPosition == LyricTextPosition.Center) {
            Spacer(Modifier.width(48.dp))
        }
        if (textPosition == LyricTextPosition.Left && leftContentInset > 0.dp) {
            Spacer(Modifier.width(leftContentInset))
        }
        ComposePlayerTitleInfo(
            title = song?.displayName ?: stringResource(R.string.player_no_song),
            artist = song?.artist?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.player_unknown_artist),
            album = song?.album?.takeIf { it.isNotBlank() }.orEmpty(),
            titleColor = tone.primary,
            artistColor = tone.secondary,
            albumColor = tone.tertiary,
            onLongClick = if (motionPaused) null else { { copySongInfoToClipboard(context, song) } },
            onLongPressGestureActiveChange = onLongPressGestureActiveChange,
            textPosition = textPosition,
            motionPaused = motionPaused,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        IconOnlyButton(iconRes = moreIconRes, onClick = onMore, enabled = !motionPaused)
    }
}

private fun resolvePlayerArtworkSong(
    key: String,
    queueSongs: List<AudioFile>,
    fallback: AudioFile?,
    centerIndex: Int = -1,
): AudioFile? {
    if (key.isBlank()) return fallback
    fallback?.takeIf { it.resolvePlaybackArtworkKey(null) == key }?.let { return it }

    // Keep three physical pager holders around the committed cursor. Normal
    // previous/current/next binding therefore never searches the complete provider. Mirror that
    // hot path before retaining the full scan as a correctness fallback for queue replacement,
    // duplicate artwork identities, or a rapid multi-page retarget.
    if (queueSongs.isNotEmpty() && centerIndex in queueSongs.indices) {
        fun candidate(index: Int): AudioFile? {
            val wrapped = when {
                index < 0 -> queueSongs.lastIndex
                index > queueSongs.lastIndex -> 0
                else -> index
            }
            return queueSongs[wrapped].takeIf { it.resolvePlaybackArtworkKey(null) == key }
        }
        candidate(centerIndex)?.let { return it }
        candidate(centerIndex - 1)?.let { return it }
        candidate(centerIndex + 1)?.let { return it }
    }
    return queueSongs.firstOrNull { it.resolvePlaybackArtworkKey(null) == key }
}

@Composable
private fun IconOnlyButton(
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = null,
        colorFilter = ColorFilter.tint(rememberStandardPlayerTone().iconSoft),
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
    )
}

@Composable
internal fun rememberBottomAccentColor(coverPath: String?): Color =
    rememberStaticAlbumAccent(coverPath)

@Composable
fun rememberCoverAccentColor(coverPath: String?): Color =
    rememberCoverAccentColorState(coverPath).color

internal data class CoverAccentColorState(
    val color: Color,
    val ready: Boolean,
)

@Composable
internal fun rememberCoverAccentColorState(coverPath: String?): CoverAccentColorState {
    val defaultColor = Color(0xFF6B5A70)
    val context = LocalContext.current
    var state by remember {
        mutableStateOf(
            CoverAccentColorState(
                color = defaultColor,
                ready = coverPath.isNullOrBlank(),
            )
        )
    }

    LaunchedEffect(coverPath) {
        val key = coverPath?.takeIf { it.isNotBlank() }
        if (key == null) {
            state = CoverAccentColorState(defaultColor, ready = true)
            return@LaunchedEffect
        }

        CoverAccentColorCache[key]?.let { cached ->
            state = CoverAccentColorState(cached, ready = true)
            return@LaunchedEffect
        }

        // Preserve the historical public behavior (neutral while loading) while exposing readiness
        // so small overlay elements can keep their previous color until the next palette is ready.
        state = CoverAccentColorState(defaultColor, ready = false)

        val bitmap = ArtworkBitmapRuntime.executePixelAnalysisBitmap(
            context = context,
            key = key,
            targetSide = 96,
        )
        val next = withContext(Dispatchers.Default) {
            bitmap?.safePaletteColor(defaultColor)
        }
        if (next != null) {
            CoverAccentColorCache[key] = next
            state = CoverAccentColorState(next, ready = true)
        } else {
            state = CoverAccentColorState(defaultColor, ready = true)
        }
    }
    return state
}

private val CoverAccentColorCache = ConcurrentHashMap<String, Color>()

private fun android.graphics.Bitmap.safePaletteColor(defaultColor: Color): Color? {
    if (isRecycled) return null
    if (
        android.os.Build.VERSION.SDK_INT >= 26 &&
        config == android.graphics.Bitmap.Config.HARDWARE
    ) return null
    return try {
        val swatch = Palette.from(this).maximumColorCount(8).generate()
        val rgb = swatch.mutedSwatch?.rgb
            ?: swatch.vibrantSwatch?.rgb
            ?: swatch.dominantSwatch?.rgb
            ?: defaultColor.toArgbCompat()
        Color(rgb).softenedForPlayer()
    } catch (_: Throwable) {
        null
    }
}

private fun Color.toArgbCompat(): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val r = (red.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val g = (green.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val b = (blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun Color.softenedForPlayer(): Color {
    val gray = (red + green + blue) / 3f
    return Color(
        red = red * 0.58f + gray * 0.18f + 0.10f,
        green = green * 0.58f + gray * 0.18f + 0.08f,
        blue = blue * 0.58f + gray * 0.22f + 0.12f,
        alpha = 1f
    )
}

internal fun audioChainText(song: AudioFile?): String {
    val audio = song
    val bits = audio?.bitsPerSample?.takeIf { it > 0 }?.let { "$it BIT" }
    val sampleRate = audio?.let { current ->
        current.sampleRate.takeIf { it > 0 }?.let {
            com.rawsmusic.core.common.utils.SampleRateNormalizer.formatKhz(
                sampleRate = it,
                codecName = current.encodingFormat,
                formatName = current.format,
                filePath = current.path,
                uppercase = true
            )
        }
    }?.takeIf { it.isNotBlank() }
    val bitRate = audio?.let { current ->
        current.bitRate.takeIf { it > 0 }?.let {
            com.rawsmusic.core.common.utils.BitrateNormalizer.formatKbps(it, current.duration, current.fileSize).uppercase()
        }
    }
    val format = audio?.format?.takeIf { it.isNotBlank() }?.uppercase()
    return listOfNotNull(bits, sampleRate, bitRate, format)
        .takeIf { it.isNotEmpty() }
        ?.joinToString("  ")
        ?: "LOCAL AUDIO"
}


@Composable
internal fun StandardMiniWaveform(
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.58f)
) {
    Canvas(modifier = modifier) {
        val bars = 26
        val gap = size.width / (bars * 2.4f)
        val barWidth = gap * 1.1f
        for (i in 0 until bars) {
            val heightFactor = 0.22f + ((i * 37) % 10) / 10f * 0.72f
            val h = size.height * heightFactor
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
