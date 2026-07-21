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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
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
import com.rawsmusic.core.ui.widget.flow.rememberCurrentRawFlowMode
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.bitmaps.AlbumArtTiers
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransition
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkAnimationStyle
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkItemRole
import com.rawsmusic.core.ui.widget.bitmaps.playerArtworkForegroundTransform
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.lyric.model.Song
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val isDark = scheme.background.luminance() < 0.5f
    return StandardPlayerTone(
        primary = if (isDark) Color.White else scheme.onBackground.copy(alpha = 0.88f),
        secondary = if (isDark) Color.White.copy(alpha = 0.86f) else scheme.onBackground.copy(alpha = 0.68f),
        tertiary = if (isDark) Color.White.copy(alpha = 0.62f) else scheme.onBackground.copy(alpha = 0.48f),
        icon = if (isDark) Color.White else scheme.onBackground.copy(alpha = 0.84f),
        iconSoft = if (isDark) Color.White.copy(alpha = 0.86f) else scheme.onBackground.copy(alpha = 0.64f),
        chipBackground = if (isDark) Color.Black.copy(alpha = 0.28f) else scheme.surfaceContainerHigh.copy(alpha = 0.72f),
        chipText = if (isDark) Color.White.copy(alpha = 0.85f) else scheme.onSurface.copy(alpha = 0.82f)
    )
}

