package com.rawsmusic.core.ui.widget.player

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkAnimationStyle
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import kotlin.math.abs

private data class PlayerForegroundTone(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val icon: Color,
    val iconSoft: Color,
    val chipBackground: Color,
    val chipText: Color,
    val controlTrack: Color,
    val controlFill: Color
)

@Composable
private fun rememberPlayerForegroundTone(): PlayerForegroundTone {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val primary = if (isDark) Color.White else scheme.onBackground.copy(alpha = 0.88f)
    val secondary = if (isDark) Color.White.copy(alpha = 0.76f) else scheme.onBackground.copy(alpha = 0.68f)
    val tertiary = if (isDark) Color.White.copy(alpha = 0.52f) else scheme.onBackground.copy(alpha = 0.46f)
    return PlayerForegroundTone(
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        icon = if (isDark) Color.White else scheme.onBackground.copy(alpha = 0.84f),
        iconSoft = if (isDark) Color.White.copy(alpha = 0.72f) else scheme.onBackground.copy(alpha = 0.62f),
        chipBackground = if (isDark) Color.Black.copy(alpha = 0.28f) else scheme.surfaceContainerHigh.copy(alpha = 0.72f),
        chipText = if (isDark) Color.White.copy(alpha = 0.85f) else scheme.onSurface.copy(alpha = 0.82f),
        controlTrack = if (isDark) Color.White.copy(alpha = 0.16f) else scheme.onSurfaceVariantSummary.copy(alpha = 0.18f),
        controlFill = if (isDark) Color.White.copy(alpha = 0.88f) else scheme.primary.copy(alpha = 0.84f)
    )
}

