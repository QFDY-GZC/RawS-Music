package com.rawsmusic.core.ui.widget.player

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.platform.LocalWindowInfo
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
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.prefs.VideoCoverMode
import com.rawsmusic.module.data.prefs.VideoCoverPreferences
import com.rawsmusic.module.data.prefs.VideoCoverSearchCandidate
import com.rawsmusic.module.data.prefs.VideoCoverRemoteRepository
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.preference.SliderPreference
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
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
    onOpenSpectrumAnalysis: () -> Unit = {},
    realtimeSeparationEnabled: Boolean = false,
    realtimeSeparationPreparing: Boolean = false,
    realtimeSeparationStem: Int = 0,
    realtimeSeparationStrength: Float = 1f,
    realtimeSeparationStatus: String = "",
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit = {},
    onRealtimeSeparationStemChange: (Int) -> Unit = {},
    onRealtimeSeparationStrengthChange: (Float) -> Unit = {},
    isImmersiveEnabled: Boolean = true,
    onPlayerStyleChange: (Boolean) -> Unit = {},
    onOpenLandscapePlayer: () -> Unit = {},
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
    val videoCoverState by com.rawsmusic.module.data.prefs.VideoCoverPreferences.state.collectAsState()
    val videoCoverSongKey = currentSong?.path.orEmpty()
    val videoCoverUri = videoCoverState.resolve(videoCoverSongKey)
    LaunchedEffect(videoCoverSongKey) {
        com.rawsmusic.module.data.prefs.VideoCoverPreferences.onSongChanged(videoCoverSongKey)
    }
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
    FullCoverPredictiveBackHandler(
        enabled = queueVisible && queueFullscreen,
        onProgress = {},
        onCancelled = {},
        onCompleted = { queueFullscreen = false }
    )

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
            videoCoverUri = videoCoverUri,
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
            videoCoverUri = videoCoverUri,
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
            onPlayPause = onPlayPause,
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
            artworkTransitionState = artworkTransitionState,
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
                artworkTransitionState = artworkTransitionState,
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
                onOpenSpectrumAnalysis = onOpenSpectrumAnalysis,
                realtimeSeparationEnabled = realtimeSeparationEnabled,
                realtimeSeparationPreparing = realtimeSeparationPreparing,
                realtimeSeparationStem = realtimeSeparationStem,
                realtimeSeparationStrength = realtimeSeparationStrength,
                realtimeSeparationStatus = realtimeSeparationStatus,
                onRealtimeSeparationEnabledChange = onRealtimeSeparationEnabledChange,
                onRealtimeSeparationStemChange = onRealtimeSeparationStemChange,
                onRealtimeSeparationStrengthChange = onRealtimeSeparationStrengthChange,
                isImmersiveEnabled = isImmersiveEnabled,
                onPlayerStyleChange = onPlayerStyleChange,
                onOpenLandscapePlayer = onOpenLandscapePlayer,
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
    videoCoverUri: String? = null,
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
                videoCoverUri = videoCoverUri,
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
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        MiniLyricPreview(
                            song = lyricSong,
                            positionMs = lyricPositionMs,
                            isPlaying = isPlaying,
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
                    val context = LocalContext.current
                    val playlistStore = remember(context) { PlaylistStore.getInstance(context) }
                    val playlists by playlistStore.playlists.collectAsState()
                    val isFavorite = currentSong?.let(playlistStore::isFavorite) == true
                    val favoriteScope = rememberCoroutineScope()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                currentSong?.let { song ->
                                    favoriteScope.launch { playlistStore.toggleFavorite(song) }
                                }
                            },
                            enabled = currentSong != null
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_playlist_favorite_star),
                                contentDescription = stringResource(R.string.player_favorite),
                                tint = if (isFavorite) tone.icon else tone.iconSoft,
                                modifier = Modifier.size(30.dp)
                            )
                        }
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
            contentDescription = stringResource(R.string.player_queue_description),
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
                    onDoubleTap = onPlayPause,
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
    isPlaying: Boolean,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onClick: () -> Unit,
    primaryColor: Color = Color.White,
    secondaryColor: Color = Color.White.copy(alpha = 0.58f),
    dimColor: Color = Color.White.copy(alpha = 0.40f),
    maxHeight: Dp,
    maxPrimaryRows: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(maxHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        ComposeLyricView(
            song = song,
            positionMs = positionMs,
            isPlaying = isPlaying,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            textColor = primaryColor,
            dimColor = dimColor,
            secondaryColor = secondaryColor,
            fontSizeSp = 15,
            blurEnabled = false,
            karaokeGlowEnabled = AppPreferences.UI.lyricKaraokeGlowEnabled,
            karaokeLiftEnabled = AppPreferences.UI.lyricKaraokeLiftEnabled,
            primaryFontSizeRange = 12..20,
            secondaryFontSizeRange = 10..14,
            lineHorizontalPadding = 0.dp,
            compactLineSpacing = 6.dp,
            enforceCompactInferredLineLimit = false,
            maxPrimaryVisibleLines = maxPrimaryRows,
            onLineClick = { onClick() },
            modifier = Modifier
                .fillMaxSize()
                .miniLyricShortEdgeFade()
        )
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
    modifier: Modifier = Modifier,
    trackColor: Color? = null,
    fillColor: Color? = null,
    timeColor: Color? = null
) {
    val foregroundTone = rememberPlayerForegroundTone()
    val resolvedTrackColor = trackColor ?: foregroundTone.controlTrack
    val resolvedFillColor = fillColor ?: foregroundTone.controlFill
    val resolvedTimeColor = timeColor ?: foregroundTone.tertiary
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
                .height(28.dp)
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
                    .height(6.dp)
                    .offset(y = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(resolvedTrackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayFraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(resolvedFillColor)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AudioUtils.formatDuration(displayPositionMs), color = resolvedTimeColor, fontSize = 12.sp)
            Text(AudioUtils.formatDuration(totalDurationMs), color = resolvedTimeColor, fontSize = 12.sp)
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
    artworkTransitionState: PlaybackArtworkTransitionState? = null,
    artworkAlpha: Float = 1f,
    onArtworkBoundsChanged: (Rect) -> Unit = {},
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
    onOpenSpectrumAnalysis: () -> Unit = {},
    realtimeSeparationEnabled: Boolean = false,
    realtimeSeparationPreparing: Boolean = false,
    realtimeSeparationStem: Int = 0,
    realtimeSeparationStrength: Float = 1f,
    realtimeSeparationStatus: String = "",
    onRealtimeSeparationEnabledChange: (Boolean) -> Unit = {},
    onRealtimeSeparationStemChange: (Int) -> Unit = {},
    onRealtimeSeparationStrengthChange: (Float) -> Unit = {},
    isImmersiveEnabled: Boolean = false,
    onPlayerStyleChange: (Boolean) -> Unit = {},
    onOpenLandscapePlayer: () -> Unit = {},
    audioVisualizerEnabled: Boolean = false,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit = {},
    sleepTimerSelection: Int = 0,
    onSleepTimerSelectionChange: ((Int) -> Unit)? = null,
    artworkAnimationStyle: PlayerArtworkAnimationStyle? = null,
    onArtworkAnimationStyleChange: ((PlayerArtworkAnimationStyle) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteCoverSuccessText = stringResource(R.string.player_video_cover_remote_success)
    val remoteCoverFailedText = stringResource(R.string.player_video_cover_remote_failed)
    val videoCoverState by VideoCoverPreferences.state.collectAsState()
    val videoCoverSongKey = currentSong?.path.orEmpty()
    var showRemoteCoverDialog by rememberSaveable(videoCoverSongKey) { mutableStateOf(false) }
    var remoteCoverBusy by remember { mutableStateOf(false) }
    var remoteSearchBusy by remember { mutableStateOf(false) }
    var remotePreviewBusy by remember { mutableStateOf(false) }
    var remoteCandidates by remember { mutableStateOf<List<VideoCoverSearchCandidate>>(emptyList()) }
    var selectedRemoteCandidate by remember { mutableStateOf<VideoCoverSearchCandidate?>(null) }
    var remotePreviewUri by remember { mutableStateOf<String?>(null) }
    var remoteCoverMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showRemoteCoverDialog, videoCoverSongKey) {
        if (!showRemoteCoverDialog) {
            remoteSearchBusy = false
            remotePreviewBusy = false
            remoteCandidates = emptyList()
            selectedRemoteCandidate = null
            remotePreviewUri = null
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || videoCoverSongKey.isBlank()) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        VideoCoverPreferences.assign(uri.toString(), videoCoverSongKey)
    }
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
                        .onGloballyPositioned { coordinates ->
                            onArtworkBoundsChanged(coordinates.boundsInRoot())
                        }
                        .graphicsLayer {
                            alpha = artworkAlpha.coerceIn(0f, 1f)
                        }
                ) {
                    val sharedArtwork = artworkTransitionState?.let { state ->
                        state.foregroundArtworkBitmap()
                    }?.takeUnless { it.isRecycled }
                    if (sharedArtwork != null) {
                        Image(
                            bitmap = sharedArtwork.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (!coverPath.isNullOrBlank()) {
                        // Keep the More sheet tied to the current playback-art slot.
                        // This is only a cache fallback; the shared transition bitmap is preferred.
                        // Do not start a second fade/request path when the sheet appears.
                        BitmapImage(
                            key = coverPath,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            targetWidth = 220,
                            targetHeight = 220,
                            surface = ArtworkSurface.Playback,
                            fadeInMillis = 0,
                            holdPreviousOnKeyChange = true,
                            fadeOnBitmapChange = false,
                        )
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
                ImmersiveMoreActionButton(
                    iconRes = R.drawable.ic_landscape_player,
                    label = stringResource(R.string.player_more_landscape),
                    iconColor = actionIconColor,
                    cardColor = cardColor,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenLandscapePlayer
                )
            }
            // Keep file spectrum analysis out of the five compact actions above. It is a
            // secondary entry, so use the same sheet surface but no extra icon or cramped slot.
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.player_more_spectrum_analysis),
                color = scheme.onSurface,
                fontSize = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSpectrumAnalysis)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(20.dp))
            RealtimeVocalSeparationCard(
                enabled = realtimeSeparationEnabled,
                preparing = realtimeSeparationPreparing,
                stem = realtimeSeparationStem,
                strength = realtimeSeparationStrength,
                status = realtimeSeparationStatus,
                onEnabledChange = onRealtimeSeparationEnabledChange,
                onStemChange = onRealtimeSeparationStemChange,
                onStrengthChange = onRealtimeSeparationStrengthChange,
            )
            Spacer(Modifier.height(18.dp))
            val standardStyleLabel = stringResource(R.string.player_style_standard)
            val immersiveStyleLabel = stringResource(R.string.player_style_immersive)
            val playerStyleEntry = DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = standardStyleLabel,
                        selected = !isImmersiveEnabled,
                        onClick = { onPlayerStyleChange(false) }
                    ),
                    DropdownItem(
                        text = immersiveStyleLabel,
                        selected = isImmersiveEnabled,
                        onClick = { onPlayerStyleChange(true) }
                    )
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
            ) {
                RawWindowDropdownPreference(
                    entry = playerStyleEntry,
                    title = stringResource(R.string.player_style_title),
                    summary = if (isImmersiveEnabled) immersiveStyleLabel else standardStyleLabel,
                    showValue = true,
                    maxHeight = 260.dp,
                    collapseOnSelection = true
                )
            }
            Spacer(Modifier.height(18.dp))
            VideoCoverSettingsCard(
                state = videoCoverState,
                songKey = videoCoverSongKey,
                onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
                onImportRemote = {
                    remoteCoverMessage = null
                    showRemoteCoverDialog = true
                }
            )
            Spacer(Modifier.height(18.dp))
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
                    RawWindowDropdownPreference(
                        entry = sleepDropdown,
                        title = stringResource(R.string.player_sleep_timer),
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
                    RawWindowDropdownPreference(
                        entry = animationDropdown,
                        title = stringResource(R.string.player_artwork_animation),
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
        if (showRemoteCoverDialog) {
            VideoCoverRemoteImportDialog(
                title = currentSong?.displayName.orEmpty(),
                artist = currentSong?.artist.orEmpty(),
                album = currentSong?.album.orEmpty(),
                busy = remoteCoverBusy,
                message = remoteCoverMessage,
                candidates = remoteCandidates,
                searchBusy = remoteSearchBusy,
                previewBusy = remotePreviewBusy,
                selectedCandidate = selectedRemoteCandidate,
                previewUri = remotePreviewUri,
                onDismiss = { showRemoteCoverDialog = false },
                onSearch = {
                    selectedRemoteCandidate = null
                    remotePreviewUri = null
                    remoteSearchBusy = true
                    remoteCoverMessage = null
                    scope.launch {
                        VideoCoverRemoteRepository.searchCandidates(
                            artist = currentSong?.artist.orEmpty(),
                            album = currentSong?.album.orEmpty(),
                            title = currentSong?.displayName.orEmpty(),
                        ).onSuccess { matches ->
                            remoteCandidates = matches
                            remoteCoverMessage = if (matches.isEmpty()) {
                                context.getString(R.string.player_video_cover_remote_no_candidates)
                            } else {
                                context.getString(R.string.player_video_cover_remote_candidates_found, matches.size)
                            }
                        }.onFailure { error ->
                            remoteCoverMessage = error.message
                                ?.takeIf(String::isNotBlank)
                                ?: remoteCoverFailedText
                        }
                        remoteSearchBusy = false
                    }
                },
                onSelectCandidate = { candidate ->
                    selectedRemoteCandidate = candidate
                    remotePreviewUri = null
                    remotePreviewBusy = true
                    remoteCoverMessage = null
                    scope.launch {
                        VideoCoverRemoteRepository.import(
                            context = context,
                            artist = candidate.artist,
                            album = candidate.album,
                            title = candidate.title,
                            input = candidate.selectionUrl,
                        ).onSuccess { uri ->
                            remotePreviewUri = uri
                            remoteCoverMessage = context.getString(
                                R.string.player_video_cover_remote_preview_ready,
                            )
                        }.onFailure { error ->
                            remoteCoverMessage = error.message
                                ?.takeIf(String::isNotBlank)
                                ?: context.getString(R.string.player_video_cover_remote_preview_unavailable)
                        }
                        remotePreviewBusy = false
                    }
                },
                onImport = { candidate, previewUri ->
                    if (candidate != null) {
                        if (!previewUri.isNullOrBlank()) {
                            VideoCoverPreferences.assign(previewUri, videoCoverSongKey)
                            remoteCoverMessage = remoteCoverSuccessText
                        } else {
                            // The artwork shown for a candidate is only a static identity preview.
                            // Do not repeat the same resolver request after it already reported that
                            // this release has no importable motion asset.
                            remoteCoverMessage = context.getString(
                                R.string.player_video_cover_remote_preview_unavailable,
                            )
                        }
                    } else {
                        remoteCoverBusy = true
                        remoteCoverMessage = null
                        scope.launch {
                            VideoCoverRemoteRepository.import(
                                context = context,
                                artist = currentSong?.artist.orEmpty(),
                                album = currentSong?.album.orEmpty(),
                                title = currentSong?.displayName.orEmpty(),
                                input = candidate?.selectionUrl.orEmpty(),
                            ).onSuccess { uri ->
                                VideoCoverPreferences.assign(uri, videoCoverSongKey)
                                remoteCoverMessage = remoteCoverSuccessText
                            }.onFailure { error ->
                                remoteCoverMessage = error.message
                                    ?.takeIf(String::isNotBlank)
                                    ?: remoteCoverFailedText
                            }
                            remoteCoverBusy = false
                        }
                    }
                },
                onApplyPreview = {
                    val preview = remotePreviewUri
                    if (selectedRemoteCandidate != null && !preview.isNullOrBlank()) {
                        VideoCoverPreferences.assign(preview, videoCoverSongKey)
                        remoteCoverMessage = remoteCoverSuccessText
                    }
                },
            )
        }
}

@Composable
private fun RealtimeVocalSeparationCard(
    enabled: Boolean,
    preparing: Boolean,
    stem: Int,
    strength: Float,
    status: String,
    onEnabledChange: (Boolean) -> Unit,
    onStemChange: (Int) -> Unit,
    onStrengthChange: (Float) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var localEnabled by remember { mutableStateOf(enabled) }
    var localStem by remember { mutableIntStateOf(stem) }
    var localStrength by remember { mutableFloatStateOf(strength.coerceIn(0f, 1f)) }
    var strengthDragging by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        localEnabled = enabled
    }
    LaunchedEffect(stem) {
        localStem = stem
    }
    LaunchedEffect(strength, strengthDragging) {
        if (!strengthDragging) {
            localStrength = strength.coerceIn(0f, 1f)
        }
    }
    val summary = if (localEnabled && !enabled) {
        stringResource(R.string.player_realtime_separation_loading_model)
    } else when (status) {
        "phase:LOADING_MODEL" ->
            stringResource(R.string.player_realtime_separation_loading_model)
        "phase:BUFFERING_AUDIO" ->
            stringResource(R.string.player_realtime_separation_buffering_audio)
        "phase:RUNNING_MODEL" ->
            stringResource(R.string.player_realtime_separation_running_model)
        "phase:ACTIVE" ->
            stringResource(R.string.player_realtime_separation_active)
        "phase:IDLE" ->
            stringResource(R.string.player_realtime_separation_off)
        else -> when {
            status.isNotBlank() -> status
            preparing -> stringResource(R.string.player_realtime_separation_preparing)
            localEnabled -> stringResource(R.string.player_realtime_separation_active)
            else -> stringResource(R.string.player_realtime_separation_off)
        }
    }
    val cardEnabled = localEnabled
    val toggleSubtitle = when {
        cardEnabled -> summary
        else -> stringResource(R.string.player_realtime_separation_off)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.52f))
            .padding(12.dp)
    ) {
        ImmersiveSettingToggleRow(
            title = stringResource(R.string.player_realtime_separation),
            subtitle = toggleSubtitle,
            enabled = true,
            checked = cardEnabled,
            onClick = {
                val next = !cardEnabled
                localEnabled = next
                onEnabledChange(next)
            },
        )
        AnimatedVisibility(visible = cardEnabled) {
            Column {
                Spacer(Modifier.height(12.dp))
                val vocalLabel = stringResource(R.string.player_realtime_separation_vocals)
                val instrumentalLabel =
                    stringResource(R.string.player_realtime_separation_instrumental)
                Text(
                    text = stringResource(R.string.player_realtime_separation_stem),
                    color = scheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0 to vocalLabel, 1 to instrumentalLabel).forEach { (mode, label) ->
                        val selected = localStem == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) {
                                        scheme.primary.copy(alpha = 0.18f)
                                    } else {
                                        scheme.onSurface.copy(alpha = 0.07f)
                                    }
                                )
                                .clickable {
                                    localStem = mode
                                    onStemChange(mode)
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = if (selected) scheme.primary else scheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = if (selected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.player_realtime_separation_strength),
                            color = scheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(
                                R.string.player_realtime_separation_strength_summary
                            ),
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        text = "${(localStrength * 100f).toInt()}%",
                        color = scheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Slider(
                    value = localStrength,
                    onValueChange = { value ->
                        strengthDragging = true
                        localStrength = value
                        onStrengthChange(value)
                    },
                    onValueChangeFinished = {
                        strengthDragging = false
                    },
                    valueRange = 0f..1f,
                    enabled = cardEnabled && !preparing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun VideoCoverSettingsCard(
    state: com.rawsmusic.module.data.prefs.VideoCoverState,
    songKey: String,
    onPickVideo: () -> Unit,
    onImportRemote: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val currentUri = state.resolve(songKey)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.52f))
            .padding(12.dp)
    ) {
        ImmersiveSettingToggleRow(
            title = stringResource(R.string.player_video_cover),
            subtitle = stringResource(R.string.player_video_cover_summary),
            enabled = true,
            checked = state.enabled,
            onClick = { VideoCoverPreferences.setEnabled(!state.enabled) }
        )
        Spacer(Modifier.height(10.dp))
        val modeTitles = mapOf(
            VideoCoverMode.PERMANENT to stringResource(R.string.player_video_cover_permanent),
            VideoCoverMode.CURRENT to stringResource(R.string.player_video_cover_current),
            VideoCoverMode.TEMPORARY to stringResource(R.string.player_video_cover_temporary)
        )
        val modeDropdown = DropdownEntry(
            items = VideoCoverMode.entries.map { mode ->
                DropdownItem(
                    text = modeTitles.getValue(mode),
                    selected = mode == state.mode,
                    onClick = { VideoCoverPreferences.setMode(mode) }
                )
            }
        )
        RawWindowDropdownPreference(
            entry = modeDropdown,
            title = stringResource(R.string.player_video_cover_mode),
            summary = when (state.mode) {
                VideoCoverMode.PERMANENT -> stringResource(R.string.player_video_cover_permanent_summary)
                VideoCoverMode.CURRENT -> stringResource(R.string.player_video_cover_current_summary)
                VideoCoverMode.TEMPORARY -> stringResource(R.string.player_video_cover_temporary_summary)
            },
            showValue = false,
            maxHeight = 360.dp,
            collapseOnSelection = true
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.primary.copy(alpha = 0.14f))
                    .clickable(enabled = songKey.isNotBlank(), onClick = onPickVideo)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (currentUri == null) {
                        stringResource(R.string.player_video_cover_select)
                    } else {
                        stringResource(R.string.player_video_cover_replace)
                    },
                    color = scheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (currentUri != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.onSurfaceVariantSummary.copy(alpha = 0.12f))
                        .clickable { VideoCoverPreferences.clearForMode(songKey) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.player_video_cover_clear),
                        color = scheme.onSurface,
                        fontSize = 14.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.primary.copy(alpha = 0.14f))
                .clickable(enabled = songKey.isNotBlank(), onClick = onImportRemote)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.player_video_cover_remote_import),
                color = scheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (currentUri != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.primary.copy(alpha = 0.14f))
                    .clickable {
                        context.startService(
                            Intent("com.rawsmusic.action.PAUSE")
                                .setPackage(context.packageName)
                        )
                        context.startActivity(
                            Intent("com.rawsmusic.action.OPEN_MUSIC_VIDEO")
                                .setPackage(context.packageName)
                                .putExtra("video_uri", currentUri)
                                .putExtra("video_title", songKey.substringAfterLast('/'))
                        )
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.player_music_video_open),
                    color = scheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun VideoCoverRemoteImportDialog(
    title: String,
    artist: String,
    album: String,
    busy: Boolean,
    message: String?,
    candidates: List<VideoCoverSearchCandidate>,
    searchBusy: Boolean,
    previewBusy: Boolean,
    selectedCandidate: VideoCoverSearchCandidate?,
    previewUri: String?,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onSelectCandidate: (VideoCoverSearchCandidate) -> Unit,
    onImport: (VideoCoverSearchCandidate?, String?) -> Unit,
    onApplyPreview: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val hasMetadata = title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()
    val canApplyPreview = !previewUri.isNullOrBlank()
    val canImport = !busy && !searchBusy && !previewBusy && hasMetadata &&
        (selectedCandidate == null || canApplyPreview)
    val dialogScrollState = rememberScrollState()
    val maxDialogContentHeight = (LocalWindowInfo.current.containerDpSize.height - 96.dp)
        .coerceAtLeast(360.dp)
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.player_video_cover_remote_title),
        summary = stringResource(R.string.player_video_cover_remote_summary),
        backgroundColor = scheme.surface,
        onDismissRequest = if (busy) null else onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxDialogContentHeight)
                .clipToBounds()
                .verticalScroll(dialogScrollState, enabled = false)
                // DialogContentLayout consumes the normal gesture pass. Handle the outer
                // viewport in the final pass so both the dialog and its child rows remain usable.
                .pointerInput(busy, searchBusy, previewBusy, candidates.size) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Final,
                        )
                        var lastY = 0f
                        var released = false
                        var initialized = false
                        while (!released) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull() ?: break
                            if (!initialized) {
                                lastY = change.position.y
                                initialized = true
                            }
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            if (dy != 0f) {
                                dialogScrollState.dispatchRawDelta(-dy)
                            }
                            released = !change.pressed
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.player_video_cover_remote_metadata_title),
                color = scheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(
                        R.string.player_video_cover_remote_title_value,
                        title.ifBlank { stringResource(R.string.player_no_song) },
                    ),
                    color = scheme.onSurface,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.player_video_cover_remote_artist_value,
                        artist.ifBlank { stringResource(R.string.player_unknown_artist) },
                    ),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.player_video_cover_remote_album_value,
                        album.ifBlank { stringResource(R.string.player_unknown_album) },
                    ),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.player_video_cover_remote_source_note),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_video_cover_remote_candidates_title),
                    color = scheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (searchBusy) {
                        stringResource(R.string.player_video_cover_remote_searching)
                    } else {
                        stringResource(R.string.player_video_cover_remote_search)
                    },
                    enabled = !busy && !searchBusy && !previewBusy && hasMetadata,
                    onClick = onSearch,
                )
            }
            if (candidates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { candidate ->
                        val selected = candidate.id == selectedCandidate?.id
                        val artworkRequest = remember(candidate.artworkUrl) {
                            candidate.artworkUrl.takeIf(String::isNotBlank)?.let { artworkUrl ->
                                ImageRequest.Builder(context)
                                    .data(artworkUrl)
                                    .crossfade(false)
                                    .build()
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) scheme.primary.copy(alpha = 0.16f)
                                    else scheme.surfaceContainerHigh.copy(alpha = 0.45f)
                                )
                                .padding(8.dp)
                                .pointerInput(candidate.id, busy, previewBusy) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var lastY = 0f
                                        var totalDy = 0f
                                        var initialized = false
                                        var released = false
                                        while (!released) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull() ?: break
                                            if (!initialized) {
                                                lastY = change.position.y
                                                initialized = true
                                            }
                                            val dy = change.position.y - lastY
                                            lastY = change.position.y
                                            totalDy += dy
                                            released = !change.pressed
                                        }
                                        if (kotlin.math.abs(totalDy) < 12f && !busy && !previewBusy) {
                                            onSelectCandidate(candidate)
                                        }
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(62.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(scheme.surfaceContainerHigh),
                            ) {
                                if (artworkRequest != null) {
                                    AsyncImage(
                                        model = artworkRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = candidate.title.ifBlank { stringResource(R.string.player_no_song) },
                                    color = scheme.onSurface,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = candidate.artist,
                                    color = scheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${candidate.album} · ${candidate.provider}",
                                    color = scheme.onSurfaceVariantSummary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (selectedCandidate != null) {
                val selectedArtworkRequest = remember(selectedCandidate.artworkUrl) {
                    selectedCandidate.artworkUrl.takeIf(String::isNotBlank)?.let { artworkUrl ->
                        ImageRequest.Builder(context)
                            .data(artworkUrl)
                            .crossfade(false)
                            .build()
                    }
                }
                Text(
                    text = stringResource(R.string.player_video_cover_remote_preview_title),
                    color = scheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(172.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .pointerInput(previewUri, previewBusy, selectedCandidate) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var lastPosition = down.position
                                var moved = false
                                var released = false
                                while (!released) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.position != lastPosition) moved = true
                                    lastPosition = change.position
                                    change.consume()
                                    released = !change.pressed
                                }
                                if (!moved && !previewBusy && !previewUri.isNullOrBlank()) {
                                    onApplyPreview()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        previewUri != null -> FfmpegVideoCover(
                            uri = previewUri,
                            active = true,
                            cornerRadiusDp = 16f,
                        )
                        previewBusy -> Text(
                            text = stringResource(R.string.player_video_cover_remote_preview_loading),
                            color = Color.White.copy(alpha = 0.86f),
                            fontSize = 13.sp,
                        )
                        selectedArtworkRequest != null -> AsyncImage(
                            model = selectedArtworkRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Text(
                            text = stringResource(R.string.player_video_cover_remote_preview_unavailable),
                            color = Color.White.copy(alpha = 0.86f),
                            fontSize = 13.sp,
                        )
                    }
                    if (!previewBusy && !previewUri.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.player_video_cover_remote_preview_apply_hint),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp),
                        )
                    }
                    if (!previewBusy && previewUri.isNullOrBlank() && selectedArtworkRequest != null) {
                        Text(
                            text = stringResource(R.string.player_video_cover_remote_preview_static_hint),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    color = scheme.primary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.player_video_cover_remote_cancel),
                    enabled = !busy,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (busy) stringResource(R.string.player_video_cover_remote_importing)
                    else stringResource(R.string.player_video_cover_remote_import),
                    enabled = canImport,
                    onClick = { if (canImport) onImport(selectedCandidate, previewUri) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
        RawWindowDropdownPreference(
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
                .size(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardColor),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                colorFilter = ColorFilter.tint(iconColor),
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = MiuixTheme.colorScheme.onSurface, fontSize = 13.sp, maxLines = 1)
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
                .size(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardColor),
            contentAlignment = Alignment.Center
        ) {
            AudioVisualizerToggleGlyph(
                locked = enabled,
                tint = iconColor,
                animateOnEnter = true,
                modifier = Modifier.size(29.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.player_more_visualizer),
            color = labelColor,
            fontSize = 13.sp,
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