@Composable
fun PlayerMainPage(
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean = false,
    audioSpectrum: FloatArray = FloatArray(0),
    audioVisualizerForeground: Boolean = false,
    onAudioVisualizerDismiss: () -> Unit = {},
    onAudioVisualizerEnabledChange: (Boolean) -> Unit = {},
    lyricSong: Song? = null,
    lyricPositionMs: Long = 0L,
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
    onArtworkAnchorChanged: (PlayerLyricsArtworkAnchor?) -> Unit = {},
    showAlbumArt: Boolean = true,
    renderBackdrop: Boolean = true,
    contentAlpha: Float = 1f,
    sleepTimerSelection: Int = 0,
    onSleepTimerSelectionChange: (Int) -> Unit = {},
    overlaySuspended: Boolean = false,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val baseColor = rememberCoverAccentColor(coverPath)
    var showAudioChain by remember { mutableStateOf(false) }
    var showMoreSheetRequested by rememberSaveable { mutableStateOf(false) }
    val showMoreSheet = showMoreSheetRequested && !overlaySuspended
    var selectedSleepTimer by remember(sleepTimerSelection) { mutableStateOf(sleepTimerSelection) }
    var artworkAnimationStyle by remember {
        mutableStateOf(PlayerArtworkAnimationStyle.from(AppPreferences.UI.playerArtworkAnimationStyle))
    }
    var progressStyle by remember { mutableStateOf(ImmersiveProgressStyle.from(AppPreferences.UI.immersiveProgressStyle)) }
    var climaxEnabled by remember { mutableStateOf(AppPreferences.UI.immersiveClimaxEnabled) }
    var waveformDebugPanel by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformDebugPanel) }
    var waveformRemainingColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformRemainingColor) }
    var waveformPlayedColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformPlayedColor) }
    var waveformClimaxColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformClimaxColor) }
    var queueFullscreen by remember { mutableStateOf(false) }
    var playerRootBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    var queueAnchorBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val fullscreenQueueTopPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() + 8.dp.toPx()
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
            queueAnchorInPlayer?.top ?: fullscreenQueueTopPx
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
        artworkAnimationStyle = style
        AppPreferences.UI.playerArtworkAnimationStyle = style.value
    }

    fun saveProgressStyle(style: ImmersiveProgressStyle) {
        progressStyle = style
        AppPreferences.UI.immersiveProgressStyle = style.value
    }

    fun saveClimaxEnabled(enabled: Boolean) {
        climaxEnabled = enabled
        AppPreferences.UI.immersiveClimaxEnabled = enabled
    }

    fun saveWaveformDebugPanel(enabled: Boolean) {
        waveformDebugPanel = enabled
        AppPreferences.UI.immersiveWaveformDebugPanel = enabled
    }

    fun saveWaveformRemainingColor(color: Color) {
        waveformRemainingColorInt = color.toArgbCompat()
        AppPreferences.UI.immersiveWaveformRemainingColor = waveformRemainingColorInt
    }

    fun saveWaveformPlayedColor(color: Color) {
        waveformPlayedColorInt = color.toArgbCompat()
        AppPreferences.UI.immersiveWaveformPlayedColor = waveformPlayedColorInt
    }

    fun saveWaveformClimaxColor(color: Color) {
        waveformClimaxColorInt = color.toArgbCompat()
        AppPreferences.UI.immersiveWaveformClimaxColor = waveformClimaxColorInt
    }

    fun closeUnifiedMoreSheet() {
        showMoreSheetRequested = false
    }

    fun openUnifiedMoreSheet() {
        showMoreSheetRequested = true
    }

    LaunchedEffect(showMoreSheet, queueFullscreen, queueVisible) {
        if (queueVisible && queueFullscreen) {
            onModalDismissActionChange { queueFullscreen = false }
            onModalVisibleChange(true)
        } else if (showMoreSheet) {
            onModalDismissActionChange(::closeUnifiedMoreSheet)
            onModalVisibleChange(true)
        } else {
            onModalDismissActionChange(null)
            onModalVisibleChange(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onModalDismissActionChange(null)
            onModalVisibleChange(false)
        }
    }

    LaunchedEffect(coverPath) {
        if (!coverPath.isNullOrBlank()) {
            BitmapProvider.warmPlaybackArt(coverPath)
            BitmapProvider.warmFullCoverArt(coverPath)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                if (playerRootBoundsInWindow != bounds) playerRootBoundsInWindow = bounds
            }
    ) {
        if (renderBackdrop) {
            StandardPlayerBackdrop(
                coverPath = coverPath,
                accent = baseColor,
                artworkTransitionState = artworkTransitionState
            )
        }

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
                    .padding(horizontal = 34.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtCard(
                    coverPath = coverPath,
                    artworkTransitionState = artworkTransitionState,
                    artworkAnimationStyle = artworkAnimationStyle,
                    title = currentSong?.displayName.orEmpty(),
                    onLongPress = onArtworkLongPress,
                    onSwipeUp = onOpenLyric,
                    onSwipeDown = onClosePlayer,
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    onSwipeDownStart = onCoverSwipeDownStart,
                    onSwipeDownProgress = onCoverSwipeDownProgress,
                    onSwipeDownEnd = onCoverSwipeDownEnd,
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onGestureActiveChange = { active ->
                        onModalVisibleChange(active || showMoreSheet || showAudioChain)
                    },
                    onArtworkAnchorChanged = onArtworkAnchorChanged,
                    audioVisualizerEnabled = audioVisualizerEnabled,
                    audioSpectrum = audioSpectrum,
                    audioVisualizerForeground = audioVisualizerForeground,
                    onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                    isPlaying = isPlaying,
                    showArt = showAlbumArt,
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
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
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
                    onMore = ::openUnifiedMoreSheet,
                    progressStyle = progressStyle,
                    climaxEnabled = climaxEnabled,
                    waveformDebugPanel = waveformDebugPanel,
                    waveformRemainingColor = Color(waveformRemainingColorInt),
                    waveformPlayedColor = Color(waveformPlayedColorInt),
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
                    modifier = Modifier.weight(0.54f)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
                    .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AlbumArtCard(
                    coverPath = coverPath,
                    artworkTransitionState = artworkTransitionState,
                    artworkAnimationStyle = artworkAnimationStyle,
                    title = currentSong?.displayName.orEmpty(),
                    onLongPress = onArtworkLongPress,
                    onSwipeUp = onOpenLyric,
                    onSwipeDown = onClosePlayer,
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    onSwipeDownStart = onCoverSwipeDownStart,
                    onSwipeDownProgress = onCoverSwipeDownProgress,
                    onSwipeDownEnd = onCoverSwipeDownEnd,
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onGestureActiveChange = { active ->
                        onModalVisibleChange(active || showMoreSheet || showAudioChain)
                    },
                    onArtworkAnchorChanged = onArtworkAnchorChanged,
                    audioVisualizerEnabled = audioVisualizerEnabled,
                    audioSpectrum = audioSpectrum,
                    audioVisualizerForeground = audioVisualizerForeground,
                    onAudioVisualizerDismiss = onAudioVisualizerDismiss,
                    isPlaying = isPlaying,
                    showArt = showAlbumArt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.02f)
                        .graphicsLayer {
                            alpha = queueArtworkAlpha
                        }
                )
                Spacer(Modifier.height(20.dp))
                StandardPlayerBody(
                    currentSong = currentSong,
                    artworkTransitionState = artworkTransitionState,
                    artworkAnimationStyle = artworkAnimationStyle,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
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
                    onMore = ::openUnifiedMoreSheet,
                    progressStyle = progressStyle,
                    climaxEnabled = climaxEnabled,
                    waveformDebugPanel = waveformDebugPanel,
                    waveformRemainingColor = Color(waveformRemainingColorInt),
                    waveformPlayedColor = Color(waveformPlayedColorInt),
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
                    modifier = Modifier.fillMaxWidth()
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

        RawMiuixOverlayDialog(
            show = showMoreSheet,
            onDismissRequest = ::closeUnifiedMoreSheet,
            renderInRootScaffold = true
        ) {
            ImmersiveMoreSheet(
                currentSong = currentSong,
                coverPath = coverPath,
                progressStyle = progressStyle,
                climaxEnabled = climaxEnabled,
                waveformDebugPanel = waveformDebugPanel,
                waveformRemainingColor = Color(waveformRemainingColorInt),
                waveformPlayedColor = Color(waveformPlayedColorInt),
                waveformClimaxColor = Color(waveformClimaxColorInt),
                onProgressStyleChange = ::saveProgressStyle,
                onClimaxEnabledChange = ::saveClimaxEnabled,
                onWaveformDebugPanelChange = ::saveWaveformDebugPanel,
                onWaveformRemainingColorChange = ::saveWaveformRemainingColor,
                onWaveformPlayedColorChange = ::saveWaveformPlayedColor,
                onWaveformClimaxColorChange = ::saveWaveformClimaxColor,
                onOpenMetadata = onOpenMetadata,
                onOpenAudioEffects = onOpenAudioEffects,
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
    accent: Color,
    artworkTransitionState: PlaybackArtworkTransitionState? = null,
    modifier: Modifier = Modifier
) {
    // Keep the background fixed while cross-blending the outgoing and incoming artwork lanes.
    // Native draw order is dominant-first, so each RawFlow layer is stacked in that order.
    val layers = artworkTransitionState?.backgroundLayers().orEmpty()
    val flowMode = rememberCurrentRawFlowMode()
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        if (layers.isEmpty()) {
            RawFlowBackground(
                mode = flowMode,
                sourceCoverKey = coverPath,
                modifier = Modifier.fillMaxSize().clipToBounds()
            )
        } else {
            layers.forEach { layer ->
                androidx.compose.runtime.key(layer.token) {
                    RawFlowBackground(
                        mode = flowMode,
                        sourceCoverKey = layer.key,
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .graphicsLayer {
                                alpha = layer.alpha.coerceIn(0f, 1f)
                                clip = true
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumArtCard(
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    artworkAnimationStyle: PlayerArtworkAnimationStyle,
    title: String,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    onSwipeDownStart: () -> Unit,
    onSwipeDownProgress: (Float) -> Unit,
    onSwipeDownEnd: (Boolean, Float) -> Unit,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGestureActiveChange: (Boolean) -> Unit,
    onArtworkAnchorChanged: (PlayerLyricsArtworkAnchor?) -> Unit,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    isPlaying: Boolean,
    showArt: Boolean,
    modifier: Modifier = Modifier
) {
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var cardBoundsInRoot by remember { mutableStateOf<PlayerLyricsRect?>(null) }
    val hasCover = !coverPath.isNullOrBlank()

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
    val latestOnArtworkAnchorChanged by rememberUpdatedState(onArtworkAnchorChanged)
    val densityValue = LocalDensity.current.density

    LaunchedEffect(
        coverPath,
        cardBoundsInRoot,
        artworkTransitionState.isGestureActive,
        artworkTransitionState.isSettling,
        artworkTransitionState.ratio
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
                    cornerRadiusDp = STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP,
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
                cardBoundsInRoot = PlayerLyricsRect(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom
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
                        pass = PointerEventPass.Main
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
                    var horizontalInterceptionReported = false
                    var finishedNormally = false

                    fun adjacentKey(direction: PlayerArtworkDirection): String? {
                        val songs = latestQueueSongs
                        val index = latestQueueCurrentIndex
                        if (songs.isEmpty()) return null
                        val song = when (direction) {
                            PlayerArtworkDirection.Previous -> when {
                                index > 0 -> songs[index - 1]
                                index == 0 -> songs.lastOrNull()
                                else -> null
                            }
                            PlayerArtworkDirection.Next -> when {
                                index in 0 until songs.lastIndex -> songs[index + 1]
                                index == songs.lastIndex -> songs.firstOrNull()
                                else -> null
                            }
                        }
                        return song?.resolvePlaybackArtworkKey(null)
                    }

                    fun horizontalProgress(dx: Float): Float {
                        val width = cardSize.width.toFloat().coerceAtLeast(1f)
                        return when (horizontalDirection) {
                            PlayerArtworkDirection.Next -> (-dx / width).coerceIn(0f, 1f)
                            PlayerArtworkDirection.Previous -> (dx / width).coerceIn(0f, 1f)
                            null -> 0f
                        }
                    }

                    fun finishHorizontal(dx: Float, velocityX: Float) {
                        if (!horizontalStarted) return
                        val width = cardSize.width.toFloat().coerceAtLeast(1f)
                        artworkTransitionState.endGesture(
                            progress = horizontalProgress(dx),
                            velocityFractionPerSecond = velocityX / width
                        ) {
                            when (horizontalDirection) {
                                PlayerArtworkDirection.Previous -> latestOnPrevious()
                                PlayerArtworkDirection.Next -> latestOnNext()
                                null -> Unit
                            }
                        }
                    }

                    fun finishVertical(dy: Float, velocityY: Float, cancelled: Boolean) {
                        if (axis != 2 || verticalDirection == 0) return
                        val height = cardSize.height.toFloat().coerceAtLeast(1f)
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
                            val commit = !cancelled && (
                                progress >= 0.30f || velocityY >= 900f
                            )
                            latestOnSwipeDownEnd(commit, velocityY)
                        }
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
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
                                        val candidate = if (dx < 0f) {
                                            PlayerArtworkDirection.Next
                                        } else {
                                            PlayerArtworkDirection.Previous
                                        }
                                        val targetKey = artworkTransitionState.gestureTargetKey(candidate)
                                            ?: adjacentKey(candidate)
                                        if (artworkTransitionState.beginGesture(candidate, targetKey)) {
                                            axis = 1
                                            horizontalDirection = candidate
                                            horizontalStarted = true
                                            horizontalInterceptionReported = true
                                            // Only horizontal track switching blocks the root scene interceptor.
                                            // Vertical drags must stay available for player -> lyric/main transitions.
                                            latestOnGestureActiveChange(true)
                                        } else {
                                            axis = 3
                                        }
                                    }
                                    absY > absX * 1.20f -> {
                                        axis = 2
                                        verticalDirection = if (dy < 0f) -1 else 1
                                        if (verticalDirection < 0) latestOnSwipeUpStart() else latestOnSwipeDownStart()
                                    }
                                }
                            }

                            when (axis) {
                                1 -> {
                                    artworkTransitionState.updateGesture(horizontalProgress(dx))
                                    change.consume()
                                }
                                2 -> {
                                    val height = cardSize.height.toFloat().coerceAtLeast(1f)
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
        if (hasCover) {
            val behindVisible = audioVisualizerEnabled && showArt && !audioVisualizerForeground
            val foregroundVisible = audioVisualizerEnabled && showArt && audioVisualizerForeground
            val visualizerArtworkAlpha by animateFloatAsState(
                targetValue = if (behindVisible) 0.78f else 1f,
                animationSpec = tween(durationMillis = 320),
                label = "album-art-visualizer-depth"
            )
            val artworkAlpha = if (showArt) visualizerArtworkAlpha else 0f

            AlbumArtworkSpectrumOverlay(
                spectrum = audioSpectrum,
                visible = behindVisible,
                isPlaying = isPlaying,
                layer = AudioVisualizerLayer.BehindArtwork,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP.dp))
            )
            PlaybackArtworkTransition(
                state = artworkTransitionState,
                animationStyle = artworkAnimationStyle,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = artworkAlpha },
                contentScale = ContentScale.Crop,
                cornerRadius = STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP.dp
            )
            if (foregroundVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP.dp))
                        .background(Color.Black.copy(alpha = 0.24f))
                )
            }
            AlbumArtworkSpectrumOverlay(
                spectrum = audioSpectrum,
                visible = foregroundVisible,
                isPlaying = isPlaying,
                layer = AudioVisualizerLayer.Foreground,
                showControls = foregroundVisible,
                onDismiss = onAudioVisualizerDismiss,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.08f
                        clip = true
                        shape = RoundedCornerShape(STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP.dp)
                    }
            )
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
    currentPositionMs: Long,
    totalDurationMs: Long,
    lyricSong: Song?,
    lyricPositionMs: Long,
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
    waveformDebugPanel: Boolean,
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
    modifier: Modifier = Modifier
) {
    val tone = rememberStandardPlayerTone()
    val footerReserve by animateDpAsState(
        targetValue = if (queueVisible) 4.dp else 26.dp,
        animationSpec = tween(durationMillis = 180),
        label = "standard-player-queue-footer"
    )
    Column(modifier = modifier) {
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
                Column(modifier = Modifier.fillMaxSize()) {
                    ArtworkTitleInfoPager(
                        artworkTransitionState = artworkTransitionState,
                        artworkAnimationStyle = artworkAnimationStyle,
                        currentSong = currentSong,
                        queueSongs = queueSongs,
                        moreIconRes = moreIconRes,
                        onMore = onMore,
                        onSwipeUpStart = onSwipeUpStart,
                        onSwipeUpProgress = onSwipeUpProgress,
                        onSwipeUpEnd = onSwipeUpEnd,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    when (progressStyle) {
                        ImmersiveProgressStyle.Classic -> {
                            StandardMiniLyric(
                                song = lyricSong,
                                positionMs = lyricPositionMs,
                                displayTranslation = displayTranslation,
                                displayRoma = displayRoma,
                                progressStyle = progressStyle,
                                onClick = onOpenLyric,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.height(8.dp))
                            ClassicTimelineProgress(
                                currentPositionMs = currentPositionMs,
                                totalDurationMs = totalDurationMs,
                                onSeekStart = onSeekStart,
                                onSeekStop = onSeekStop,
                                modifier = Modifier.fillMaxWidth().offset(y = 6.dp)
                            )
                        }
                        ImmersiveProgressStyle.Waveform -> {
                            StandardMiniLyric(
                                song = lyricSong,
                                positionMs = lyricPositionMs,
                                displayTranslation = displayTranslation,
                                displayRoma = displayRoma,
                                progressStyle = progressStyle,
                                onClick = onOpenLyric,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.height(4.dp))
                            WindowWaveformTimelineProgress(
                                currentSong = currentSong,
                                currentPositionMs = currentPositionMs,
                                totalDurationMs = totalDurationMs,
                                isPlaying = isPlaying,
                                waveformRemainingColor = waveformRemainingColor,
                                waveformPlayedColor = waveformPlayedColor,
                                waveformClimaxColor = waveformClimaxColor,
                                climaxEnabled = climaxEnabled,
                                showDebugPanel = waveformDebugPanel,
                                onSeekStart = onSeekStart,
                                onSeekStop = onSeekStop,
                                modifier = Modifier.fillMaxWidth().offset(y = 6.dp)
                            )
                        }
                        ImmersiveProgressStyle.Seconds -> {
                            StandardMiniLyric(
                                song = lyricSong,
                                positionMs = lyricPositionMs,
                                displayTranslation = displayTranslation,
                                displayRoma = displayRoma,
                                progressStyle = progressStyle,
                                onClick = onOpenLyric,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.height(3.dp))
                            SecondSpectrumTimelineProgress(
                                currentSong = currentSong,
                                currentPositionMs = currentPositionMs,
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
                    }
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
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(footerReserve)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !queueVisible,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(140)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    QualityPill(
                        song = currentSong,
                        text = audioInfoText,
                        onClick = onAudioQuality,
                        onLongClick = onAudioQualityLongPress
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().offset(y = 6.dp)) {
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

@Composable
private fun StandardMiniLyric(
    song: Song?,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    progressStyle: ImmersiveProgressStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = rememberStandardPlayerTone()
    val lyricLines = song?.lyrics.orEmpty()
    val lyricSource = remember(lyricLines, displayTranslation, displayRoma) {
        prepareMiniLyricSource(lyricLines, displayTranslation, displayRoma)
    }
    var viewportHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val hasSecondaryText = remember(lyricSource) {
        lyricSource.displayLines.any { it.secondaryParts.isNotEmpty() }
    }
    val maxVisibleRows = remember(viewportHeightPx, density.density, hasSecondaryText) {
        val rowHeightPx = with(density) {
            (if (hasSecondaryText) 34.dp else 25.dp).toPx()
        }.coerceAtLeast(1f)
        (viewportHeightPx / rowHeightPx).toInt().coerceIn(1, 5)
    }
    val preview = remember(lyricSource, positionMs, maxVisibleRows) {
        resolveMiniLyricPresentation(
            source = lyricSource,
            positionMs = positionMs,
            maxPrimaryRows = maxVisibleRows
        )
    }

    AnimatedContent(
        targetState = preview,
        transitionSpec = {
            (slideInVertically(tween(220)) { it / 3 } + fadeIn(tween(180))) togetherWith
                (slideOutVertically(tween(180)) { -it / 3 } + fadeOut(tween(140)))
        },
        contentKey = { state ->
            val line = state.currentLine ?: state.lines.firstOrNull { it.index == state.anchorLineIndex }
            line?.let { Triple(it.index, it.beginMs, it.primary) } ?: Triple(-1, -1L, "")
        },
        label = "standard-mini-lyric",
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .onSizeChanged { viewportHeightPx = it.height }
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) { state ->
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            if (state.lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.player_no_lyric),
                    color = tone.tertiary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                state.lines.forEachIndexed { index, line ->
                    Column {
                        Text(
                            text = line.primary,
                            color = if (line.active) tone.primary.copy(alpha = 0.94f) else tone.tertiary.copy(alpha = 0.72f),
                            fontSize = if (line.active && progressStyle == ImmersiveProgressStyle.Classic) 16.sp else if (line.active) 15.sp else 13.sp,
                            fontWeight = if (line.active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = if (line.allowWrap) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        line.secondaryText.takeIf(String::isNotBlank)?.let { secondary ->
                            Spacer(Modifier.height(1.dp))
                            Text(
                                text = secondary,
                                color = if (line.active) tone.secondary else tone.tertiary.copy(alpha = 0.58f),
                                fontSize = if (line.active) 12.sp else 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (index != state.lines.lastIndex) Spacer(Modifier.height(4.dp))
                }
            }
        }
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
            contentDescription = "播放队列",
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
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE")
    val redraw = artworkTransitionState.frameVersion
    val currentKey = artworkTransitionState.foregroundCurrentKey()
    val targetKey = artworkTransitionState.foregroundTargetKey()
    val visualTransitionActive = targetKey.isNotBlank()
    val resolvedCurrent = remember(currentKey, queueSongs, currentSong) {
        resolvePlayerArtworkSong(currentKey, queueSongs, currentSong)
    }
    val resolvedTarget = remember(targetKey, queueSongs, currentSong) {
        resolvePlayerArtworkSong(targetKey, queueSongs, currentSong)
    }
    val progress = artworkTransitionState.ratio.coerceIn(0f, 1f)
    var pagerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragY by remember { mutableStateOf(0f) }
    val latestOnSwipeUpStart by rememberUpdatedState(onSwipeUpStart)
    val latestOnSwipeUpProgress by rememberUpdatedState(onSwipeUpProgress)
    val latestOnSwipeUpEnd by rememberUpdatedState(onSwipeUpEnd)
    val densityValue = LocalDensity.current.density

    Box(
        modifier = modifier
            .onSizeChanged { pagerSize = it }
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
                        if (!accepted && !rejected &&
                            (
                                kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                    kotlin.math.abs(dy) > viewConfiguration.touchSlop
                                )
                        ) {
                            if (dy < 0f &&
                                kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f
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
        val currentTransform = playerArtworkForegroundTransform(
            style = artworkAnimationStyle,
            role = PlayerArtworkItemRole.Current,
            direction = artworkTransitionState.direction,
            progress = progress,
            itemExtentPx = itemExtentPx
        )
        val targetTransform = playerArtworkForegroundTransform(
            style = artworkAnimationStyle,
            role = PlayerArtworkItemRole.Target,
            direction = artworkTransitionState.direction,
            progress = progress,
            itemExtentPx = itemExtentPx
        )

        @Composable
        fun TitleItem(song: AudioFile?, transform: com.rawsmusic.core.ui.widget.bitmaps.ForegroundItemTransform) {
            if (song == null || transform.alpha <= 0.001f) return
            PlayerTitlePage(
                song = song,
                moreIconRes = moreIconRes,
                onMore = onMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // Title, artist and the item-local more button share the same item transform
                        // as the corresponding artwork card.
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

        val currentTitleSong = resolvedCurrent ?: currentSong
        val currentTitleKey = currentKey.ifBlank {
            currentTitleSong?.path?.takeIf { it.isNotBlank() } ?: "player-title-empty"
        }
        val orderedSlots = when {
            !visualTransitionActive -> listOf(
                Triple(currentTitleKey, currentTitleSong, currentTransform)
            )
            progress < 0.5f -> listOf(
                Triple(targetKey, resolvedTarget, targetTransform),
                Triple(currentTitleKey, currentTitleSong, currentTransform)
            )
            else -> listOf(
                Triple(currentTitleKey, currentTitleSong, currentTransform),
                Triple(targetKey, resolvedTarget, targetTransform)
            )
        }

        // Keep each song's title node alive while its artwork slot is promoted from target to
        // current. Replacing the transition branch with a separate static node reset the marquee
        // Canvas for one frame at settle completion, which appeared as a title flash.
        orderedSlots.forEach { (slotKey, song, transform) ->
            key(slotKey) {
                TitleItem(song, transform)
            }
        }
    }
}

@Composable
private fun PlayerTitlePage(
    song: AudioFile?,
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = rememberStandardPlayerTone()
    val context = LocalContext.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ComposePlayerTitleInfo(
            title = song?.displayName ?: stringResource(R.string.player_no_song),
            artist = song?.artist?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.player_unknown_artist),
            album = song?.album?.takeIf { it.isNotBlank() }.orEmpty(),
            titleColor = tone.primary,
            artistColor = tone.secondary,
            albumColor = tone.tertiary,
            onLongClick = { copySongInfoToClipboard(context, song) },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        IconOnlyButton(iconRes = moreIconRes, onClick = onMore)
    }
}

private fun resolvePlayerArtworkSong(
    key: String,
    queueSongs: List<AudioFile>,
    fallback: AudioFile?
): AudioFile? {
    if (key.isBlank()) return fallback
    fallback?.takeIf { it.resolvePlaybackArtworkKey(null) == key }?.let { return it }
    return queueSongs.firstOrNull { it.resolvePlaybackArtworkKey(null) == key }
}

@Composable
private fun IconOnlyButton(
    @DrawableRes iconRes: Int,
    onClick: () -> Unit
) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = null,
        colorFilter = ColorFilter.tint(rememberStandardPlayerTone().iconSoft),
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp)
    )
}

@Composable
private fun QualityPill(song: AudioFile?, text: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val capsuleColor = if (isDark) {
        Color(0xFFC2C2C6).copy(alpha = 0.86f)
    } else {
        Color(0xFF59595E).copy(alpha = 0.84f)
    }
    val capsuleTextColor = if (isDark) Color(0xFF171719) else Color.White.copy(alpha = 0.94f)
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pill_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.6f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "pill_alpha"
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(RoundedCornerShape(50))
            .background(capsuleColor)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .height(18.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.ifBlank { audioChainText(song) },
            color = capsuleTextColor,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun AudioChainCard(song: AudioFile?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(rememberStandardPlayerTone().chipBackground)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(audioChainText(song), color = rememberStandardPlayerTone().primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            text = listOfNotNull(
                song?.format?.takeIf { it.isNotBlank() },
                song?.channelCount?.takeIf { it > 0 }?.let { "${it}ch" },
                song?.bitRate?.takeIf { it > 0 }?.let {
                    com.rawsmusic.core.common.utils.BitrateNormalizer.formatKbps(it, song.duration, song.fileSize)
                }
            ).joinToString("  "),
            color = rememberStandardPlayerTone().secondary,
            fontSize = 12.sp
        )
    }
}

@Composable
internal fun rememberCoverAccentColor(coverPath: String?): Color {
    val defaultColor = Color(0xFF6B5A70)
    var color by remember { mutableStateOf(defaultColor) }

    LaunchedEffect(coverPath) {
        val key = coverPath?.takeIf { it.isNotBlank() }
        if (key == null) {
            color = defaultColor
            return@LaunchedEffect
        }

        CoverAccentColorCache[key]?.let { cached ->
            color = cached
            return@LaunchedEffect
        }

        // 不把上一首的背景色缓存到新歌上。先退回稳定默认色，等当前封面已预热后再取色。
        color = defaultColor

        repeat(3) { attempt ->
            val next = withContext(Dispatchers.IO) {
                val cached = BitmapProvider.peekThumbnail(key, 192, 192)
                    ?: BitmapProvider.peekThumbnail(key, 512, 512)
                    ?: BitmapProvider.peek(key, 512, 512)
                    ?: BitmapProvider.peekAny(key)
                cached?.safePaletteColor(defaultColor)
            }
            if (next != null) {
                CoverAccentColorCache[key] = next
                color = next
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(if (attempt == 0) 80L else 180L)
        }
    }
    return color
}

private val CoverAccentColorCache = ConcurrentHashMap<String, Color>()

private fun android.graphics.Bitmap.safePaletteColor(defaultColor: Color): Color? {
    if (isRecycled) return null
    val softwareCopy = if (android.os.Build.VERSION.SDK_INT >= 26 && config == android.graphics.Bitmap.Config.HARDWARE) {
        copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        null
    }
    val source = softwareCopy ?: this
    return try {
        val swatch = Palette.from(source).maximumColorCount(8).generate()
        val rgb = swatch.mutedSwatch?.rgb
            ?: swatch.vibrantSwatch?.rgb
            ?: swatch.dominantSwatch?.rgb
            ?: defaultColor.toArgbCompat()
        Color(rgb).softenedForPlayer()
    } catch (_: Throwable) {
        null
    } finally {
        softwareCopy?.recycle()
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


private val CoverEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

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