@Composable
fun ImmersivePlayerHorizontalStack(
    currentScene: PlayerSceneController.Scene,
    fromScene: PlayerSceneController.Scene,
    toScene: PlayerSceneController.Scene,
    progress: Float,
    isTransitioning: Boolean,
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
    @DrawableRes previousIconRes: Int,
    @DrawableRes playIconRes: Int,
    @DrawableRes pauseIconRes: Int,
    @DrawableRes nextIconRes: Int,
    @DrawableRes playModeIconRes: Int,
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    audioInfoText: String = "",
    albumSongs: List<AudioFile>,
    albumCoverPath: String?,
    queueVisible: Boolean,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onToggleQueue: () -> Unit,
    onBack: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onOpenMetadata: () -> Unit = {},
    onOpenAudioEffects: () -> Unit = {},
    showPlayerMore: Boolean,
    onShowPlayerMoreChange: (Boolean) -> Unit,
    onMorePanelVisibleChange: (Boolean) -> Unit,
    onModalDismissActionChange: ((() -> Unit)?) -> Unit,
    sleepTimerSelection: Int = 0,
    onSleepTimerSelectionChange: (Int) -> Unit = {},
    onLyricModifyAlbumArt: () -> Unit = {},
    onSearchLyrico: () -> Unit = {},
    onOpenInLyrico: () -> Unit = {},
    onOpenLyric: () -> Unit,
    onArtworkLongPress: () -> Unit = {},
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onLyricRomaToggle: () -> Unit,
    onAlbumSongClick: (AudioFile, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val basePage = if (isTransitioning) {
        lerp(scenePageIndex(fromScene), scenePageIndex(toScene), progress.coerceIn(0f, 1f))
    } else {
        scenePageIndex(currentScene).toFloat()
    }
    // Visibility is owned by ComposePlayerContainer rather than this horizontally moving page
    // stack, so a page recomposition cannot reset the modal immediately after it opens.
    var immersiveProgressStyle by remember { mutableStateOf(ImmersiveProgressStyle.from(AppPreferences.UI.immersiveProgressStyle)) }
    var climaxEnabled by remember { mutableStateOf(AppPreferences.UI.immersiveClimaxEnabled) }
    var waveformDebugPanel by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformDebugPanel) }
    var waveformRemainingColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformRemainingColor) }
    var waveformPlayedColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformPlayedColor) }
    var waveformClimaxColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformClimaxColor) }
    var queueFullscreen by remember { mutableStateOf(false) }

    LaunchedEffect(queueVisible) {
        if (!queueVisible) queueFullscreen = false
    }

    fun saveProgressStyle(style: ImmersiveProgressStyle) {
        immersiveProgressStyle = style
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
        waveformRemainingColorInt = color.toArgb()
        AppPreferences.UI.immersiveWaveformRemainingColor = waveformRemainingColorInt
    }

    fun saveWaveformPlayedColor(color: Color) {
        waveformPlayedColorInt = color.toArgb()
        AppPreferences.UI.immersiveWaveformPlayedColor = waveformPlayedColorInt
    }

    fun saveWaveformClimaxColor(color: Color) {
        waveformClimaxColorInt = color.toArgb()
        AppPreferences.UI.immersiveWaveformClimaxColor = waveformClimaxColorInt
    }

    fun closePlayerMore() {
        onShowPlayerMoreChange(false)
    }

    LaunchedEffect(showPlayerMore, queueFullscreen, queueVisible) {
        if (queueVisible && queueFullscreen) {
            onModalDismissActionChange { queueFullscreen = false }
            onMorePanelVisibleChange(true)
        } else if (showPlayerMore) {
            onModalDismissActionChange(::closePlayerMore)
            onMorePanelVisibleChange(true)
        } else {
            onModalDismissActionChange(null)
            onMorePanelVisibleChange(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onModalDismissActionChange(null)
            onMorePanelVisibleChange(false)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun Modifier.pageLayer(page: Int): Modifier {
            val distance = page - basePage
            return graphicsLayer {
                translationX = distance * widthPx
                alpha = 1f
            }
        }
        ImmersiveBackdrop(
            coverPath = coverPath,
            pageProgress = basePage,
            artworkTransitionState = artworkTransitionState,
            clearArtworkVisible = !(queueVisible && queueFullscreen)
        )
        ImmersiveAlbumInfoPage(
            currentSong = currentSong,
            songs = albumSongs,
            coverPath = albumCoverPath ?: coverPath,
            onBack = { },
            onSongClick = onAlbumSongClick,
            pageProgress = basePage,
            renderBackdrop = false,
            renderTopBar = false,
            modifier = Modifier.pageLayer(0)
        )
        ImmersivePlayerMainPage(
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
            queueVisible = queueVisible,
            queueSongs = queueSongs,
            queueCurrentIndex = queueCurrentIndex,
            queueFullscreen = queueFullscreen,
            onQueueFullscreenChange = { queueFullscreen = it },
            onQueueSongClick = onQueueSongClick,
            onClearPriorityQueue = onClearPriorityQueue,
            onToggleQueue = {
                if (!queueVisible) queueFullscreen = false
                onToggleQueue()
            },
            onBack = onBack,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPlayMode = onPlayMode,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            progressStyle = immersiveProgressStyle,
            climaxEnabled = climaxEnabled,
            waveformDebugPanel = waveformDebugPanel,
            waveformRemainingColor = Color(waveformRemainingColorInt),
            waveformPlayedColor = Color(waveformPlayedColorInt),
            waveformClimaxColor = Color(waveformClimaxColorInt),
            onOpenMore = {
                onShowPlayerMoreChange(true)
            },
            onOpenLyric = onOpenLyric,
            onArtworkLongPress = onArtworkLongPress,
            pageProgress = basePage,
            renderBackdrop = false,
            renderTopBar = false,
            modifier = Modifier.pageLayer(1)
        )
        LyricPage(
            currentSong = currentSong,
            coverPath = coverPath,
            song = lyricSong,
            positionMs = lyricPositionMs,
            isPlaying = isPlaying,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            onSeek = onLyricSeek,
            onTranslationToggle = onLyricTranslationToggle,
            onRomaToggle = onLyricRomaToggle,
            moreIconRes = R.drawable.ic_more_vert,
            onModifyAlbumArt = onLyricModifyAlbumArt,
            onSearchLyrico = onSearchLyrico,
            onOpenInLyrico = onOpenInLyrico,
            onModalVisibleChange = onMorePanelVisibleChange,
            onModalDismissActionChange = onModalDismissActionChange,
            onBack = { },
            showHeaderCover = true,
            renderBackdrop = false,
            modifier = Modifier.pageLayer(2)
        )
        AnimatedVisibility(
            visible = !queueVisible,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(140)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ImmersiveTopBar(
                pageProgress = basePage,
                showPageDots = isImmersiveHorizontalPagingIndicatorVisible(
                    isTransitioning = isTransitioning,
                    fromScene = fromScene,
                    toScene = toScene
                ),
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 30.dp, end = 30.dp, top = 2.dp)
            )
        }

        RawMiuixOverlayDialog(
            show = showPlayerMore,
            onDismissRequest = ::closePlayerMore,
            renderInRootScaffold = true
        ) {
            ImmersiveMoreSheet(
                currentSong = currentSong,
                coverPath = coverPath,
                progressStyle = immersiveProgressStyle,
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
                sleepTimerSelection = sleepTimerSelection,
                onSleepTimerSelectionChange = onSleepTimerSelectionChange,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImmersivePlayerMainPage(
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState? = null,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioVisualizerForeground: Boolean,
    onAudioVisualizerDismiss: () -> Unit,
    @DrawableRes previousIconRes: Int,
    @DrawableRes playIconRes: Int,
    @DrawableRes pauseIconRes: Int,
    @DrawableRes nextIconRes: Int,
    @DrawableRes playModeIconRes: Int,
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    audioInfoText: String = "",
    queueVisible: Boolean,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    queueFullscreen: Boolean,
    onQueueFullscreenChange: (Boolean) -> Unit,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onClearPriorityQueue: (() -> Unit)?,
    onToggleQueue: () -> Unit,
    onBack: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    progressStyle: ImmersiveProgressStyle,
    climaxEnabled: Boolean,
    waveformDebugPanel: Boolean,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onOpenMore: () -> Unit,
    onOpenLyric: () -> Unit = {},
    onArtworkLongPress: () -> Unit = {},
    pageProgress: Float = 1f,
    renderBackdrop: Boolean = true,
    renderTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    Box(modifier = modifier.fillMaxSize()) {
        val tone = rememberPlayerForegroundTone()
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentVerticalPadding = 14.dp
        if (renderBackdrop) {
            ImmersiveBackdrop(
                coverPath = coverPath,
                pageProgress = pageProgress,
                artworkTransitionState = artworkTransitionState,
                clearArtworkVisible = !(queueVisible && queueFullscreen)
            )
        }
        if (audioVisualizerEnabled && audioVisualizerForeground && !queueVisible) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxHeight * IMMERSIVE_CLEAR_ARTWORK_FRACTION + IMMERSIVE_CLEAR_ARTWORK_FADE_EXTENSION)
                        .align(Alignment.TopCenter)
                        .clipToBounds()
                ) {
                    AlbumArtworkSpectrumOverlay(
                        spectrum = audioSpectrum,
                        visible = true,
                        isPlaying = isPlaying,
                        layer = AudioVisualizerLayer.Foreground,
                        showControls = true,
                        onDismiss = onAudioVisualizerDismiss,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 30.dp, vertical = 14.dp)
        ) {
            var titleInfoHeightPx by remember { mutableIntStateOf(0) }
            var progressPanelHeightPx by remember { mutableIntStateOf(0) }
            var transportControlsHeightPx by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            LaunchedEffect(progressStyle) {
                progressPanelHeightPx = 0
            }
            // Map the full-screen clear-cover boundary into this inset/padded content coordinate
            // space. The queue must begin below the clear artwork instead of being laid over it.
            val fullViewportHeight =
                maxHeight + statusBarHeight + navigationBarHeight + contentVerticalPadding * 2
            val clearArtworkBottom =
                fullViewportHeight * IMMERSIVE_CLEAR_ARTWORK_FRACTION +
                    IMMERSIVE_CLEAR_ARTWORK_FADE_EXTENSION -
                    statusBarHeight -
                    contentVerticalPadding
            val measuredTitleInfoHeight = with(density) {
                if (titleInfoHeightPx > 0) titleInfoHeightPx.toDp() else 58.dp
            }
            val measuredProgressPanelHeight = with(density) {
                if (progressPanelHeightPx > 0) {
                    progressPanelHeightPx.toDp()
                } else {
                    fallbackImmersiveProgressPanelHeight(progressStyle)
                }
            }
            val measuredTransportHeight = with(density) {
                if (transportControlsHeightPx > 0) transportControlsHeightPx.toDp() else 68.dp
            }
            val miniLyricHasSecondaryText = remember(lyricSong, displayTranslation, displayRoma) {
                immersivePrimaryLyricLineLimit(lyricSong, displayTranslation, displayRoma) == 3
            }
            val playerLayoutMetrics = resolveImmersivePlayerLayoutMetrics(
                viewportHeight = maxHeight,
                clearArtworkBottom = clearArtworkBottom,
                titleInfoHeight = measuredTitleInfoHeight,
                progressPanelHeight = measuredProgressPanelHeight,
                transportControlsHeight = measuredTransportHeight,
                fontScale = fontScale,
                hasSecondaryText = miniLyricHasSecondaryText
            )
            val titleTop = playerLayoutMetrics.titleTop
            val lyricPreviewHeight = playerLayoutMetrics.lyricPreviewHeight
            val lyricPreviewRows = playerLayoutMetrics.lyricPreviewRows
            val queueTop = (clearArtworkBottom + 12.dp).coerceIn(0.dp, maxHeight)
            val queueBottomReserve = 82.dp
            val queueViewportHeight = (maxHeight - queueTop - queueBottomReserve)
                .coerceAtLeast(0.dp)
            val queueMotion = tween<androidx.compose.ui.unit.Dp>(
                durationMillis = 750,
                easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
            )
            val animatedQueueTop by animateDpAsState(
                targetValue = if (queueFullscreen) 0.dp else queueTop,
                animationSpec = queueMotion,
                label = "immersive-queue-top"
            )
            val animatedQueueHeight by animateDpAsState(
                targetValue = if (queueFullscreen) {
                    (maxHeight - queueBottomReserve).coerceAtLeast(0.dp)
                } else {
                    queueViewportHeight
                },
                animationSpec = queueMotion,
                label = "immersive-queue-height"
            )

            if (renderTopBar) {
                ImmersiveTopBar(
                    pageProgress = pageProgress,
                    showPageDots = false,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            if (!queueVisible && currentSong != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(titleTop)
                        .observeArtworkLongPress(onArtworkLongPress)
                )
            }

            AnimatedVisibility(
                visible = !queueVisible,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(140)),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = titleTop)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .pointerInput(currentSong?.path, currentSong?.title, currentSong?.artist, currentSong?.album) {
                                detectTapGestures(
                                    onLongPress = { copySongInfoToClipboard(context, currentSong) }
                                )
                            }
                    ) {
                        Column(
                            modifier = Modifier.onSizeChanged { size ->
                                if (titleInfoHeightPx != size.height) titleInfoHeightPx = size.height
                            }
                        ) {
                            Text(
                                currentSong?.displayName ?: stringResource(R.string.player_no_song),
                                color = tone.primary,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.basicMarquee(iterations = 1, repeatDelayMillis = 900)
                            )
                            Text(
                                currentSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "",
                                color = tone.secondary,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        MiniLyricPreview(
                            song = lyricSong,
                            positionMs = lyricPositionMs,
                            displayTranslation = displayTranslation,
                            displayRoma = displayRoma,
                            onClick = onOpenLyric,
                            primaryColor = tone.primary,
                            secondaryColor = tone.secondary,
                            dimColor = tone.tertiary,
                            maxHeight = lyricPreviewHeight,
                            maxPrimaryRows = lyricPreviewRows
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("♡", color = tone.icon, fontSize = 33.sp)
                        IconCircle(
                            iconRes = R.drawable.ic_more_vert,
                            size = 44.dp,
                            tint = tone.iconSoft,
                            onClick = onOpenMore
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = queueVisible,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(140)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = animatedQueueTop)
                    .fillMaxWidth()
                    .height(animatedQueueHeight)
                    .padding(
                        horizontal = if (queueFullscreen) 12.dp else 0.dp,
                        vertical = if (queueFullscreen) 10.dp else 0.dp
                    )
                    .clipToBounds()
            ) {
                InlinePlayerQueue(
                    songs = queueSongs,
                    currentIndex = queueCurrentIndex,
                    currentSong = currentSong,
                    currentCoverPath = coverPath,
                    colors = InlinePlayerQueueColors(
                        primaryText = tone.primary,
                        secondaryText = tone.secondary,
                        accent = tone.icon,
                        icon = tone.iconSoft,
                        currentBackground = tone.chipBackground,
                        artworkPlaceholder = tone.chipBackground.copy(alpha = 0.72f)
                    ),
                    onSongClick = onQueueSongClick,
                    onClearPriorityQueue = onClearPriorityQueue,
                    fullscreen = queueFullscreen,
                    onFullscreenChange = onQueueFullscreenChange,
                    modifier = Modifier.fillMaxSize()
                )
            }

            AnimatedVisibility(
                visible = !queueVisible,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(140)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = playerLayoutMetrics.progressBottomPadding)
            ) {
                Column(
                    modifier = Modifier.onSizeChanged { size ->
                        if (progressPanelHeightPx != size.height) progressPanelHeightPx = size.height
                    }
                ) {
                    ImmersiveProgress(
                        currentSong = currentSong,
                        currentPositionMs = currentPositionMs,
                        totalDurationMs = totalDurationMs,
                        isPlaying = isPlaying,
                        progressStyle = progressStyle,
                        climaxEnabled = climaxEnabled,
                        waveformDebugPanel = waveformDebugPanel,
                        waveformRemainingColor = waveformRemainingColor,
                        waveformPlayedColor = waveformPlayedColor,
                        waveformClimaxColor = waveformClimaxColor,
                        onSeekStart = onSeekStart,
                        onSeekStop = onSeekStop
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        ImmersiveQualityPill(
                            song = currentSong,
                            text = audioInfoText,
                            onClick = onAudioQuality,
                            onLongClick = onAudioQualityLongPress
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { size ->
                        if (transportControlsHeightPx != size.height) transportControlsHeightPx = size.height
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconCircle(iconRes = playModeIconRes, size = 38.dp, tint = tone.iconSoft, onClick = onPlayMode)
                IconCircle(iconRes = previousIconRes, size = 50.dp, tint = tone.icon, onClick = onPrevious)
                IconCircle(
                    iconRes = if (isPlaying) pauseIconRes else playIconRes,
                    size = 68.dp,
                    tint = tone.icon,
                    onClick = onPlayPause
                )
                IconCircle(iconRes = nextIconRes, size = 50.dp, tint = tone.icon, onClick = onNext)
                ImmersiveQueueButton(
                    selected = queueVisible,
                    tint = if (queueVisible) tone.icon else tone.iconSoft,
                    onClick = onToggleQueue
                )
            }
        }
    }
}

@Composable
private fun ImmersiveQueueButton(
    selected: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
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
fun ImmersiveLyricPage(
    currentSong: AudioFile?,
    coverPath: String?,
    song: Song?,
    positionMs: Long,
    isPlaying: Boolean = false,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onTranslationToggle: () -> Unit,
    onPlayPause: () -> Unit,
    pageProgress: Float = 2f,
    renderBackdrop: Boolean = true,
    renderTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        val tone = rememberPlayerForegroundTone()
        if (renderBackdrop) ImmersiveBackdrop(coverPath = coverPath, pageProgress = pageProgress)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            if (renderTopBar) {
                ImmersiveTopBar(pageProgress = pageProgress, showPageDots = false)
                Spacer(Modifier.height(46.dp))
            } else {
                Spacer(Modifier.height(94.dp))
            }
            Text(
                currentSong?.displayName ?: stringResource(R.string.player_no_song),
                color = tone.primary,
                fontSize = 34.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                currentSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "",
                color = tone.secondary,
                fontSize = 21.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val maxPrimaryLyricLines = remember(song, displayTranslation, displayRoma) {
                    immersivePrimaryLyricLineLimit(song, displayTranslation, displayRoma)
                }
                ComposeLyricView(
                    song = song,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    textColor = tone.primary,
                    dimColor = tone.tertiary.copy(alpha = 0.62f),
                    secondaryColor = tone.secondary,
                    blurEnabled = AppPreferences.UI.lyricBlurEnabled,
                    highlightAll = AppPreferences.UI.lyricHighlightAllEnabled,
                    karaokeGlowEnabled = AppPreferences.UI.lyricKaraokeGlowEnabled,
                    karaokeLiftEnabled = AppPreferences.UI.lyricKaraokeLiftEnabled,
                    maxPrimaryVisibleLines = maxPrimaryLyricLines,
                    onLineClick = onSeek,
                    onSwipeRight = onBack,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LyricBottomButton(
                    text = stringResource(
                        if (displayTranslation) R.string.player_lyric_translation_on
                        else R.string.player_lyric_translation_off
                    ),
                    onClick = onTranslationToggle
                )
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}


private fun immersivePrimaryLyricLineLimit(song: Song?, displayTranslation: Boolean, displayRoma: Boolean): Int {
    val hasSecondaryText = song?.lyrics.orEmpty().any { line ->
        displayTranslation && !line.translation.isNullOrBlank() ||
            displayRoma && !line.roma.isNullOrBlank() ||
            !line.secondary.isNullOrBlank()
    }
    return if (hasSecondaryText) 3 else 5
}

@Composable
fun ImmersiveAlbumInfoPage(
    currentSong: AudioFile?,
    songs: List<AudioFile>,
    coverPath: String?,
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    pageProgress: Float = 0f,
    renderBackdrop: Boolean = true,
    renderTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    val sameArtist = remember(currentSong, songs) {
        songs.filter { it.artist == currentSong?.artist }.ifEmpty { songs.take(12) }
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (renderBackdrop) ImmersiveBackdrop(coverPath = coverPath, pageProgress = pageProgress)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                if (renderTopBar) {
                    ImmersiveTopBar(pageProgress = pageProgress, showPageDots = false, modifier = Modifier.padding(top = 18.dp))
                    Spacer(Modifier.height(82.dp))
                } else {
                    Spacer(Modifier.height(126.dp))
                }
                Text(
                    currentSong?.displayName ?: stringResource(R.string.player_song_info_title),
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.player_local_song_detail),
                    color = Color.White.copy(alpha = 0.66f),
                    fontSize = 20.sp
                )
            }
            item { InfoLine(stringResource(R.string.player_info_artist), currentSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: stringResource(R.string.player_unknown_artist), coverPath) }
            item { InfoLine(stringResource(R.string.player_info_album), currentSong?.album?.ifBlank { stringResource(R.string.player_unknown_album) } ?: stringResource(R.string.player_unknown_album), coverPath) }
            item { InfoLine(stringResource(R.string.player_info_production), stringResource(R.string.player_info_production_summary), coverPath) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoChip("↗ ${sameArtist.size}")
                    InfoChip(stringResource(R.string.player_local_listening_chip))
                    InfoChip("#${currentSong?.genre?.ifBlank { stringResource(R.string.player_default_genre) } ?: stringResource(R.string.player_default_genre)}")
                }
            }
            item {
                Text(
                    stringResource(
                        R.string.player_song_artist_line,
                        currentSong?.displayName ?: stringResource(R.string.player_this_song),
                        currentSong?.artist?.ifBlank { "" } ?: ""
                    ),
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.player_local_metadata_summary),
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 19.sp,
                    lineHeight = 30.sp
                )
            }
            item {
                Text(
                    stringResource(
                        R.string.player_same_artist_title,
                        currentSong?.artist?.ifBlank { stringResource(R.string.player_local_music) } ?: stringResource(R.string.player_local_music)
                    ),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            itemsIndexed(sameArtist.take(12)) { index, song ->
                AlbumInfoSongRow(song = song, onClick = { onSongClick(song, index) })
            }
        }
    }
}

@Composable
private fun ImmersiveTopBar(
    pageProgress: Float,
    showPageDots: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = showPageDots,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(160))
        ) {
            PageDots(pageProgress = pageProgress)
        }
    }
}

private fun isImmersiveHorizontalPagingIndicatorVisible(
    isTransitioning: Boolean,
    fromScene: PlayerSceneController.Scene,
    toScene: PlayerSceneController.Scene
): Boolean {
    if (!isTransitioning) return false
    val horizontalScenes = setOf(
        PlayerSceneController.Scene.ALBUM_DETAIL,
        PlayerSceneController.Scene.PLAYER,
        PlayerSceneController.Scene.LYRIC
    )
    return fromScene in horizontalScenes && toScene in horizontalScenes && fromScene != toScene
}

@Composable
private fun PageDots(pageProgress: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val active = (1f - abs(pageProgress - index)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .width(6.dp + 13.dp * active)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.32f + 0.56f * active))
            )
        }
    }
}

@Composable
private fun MiniLyricPreview(
    song: Song?,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onClick: () -> Unit,
    primaryColor: Color = Color.White,
    secondaryColor: Color = Color.White.copy(alpha = 0.58f),
    dimColor: Color = Color.White.copy(alpha = 0.40f),
    maxHeight: Dp,
    maxPrimaryRows: Int
) {
    val lyricLines = song?.lyrics.orEmpty()
    val lyricSource = remember(lyricLines, displayTranslation, displayRoma) {
        prepareMiniLyricSource(lyricLines, displayTranslation, displayRoma)
    }
    val preview = remember(
        lyricSource,
        positionMs,
        maxPrimaryRows
    ) {
        resolveMiniLyricPresentation(
            source = lyricSource,
            positionMs = positionMs,
            maxPrimaryRows = maxPrimaryRows
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(maxHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        AnimatedContent(
            targetState = preview,
            transitionSpec = {
                (slideInVertically(tween(240)) { it / 5 } + fadeIn(tween(200))) togetherWith
                    (slideOutVertically(tween(190)) { -it / 5 } + fadeOut(tween(150)))
            },
            contentKey = { state ->
                val line = state.currentLine ?: state.lines.firstOrNull { it.index == state.anchorLineIndex }
                line?.let { Triple(it.index, it.beginMs, it.primary) } ?: Triple(-1, -1L, "")
            },
            label = "immersive-mini-lyric",
            modifier = Modifier
                .fillMaxSize()
                .miniLyricShortEdgeFade()
        ) { state ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                if (state.lines.isEmpty()) {
                    Text(
                        stringResource(R.string.player_no_lyric),
                        color = dimColor,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    state.lines.forEachIndexed { index, line ->
                        val alpha by animateFloatAsState(
                            targetValue = if (line.active) 0.88f else 0.48f,
                            animationSpec = tween(180),
                            label = "mini-lyric-alpha"
                        )
                        Column(
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (line.active) 1f else 0.985f
                                scaleY = if (line.active) 1f else 0.985f
                            }
                        ) {
                            Text(
                                text = line.primary,
                                color = if (line.active) primaryColor.copy(alpha = alpha) else dimColor.copy(alpha = alpha),
                                fontSize = if (line.active) 15.sp else 13.sp,
                                fontWeight = if (line.active) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = if (line.allowWrap) 2 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            line.secondaryText.takeIf(String::isNotBlank)?.let { secondary ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = secondary,
                                    color = if (line.active) secondaryColor else dimColor.copy(alpha = 0.78f),
                                    fontSize = if (line.active) 13.sp else 12.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (index != state.lines.lastIndex) Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImmersiveProgress(
    currentSong: AudioFile?,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    progressStyle: ImmersiveProgressStyle,
    climaxEnabled: Boolean,
    waveformDebugPanel: Boolean,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit
) {
    when (progressStyle) {
        ImmersiveProgressStyle.Classic -> ClassicTimelineProgress(
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop
        )
        ImmersiveProgressStyle.Waveform -> WindowWaveformTimelineProgress(
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
            onSeekStop = onSeekStop
        )
        ImmersiveProgressStyle.Seconds -> SecondSpectrumTimelineProgress(
            currentSong = currentSong,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            isPlaying = isPlaying,
            waveformRemainingColor = waveformRemainingColor,
            waveformPlayedColor = waveformPlayedColor,
            waveformClimaxColor = waveformClimaxColor,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop
        )
    }
}

@Composable
internal fun WindowWaveformTimelineProgress(
    currentSong: AudioFile?,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    climaxEnabled: Boolean,
    showDebugPanel: Boolean,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = rememberPlayerForegroundTone()
    ImmersiveWaveformProgressBar(
        currentSong = currentSong,
        currentPositionMs = currentPositionMs,
        totalDurationMs = totalDurationMs,
        isPlaying = isPlaying,
        colors = ImmersiveWaveformColors(
            played = waveformPlayedColor,
            remaining = waveformRemainingColor,
            climaxPlayed = waveformClimaxColor.copy(alpha = 0.46f),
            climaxRemaining = waveformClimaxColor.copy(alpha = 0.95f),
            needle = Color.White.copy(alpha = 0.92f),
            time = tone.tertiary
        ),
        climaxEnabled = climaxEnabled,
        showDebugPanel = showDebugPanel,
        onSeekStart = onSeekStart,
        onSeekStop = onSeekStop,
        modifier = modifier
    )
}

@Composable
internal fun SecondSpectrumTimelineProgress(
    currentSong: AudioFile?,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = rememberPlayerForegroundTone()
    ImmersiveSecondProgressBar(
        currentSong = currentSong,
        currentPositionMs = currentPositionMs,
        totalDurationMs = totalDurationMs,
        isPlaying = isPlaying,
        colors = ImmersiveWaveformColors(
            played = waveformPlayedColor,
            remaining = waveformRemainingColor,
            climaxPlayed = waveformClimaxColor,
            climaxRemaining = waveformClimaxColor,
            needle = Color.White.copy(alpha = 0.92f),
            time = tone.tertiary
        ),
        onSeekStart = onSeekStart,
        onSeekStop = onSeekStop,
        modifier = modifier
    )
}

@Composable
internal fun ClassicTimelineProgress(
    currentPositionMs: Long,
    totalDurationMs: Long,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val foregroundTone = rememberPlayerForegroundTone()
    var widthPx by remember { mutableStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val realFraction = if (totalDurationMs > 0L) {
        currentPositionMs.toFloat() / totalDurationMs.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)

    val displayFraction = if (isDragging) dragFraction else realFraction
    val displayPositionMs = if (isDragging && totalDurationMs > 0L) {
        (displayFraction * totalDurationMs).toLong()
    } else {
        currentPositionMs
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(totalDurationMs, widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main
                        )
                        if (totalDurationMs <= 0L || widthPx <= 1) {
                            down.consume()
                            return@awaitEachGesture
                        }
                        var lastFraction = (down.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        isDragging = true
                        dragFraction = lastFraction
                        onSeekStart()
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                lastFraction = (change.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                                dragFraction = lastFraction
                                change.consume()
                                if (!change.pressed) break
                            }
                        } finally {
                            isDragging = false
                            onSeekStop(lastFraction)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(foregroundTone.controlTrack)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayFraction.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(foregroundTone.controlFill)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AudioUtils.formatDuration(displayPositionMs), color = foregroundTone.tertiary, fontSize = 12.sp)
            Text(AudioUtils.formatDuration(totalDurationMs), color = foregroundTone.tertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ImmersiveQualityPill(song: AudioFile?, text: String, onClick: () -> Unit, onLongClick: () -> Unit = onClick) {
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
            .pointerInput(onClick) {
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
private fun IconCircle(@DrawableRes iconRes: Int, size: androidx.compose.ui.unit.Dp, tint: Color, onClick: () -> Unit) {
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
                modifier = Modifier.size(size * 0.72f)
            )
        }
    }
}

@Composable
private fun LyricBottomButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(rememberPlayerForegroundTone().controlTrack)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = rememberPlayerForegroundTone().secondary, fontSize = 17.sp)
    }
}

@Composable
private fun InfoLine(label: String, value: String, coverPath: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            if (!coverPath.isNullOrBlank()) {
                BitmapImage(key = coverPath, contentDescription = null, modifier = Modifier.fillMaxSize(), targetWidth = 120, targetHeight = 120, surface = ArtworkSurface.Playback)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text("$label：", color = rememberPlayerForegroundTone().secondary, fontSize = 20.sp)
        Text(value, color = rememberPlayerForegroundTone().primary, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoChip(text: String) {
    Box(
        modifier = Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = rememberPlayerForegroundTone().primary, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
private fun AlbumInfoSongRow(song: AudioFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val artworkKey = song.resolvePlaybackArtworkKey(song.albumArtPath)
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            if (!artworkKey.isNullOrBlank()) {
                BitmapImage(key = artworkKey, contentDescription = null, modifier = Modifier.fillMaxSize(), targetWidth = 180, targetHeight = 180, surface = ArtworkSurface.Playback)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.displayName, color = rememberPlayerForegroundTone().primary, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { stringResource(R.string.player_unknown_artist) }, color = rememberPlayerForegroundTone().secondary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("♡", color = rememberPlayerForegroundTone().iconSoft, fontSize = 31.sp)
    }
}

private val playerSleepTimerOptions = listOf(
    "关闭",
    "10 分钟",
    "15 分钟",
    "20 分钟",
    "30 分钟",
    "45 分钟",
    "60 分钟",
    "90 分钟",
    "当前歌曲结束后",
    "再播放 3 首",
    "再播放 5 首"
)

@Composable
internal fun ImmersiveMoreSheet(
    currentSong: AudioFile?,
    coverPath: String?,
    progressStyle: ImmersiveProgressStyle,
    climaxEnabled: Boolean,
    waveformDebugPanel: Boolean,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onProgressStyleChange: (ImmersiveProgressStyle) -> Unit,
    onClimaxEnabledChange: (Boolean) -> Unit,
    onWaveformDebugPanelChange: (Boolean) -> Unit,
    onWaveformRemainingColorChange: (Color) -> Unit,
    onWaveformPlayedColorChange: (Color) -> Unit,
    onWaveformClimaxColorChange: (Color) -> Unit,
    onOpenMetadata: () -> Unit = {},
    onOpenAudioEffects: () -> Unit = {},
    audioVisualizerEnabled: Boolean = false,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit = {},
    sleepTimerSelection: Int = 0,
    onSleepTimerSelectionChange: ((Int) -> Unit)? = null,
    artworkAnimationStyle: PlayerArtworkAnimationStyle? = null,
    onArtworkAnimationStyleChange: ((PlayerArtworkAnimationStyle) -> Unit)? = null
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val cardColor = if (isDark) scheme.surfaceContainerHigh.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.88f)
    val actionIconColor = scheme.onSurface
    val sheetScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(sheetScrollState)
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardColor)
                ) {
                    if (!coverPath.isNullOrBlank()) {
                        BitmapImage(key = coverPath, contentDescription = null, modifier = Modifier.fillMaxSize(), targetWidth = 220, targetHeight = 220, surface = ArtworkSurface.Playback)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(currentSong?.displayName ?: stringResource(R.string.player_no_song), color = scheme.onSurface, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(currentSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "", color = scheme.onSurfaceVariantSummary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ImmersiveMoreActionButton(
                    iconRes = R.drawable.ic_metadata_outline,
                    label = stringResource(R.string.player_more_metadata_short),
                    iconColor = actionIconColor,
                    cardColor = cardColor,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenMetadata
                )
                ImmersiveMoreActionButton(
                    iconRes = R.drawable.ic_audio_effects_custom,
                    label = stringResource(R.string.player_more_effects_short),
                    iconColor = actionIconColor,
                    cardColor = cardColor,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenAudioEffects
                )
                ImmersiveMoreActionButton(
                    iconRes = R.drawable.ic_share,
                    label = stringResource(R.string.player_more_share_audio),
                    iconColor = actionIconColor,
                    cardColor = cardColor,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        sharePlayerAudio(context, currentSong)
                    }
                )
                AudioVisualizerMoreActionButton(
                    enabled = audioVisualizerEnabled,
                    neutralCardColor = cardColor,
                    neutralIconColor = actionIconColor,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onAudioVisualizerEnabledChange(!audioVisualizerEnabled)
                    }
                )
            }
            Spacer(Modifier.height(20.dp))
            val sleepSelectionChange = onSleepTimerSelectionChange
            if (sleepSelectionChange != null) {
                val sleepDropdown = DropdownEntry(
                    items = playerSleepTimerOptions.mapIndexed { index, title ->
                        DropdownItem(
                            text = title,
                            selected = index == sleepTimerSelection,
                            onClick = { sleepSelectionChange(index) }
                        )
                    }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cardColor)
                ) {
                    WindowDropdownPreference(
                        entry = sleepDropdown,
                        title = "睡眠定时",
                        summary = playerSleepTimerOptions.getOrElse(sleepTimerSelection) { playerSleepTimerOptions.first() },
                        showValue = true,
                        maxHeight = 440.dp,
                        collapseOnSelection = true
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
            val animationStyleChange = onArtworkAnimationStyleChange
            if (artworkAnimationStyle != null && animationStyleChange != null) {
                val animationDropdown = DropdownEntry(
                    items = PlayerArtworkAnimationStyle.entries.map { style ->
                        val (title, summary) = when (style) {
                            PlayerArtworkAnimationStyle.PerspectiveDepth ->
                                "透视切换" to "密集步距、距离缩放与轻微三轴旋转"
                            PlayerArtworkAnimationStyle.InwardCarousel ->
                                "内倾轮播" to "保留双卡片缩放、位移与倾斜效果"
                            PlayerArtworkAnimationStyle.Slide ->
                                "平移" to "只进行水平平移，不做淡入淡出"
                        }
                        DropdownItem(
                            text = title,
                            summary = summary,
                            selected = style == artworkAnimationStyle,
                            onClick = { animationStyleChange(style) }
                        )
                    }
                )
                val selectedAnimationSummary = when (artworkAnimationStyle) {
                    PlayerArtworkAnimationStyle.PerspectiveDepth -> "透视切换"
                    PlayerArtworkAnimationStyle.InwardCarousel -> "内倾轮播"
                    PlayerArtworkAnimationStyle.Slide -> "平移"
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cardColor)
                ) {
                    WindowDropdownPreference(
                        entry = animationDropdown,
                        title = "专辑图切换动画",
                        summary = selectedAnimationSummary,
                        showValue = true,
                        maxHeight = 360.dp,
                        collapseOnSelection = true
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
            ImmersiveProgressSettingsCard(
                progressStyle = progressStyle,
                climaxEnabled = climaxEnabled,
                waveformDebugPanel = waveformDebugPanel,
                waveformRemainingColor = waveformRemainingColor,
                waveformPlayedColor = waveformPlayedColor,
                waveformClimaxColor = waveformClimaxColor,
                onProgressStyleChange = onProgressStyleChange,
                onClimaxEnabledChange = onClimaxEnabledChange,
                onWaveformDebugPanelChange = onWaveformDebugPanelChange,
                onWaveformRemainingColorChange = onWaveformRemainingColorChange,
                onWaveformPlayedColorChange = onWaveformPlayedColorChange,
                onWaveformClimaxColorChange = onWaveformClimaxColorChange
            )
            Spacer(Modifier.height(18.dp))
        }
}

@Composable
private fun ImmersiveProgressSettingsCard(
    progressStyle: ImmersiveProgressStyle,
    climaxEnabled: Boolean,
    waveformDebugPanel: Boolean,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onProgressStyleChange: (ImmersiveProgressStyle) -> Unit,
    onClimaxEnabledChange: (Boolean) -> Unit,
    onWaveformDebugPanelChange: (Boolean) -> Unit,
    onWaveformRemainingColorChange: (Color) -> Unit,
    onWaveformPlayedColorChange: (Color) -> Unit,
    onWaveformClimaxColorChange: (Color) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val cardColor = if (isDark) scheme.surfaceContainerHigh.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.88f)
    val classicLabel = stringResource(R.string.immersive_progress_style_classic)
    val waveformLabel = stringResource(R.string.immersive_progress_style_waveform)
    val secondsLabel = stringResource(R.string.immersive_progress_style_seconds)
    val progressStyleEntry = DropdownEntry(
        items = listOf(
            ImmersiveProgressStyle.Classic to classicLabel,
            ImmersiveProgressStyle.Waveform to waveformLabel,
            ImmersiveProgressStyle.Seconds to secondsLabel
        ).map { (style, label) ->
            DropdownItem(
                text = label,
                selected = progressStyle == style,
                onClick = { onProgressStyleChange(style) }
            )
        }
    )
    val selectedProgressStyleLabel = when (progressStyle) {
        ImmersiveProgressStyle.Classic -> classicLabel
        ImmersiveProgressStyle.Waveform -> waveformLabel
        ImmersiveProgressStyle.Seconds -> secondsLabel
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.immersive_progress_settings_title),
            color = scheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        WindowDropdownPreference(
            entry = progressStyleEntry,
            title = stringResource(R.string.immersive_progress_style),
            summary = selectedProgressStyleLabel,
            showValue = true,
            maxHeight = 320.dp,
            collapseOnSelection = true
        )
        Spacer(Modifier.height(14.dp))
        ImmersiveSettingToggleRow(
            title = stringResource(R.string.immersive_climax_point),
            subtitle = stringResource(R.string.immersive_climax_point_desc),
            enabled = true,
            checked = climaxEnabled,
            onClick = { onClimaxEnabledChange(!climaxEnabled) }
        )
        AnimatedVisibility(
            visible = progressStyle == ImmersiveProgressStyle.Waveform || progressStyle == ImmersiveProgressStyle.Seconds,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.immersive_waveform_colors),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                ImmersiveColorPaletteRow(
                    title = stringResource(R.string.immersive_waveform_remaining_color),
                    current = waveformRemainingColor,
                    enabled = true,
                    colors = listOf(Color.White.copy(alpha = 0.90f), Color(0xFF8EC5FF), Color(0xFF9BDBFF), Color(0xFFE6D6FF)),
                    onColor = onWaveformRemainingColorChange
                )
                Spacer(Modifier.height(8.dp))
                ImmersiveColorPaletteRow(
                    title = stringResource(R.string.immersive_waveform_played_color),
                    current = waveformPlayedColor,
                    enabled = true,
                    colors = listOf(Color.White.copy(alpha = 0.24f), Color(0x667C8CA0), Color(0x553B4652), Color(0x664F6074)),
                    onColor = onWaveformPlayedColorChange
                )
                Spacer(Modifier.height(8.dp))
                ImmersiveColorPaletteRow(
                    title = stringResource(R.string.immersive_waveform_climax_color),
                    current = waveformClimaxColor,
                    enabled = climaxEnabled,
                    colors = listOf(Color(0xFFFF3B30), Color(0xFFFF2D55), Color(0xFFFF9500), Color(0xFFAF52DE)),
                    onColor = onWaveformClimaxColorChange
                )
                Spacer(Modifier.height(12.dp))
                ImmersiveSettingToggleRow(
                    title = stringResource(R.string.immersive_waveform_debug_panel),
                    subtitle = stringResource(R.string.immersive_waveform_debug_panel_desc),
                    enabled = true,
                    checked = waveformDebugPanel,
                    onClick = { onWaveformDebugPanelChange(!waveformDebugPanel) }
                )
                AnimatedVisibility(
                    visible = waveformDebugPanel,
                    enter = fadeIn(animationSpec = tween(160)),
                    exit = fadeOut(animationSpec = tween(120))
                ) {
                    ImmersiveWaveformColorDebugBoard(
                        remaining = waveformRemainingColor,
                        played = waveformPlayedColor,
                        climax = waveformClimaxColor,
                        climaxEnabled = climaxEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveWaveformColorDebugBoard(
    remaining: Color,
    played: Color,
    climax: Color,
    climaxEnabled: Boolean
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.42f))
            .padding(12.dp)
    ) {
        Text(
            text = stringResource(R.string.immersive_waveform_color_debug_title),
            color = scheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ImmersiveColorDebugSwatch(stringResource(R.string.immersive_waveform_played_color), played)
            ImmersiveColorDebugSwatch(stringResource(R.string.immersive_waveform_remaining_color), remaining)
            ImmersiveColorDebugSwatch(stringResource(R.string.immersive_waveform_climax_color), if (climaxEnabled) climax else climax.copy(alpha = 0.24f))
        }
    }
}

@Composable
private fun ImmersiveColorDebugSwatch(label: String, color: Color) {
    val scheme = MiuixTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(54.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color)
                .border(1.dp, scheme.onSurfaceVariantSummary.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = scheme.onSurfaceVariantSummary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ImmersiveSettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) scheme.primary else scheme.surfaceContainerHigh.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ImmersiveSettingToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    checked: Boolean,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) scheme.surfaceContainerHigh.copy(alpha = 0.52f)
                else scheme.surfaceContainerHigh.copy(alpha = 0.28f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.42f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = scheme.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else 0.42f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    when {
                        !enabled -> scheme.onSurfaceVariantSummary.copy(alpha = 0.16f)
                        checked -> scheme.primary
                        else -> scheme.onSurfaceVariantSummary.copy(alpha = 0.22f)
                    }
                )
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun ImmersiveColorPaletteRow(
    title: String,
    current: Color,
    enabled: Boolean,
    colors: List<Color>,
    onColor: (Color) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.42f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { color ->
                val selected = color.toArgb() == current.toArgb()
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) scheme.primary else scheme.onSurfaceVariantSummary.copy(alpha = 0.24f),
                            shape = CircleShape
                        )
                        .clickable(enabled = enabled) { onColor(color) }
                )
            }
        }
    }
}

@Composable
private fun ImmersiveMoreActionButton(
    @DrawableRes iconRes: Int,
    label: String,
    iconColor: Color,
    cardColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cardColor),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                colorFilter = ColorFilter.tint(iconColor),
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
    }
}



@Composable
private fun AudioVisualizerMoreActionButton(
    enabled: Boolean,
    neutralCardColor: Color,
    neutralIconColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val cardColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (enabled) scheme.primary.copy(alpha = 0.30f) else neutralCardColor,
        animationSpec = tween(260),
        label = "visualizer-menu-card"
    )
    val iconColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (enabled) scheme.primary else neutralIconColor,
        animationSpec = tween(260),
        label = "visualizer-menu-icon"
    )
    val labelColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (enabled) scheme.primary else scheme.onSurface,
        animationSpec = tween(260),
        label = "visualizer-menu-label"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cardColor),
            contentAlignment = Alignment.Center
        ) {
            AudioVisualizerToggleGlyph(
                locked = enabled,
                tint = iconColor,
                animateOnEnter = true,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.player_more_visualizer),
            color = labelColor,
            fontSize = 15.sp,
            maxLines = 1
        )
    }
}

private fun scenePageIndex(scene: PlayerSceneController.Scene): Int = when (scene) {
    PlayerSceneController.Scene.ALBUM_DETAIL -> 0
    PlayerSceneController.Scene.PLAYER -> 1
    PlayerSceneController.Scene.LYRIC -> 2
    PlayerSceneController.Scene.MAIN,
    PlayerSceneController.Scene.QUEUE -> 1
}

private fun lerp(start: Int, end: Int, fraction: Float): Float {
    return start + (end - start) * fraction
}

@Composable
private fun rememberDominantCoverColor(coverPath: String?): Color {
    var color by remember(coverPath) { mutableStateOf(Color(0xFF17243A)) }
    LaunchedEffect(coverPath) {
        if (coverPath.isNullOrBlank()) {
            color = Color(0xFF17243A)
            return@LaunchedEffect
        }
        color = withContext(Dispatchers.IO) {
            val bitmap = BitmapProvider.execute(coverPath, 48, 48)
            if (bitmap == null || bitmap.isRecycled) {
                Color(0xFF17243A)
            } else {
                var r = 0L
                var g = 0L
                var b = 0L
                var count = 0L
                val stepX = (bitmap.width / 12).coerceAtLeast(1)
                val stepY = (bitmap.height / 12).coerceAtLeast(1)
                var y = 0
                while (y < bitmap.height) {
                    var x = 0
                    while (x < bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        val pr = android.graphics.Color.red(pixel)
                        val pg = android.graphics.Color.green(pixel)
                        val pb = android.graphics.Color.blue(pixel)
                        val brightness = (pr + pg + pb) / 3
                        if (brightness in 18..235) {
                            r += pr.toLong()
                            g += pg.toLong()
                            b += pb.toLong()
                            count++
                        }
                        x += stepX
                    }
                    y += stepY
                }
                if (count == 0L) {
                    Color(0xFF17243A)
                } else {
                    Color(
                        red = (r / count).toInt(),
                        green = (g / count).toInt(),
                        blue = (b / count).toInt()
                    )
                }
            }
        }
    }
    return color
}

private fun darken(color: Color, factor: Float): Color {
    return Color(
        red = (color.red * factor).coerceIn(0f, 1f),
        green = (color.green * factor).coerceIn(0f, 1f),
        blue = (color.blue * factor).coerceIn(0f, 1f),
        alpha = color.alpha
    )
}
