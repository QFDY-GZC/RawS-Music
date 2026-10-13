package com.rawsmusic.core.ui.widget.player

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricTimingEditorTarget
import com.rawsmusic.core.ui.widget.resolvePlayerSurfaceContentOffsetY
import com.rawsmusic.core.ui.widget.resolvePlayerSurfaceOwnershipAlpha
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkAnimationStyle
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.text.SharedMarqueeText
import com.rawsmusic.core.ui.widget.text.resolveNativeTextLaneHeight
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.AudioInfoCapsulePreferences
import com.rawsmusic.module.data.prefs.PlayerProgressPreferences
import com.rawsmusic.module.data.prefs.VideoCoverMode
import com.rawsmusic.module.data.prefs.VideoCoverPreferences
import com.rawsmusic.module.data.prefs.VideoCoverSearchCandidate
import com.rawsmusic.module.data.prefs.VideoCoverRemoteRepository
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.preference.SliderPreference
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.widget.flow.RawBackgroundStyle
import com.rawsmusic.core.ui.widget.flow.RawFlowTuningState
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import kotlin.math.abs
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding

const val SLEEP_TIMER_OPEN_PANEL_REQUEST = -10_000

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
    val isDark = RawFlowTuningState.style == RawBackgroundStyle.STATIC ||
        scheme.background.luminance() < 0.5f
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
    progressState: State<Float>? = null,
    isTransitioning: Boolean,
    mainPlayerTransition: Boolean = false,
    mainSheetExpansionState: State<Float>? = null,
    collapsedMiniTopPx: Float = 0f,
    externallyOwnedClearArtwork: Boolean = false,
    currentSong: AudioFile?,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    audioVisualizerEnabled: Boolean,
    audioSpectrum: FloatArray,
    audioSpectrumState: State<FloatArray>? = null,
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
    audioInfoPlaybackChainText: String = "",
    smartTransitionVisible: Boolean = false,
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
    onAiTimingPreview: () -> Unit = {},
    onOpenExternalTimingEditor: (LyricTimingEditorTarget) -> Unit = {},
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
    // Retain one progress State and consume it only from layer/draw phases. Previously basePage was
    // a Float read in this composition, so every 120 Hz scene tick recomposed the backdrop plus all
    // three heavyweight pages (album/player/lyrics). The absolute page is now a derived State;
    // ordinary page content stays composition-stable while RenderNode properties move.
    val pageProgressState = remember(
        currentScene,
        fromScene,
        toScene,
        isTransitioning,
        progressState,
        progress,
    ) {
        derivedStateOf {
            if (isTransitioning) {
                lerp(
                    scenePageIndex(fromScene),
                    scenePageIndex(toScene),
                    (progressState?.value ?: progress).coerceIn(0f, 1f),
                )
            } else {
                scenePageIndex(currentScene).toFloat()
            }
        }
    }
    val settledPageProgress = scenePageIndex(currentScene).toFloat()
    // Visibility is owned by ComposePlayerContainer rather than this horizontally moving page
    // stack, so a page recomposition cannot reset the modal immediately after it opens.
    val progressStyleValue by PlayerProgressPreferences.progressStyle.collectAsState()
    val immersiveProgressStyle = ImmersiveProgressStyle.from(progressStyleValue)
    val climaxEnabled by PlayerProgressPreferences.climaxEnabled.collectAsState()
    val waveformBarCount by PlayerProgressPreferences.waveformBarCount.collectAsState()
    val waveformColorMode by PlayerProgressPreferences.waveformColorMode.collectAsState()
    val customWaveformRemainingColorInt by PlayerProgressPreferences.remainingColor.collectAsState()
    val customWaveformPlayedColorInt by PlayerProgressPreferences.playedColor.collectAsState()
    val waveformClimaxColorInt by PlayerProgressPreferences.climaxColor.collectAsState()
    val waveformAlbumAccent = rememberCoverAccentColor(coverPath)
    val waveformRemainingColor = if (waveformColorMode == PlayerProgressPreferences.COLOR_MODE_ALBUM_ART) {
        waveformAlbumAccent.copy(alpha = 0.90f)
    } else Color(customWaveformRemainingColorInt)
    val waveformPlayedColor = if (waveformColorMode == PlayerProgressPreferences.COLOR_MODE_ALBUM_ART) {
        waveformAlbumAccent.copy(alpha = 0.30f)
    } else Color(customWaveformPlayedColorInt)
    var queueFullscreen by remember { mutableStateOf(false) }
    var immersiveMoreAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var immersiveMoreMounted by remember { mutableStateOf(false) }
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
        PlayerProgressPreferences.progressStyleValue = style.value
    }

    fun closePlayerMore() {
        onShowPlayerMoreChange(false)
    }
    val addSongToPlaylist = rememberPlayerPlaylistAction()

    LaunchedEffect(showPlayerMore, immersiveMoreMounted, queueFullscreen, queueVisible) {
        if (queueVisible && queueFullscreen) {
            onModalDismissActionChange { queueFullscreen = false }
            onMorePanelVisibleChange(true)
        } else if (showPlayerMore) {
            onModalDismissActionChange(::closePlayerMore)
            onMorePanelVisibleChange(true)
        } else if (immersiveMoreMounted) {
            onModalDismissActionChange({})
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

    val verticalGate = LocalImmersiveVerticalGestureGate.current
        ?: remember { ImmersiveVerticalGestureGate() }
    val verticalMotion = remember(artworkTransitionState) { ImmersiveVerticalTrackMotion(artworkTransitionState) }
    var verticalSeekActive by remember { mutableStateOf(false) }
    val verticalEnabled = AppPreferences.UI.immersiveVerticalTrackSwipeEnabled &&
        !AppPreferences.UI.playerSwipeToChangeTrackDisabled && currentScene == PlayerSceneController.Scene.PLAYER
    val latestVerticalEligible by rememberUpdatedState(
        verticalEnabled && !isTransitioning && !queueVisible && !showPlayerMore &&
            !immersiveMoreMounted && !verticalSeekActive,
    )
    val latestVerticalSong by rememberUpdatedState(currentSong)
    val latestVerticalCover by rememberUpdatedState(coverPath)
    val latestVerticalQueue by rememberUpdatedState(queueSongs)
    val latestVerticalPrevious by rememberUpdatedState(onPrevious)
    val latestVerticalNext by rememberUpdatedState(onNext)
    SideEffect { verticalGate.enabled = verticalEnabled }
    DisposableEffect(verticalGate) {
        onDispose {
            verticalGate.enabled = false
            if (verticalMotion.presentation != null) artworkTransitionState.cancelGesture()
        }
    }
    LaunchedEffect(verticalMotion, artworkTransitionState) {
        snapshotFlow {
            val presentation = verticalMotion.presentation
            val idle = !artworkTransitionState.isGestureActive && !artworkTransitionState.isSettling
            presentation != null && idle && if (verticalMotion.committed) {
                latestVerticalSong?.coverKey == presentation.target.coverKey ||
                    (latestVerticalSong?.coverKey != presentation.source.coverKey &&
                        latestVerticalSong?.coverKey != presentation.target.coverKey)
            } else artworkTransitionState.ratio <= 0.0001f
        }.collect { finished ->
            if (finished) verticalMotion.presentation = null
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()
        .onGloballyPositioned { verticalGate.stackOrigin = it.boundsInRoot().topLeft }
        .immersiveVerticalTrackGesture(verticalGate, verticalMotion, artworkTransitionState,
            eligible = { latestVerticalEligible }, currentSong = { latestVerticalSong },
            coverPath = { latestVerticalCover }, queue = { latestVerticalQueue },
            onPrevious = { latestVerticalPrevious() }, onNext = { latestVerticalNext() })) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        // MAIN <-> PLAYER has one absolute sheet clock; page-to-page horizontal motion retains
        // its existing independent clock. Read the sheet state only in RenderNode/draw phases.
        fun mainSheetFraction(): Float =
            if (mainPlayerTransition) (mainSheetExpansionState?.value ?: 1f)
                .coerceIn(0f, 1f) else 1f
        fun Modifier.pageLayer(page: Int): Modifier {
            return graphicsLayer {
                val distance = page - pageProgressState.value
                translationX = distance * widthPx
                val reveal = mainSheetFraction()
                alpha = if (mainPlayerTransition) reveal else 1f
                val populationScale = if (mainPlayerTransition) 1.25f - 0.25f * reveal else 1f
                scaleX = populationScale
                scaleY = populationScale
                translationY = if (mainPlayerTransition) {
                    resolvePlayerSurfaceContentOffsetY(collapsedMiniTopPx, reveal)
                } else 0f
            }
        }
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (mainPlayerTransition) {
                    resolvePlayerSurfaceOwnershipAlpha(mainSheetFraction())
                } else 1f
            },
        ) {
        ImmersiveBackdrop(
            coverPath = coverPath,
            videoCoverUri = videoCoverUri,
            pageProgress = settledPageProgress,
            pageProgressState = pageProgressState,
            artworkTransitionState = artworkTransitionState,
            clearArtworkVisible = !(queueVisible && queueFullscreen),
            motionEnabled = isPlaying || isTransitioning,
            verticalMotion = verticalMotion,
            externallyOwnedClearArtwork = externallyOwnedClearArtwork,
            onClearArtworkBoundsChanged = { verticalGate.artworkBounds = it },
        )
        }
        val visualizerPageEligible by remember(pageProgressState) {
            derivedStateOf { pageProgressState.value >= 0.75f }
        }
        SharedRealtimeVisualizerOverlay(
            style = LocalAudioVisualizerStyle.current,
            spectrum = audioSpectrum,
            spectrumState = audioSpectrumState,
            visible =
                audioVisualizerEnabled &&
                    LocalAudioVisualizerStyle.current != AudioVisualizerStyle.Spectrum &&
                    !queueVisible &&
                    !showPlayerMore &&
                    visualizerPageEligible,
            isPlaying = isPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .height((maxHeight * 0.34f).coerceAtMost(240.dp))
                .align(Alignment.BottomCenter)
                .clipToBounds()
                .graphicsLayer {
                    alpha = if (mainPlayerTransition) mainSheetFraction() else 1f
                },
        )
        ImmersiveAlbumInfoPage(
            currentSong = currentSong,
            songs = albumSongs,
            coverPath = albumCoverPath ?: coverPath,
            motionEnabled = isPlaying || isTransitioning,
            onBack = { },
            onOpenMetadata = onOpenMetadata,
            onSongClick = onAlbumSongClick,
            pageProgress = 0f,
            renderBackdrop = false,
            renderTopBar = false,
            modifier = Modifier.pageLayer(0)
        )
        @Composable
        fun TrackPage(song: AudioFile?, artworkKey: String?, incoming: Boolean, layer: Modifier) {
        val liveIdentity = !incoming && song?.coverKey == currentSong?.coverKey
        ImmersivePlayerMainPage(
            currentSong = song,
            coverPath = artworkKey,
            videoCoverUri = videoCoverUri,
            artworkTransitionState = artworkTransitionState,
            isPlaying = isPlaying,
            currentPositionMs = if (liveIdentity) currentPositionMs else 0L,
            totalDurationMs = if (liveIdentity) totalDurationMs else song?.duration ?: 0L,
            audioVisualizerEnabled = audioVisualizerEnabled && liveIdentity,
            audioSpectrum = audioSpectrum,
            audioSpectrumState = audioSpectrumState,
            audioVisualizerForeground = audioVisualizerForeground,
            onAudioVisualizerDismiss = onAudioVisualizerDismiss,
            previousIconRes = previousIconRes,
            playIconRes = playIconRes,
            pauseIconRes = pauseIconRes,
            nextIconRes = nextIconRes,
            playModeIconRes = playModeIconRes,
            lyricSong = if (liveIdentity) lyricSong else null,
            lyricPositionMs = lyricPositionMs,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            audioInfoText = if (liveIdentity) audioInfoText else "",
            audioInfoPlaybackChainText = if (liveIdentity) audioInfoPlaybackChainText else "",
            smartTransitionVisible = smartTransitionVisible && liveIdentity,
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
            onSeekStart = { verticalSeekActive = true; onSeekStart() },
            onSeekStop = { value ->
                try { onSeekStop(value) } finally { verticalSeekActive = false }
            },
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPlayMode = onPlayMode,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            progressStyle = immersiveProgressStyle,
            climaxEnabled = climaxEnabled,
            waveformBarCount = waveformBarCount,
            waveformRemainingColor = waveformRemainingColor,
            waveformPlayedColor = waveformPlayedColor,
            waveformClimaxColor = Color(waveformClimaxColorInt),
            onOpenMore = {
                onShowPlayerMoreChange(true)
            },
            onMoreBoundsChanged = { if (!incoming) immersiveMoreAnchorBounds = it },
            onOpenLyric = onOpenLyric,
            onInlineLyricSeek = if (com.rawsmusic.core.ui.widget.usesInlinePlayerLyrics(
                androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp,
                com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile.current.windowWidth.value,
                com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile.current.windowHeight.value,
            )) onLyricSeek else null,
            onArtworkLongPress = onArtworkLongPress,
            pageProgress = 1f,
            renderBackdrop = false,
            renderTopBar = false,
            previewOnly = incoming,
            modifier = layer,
        )
        }
        val verticalPresentation = verticalMotion.presentation
        TrackPage(verticalPresentation?.source ?: currentSong,
            verticalPresentation?.sourceCover ?: coverPath, false,
            Modifier.pageLayer(1).graphicsLayer {
                translationY = verticalMotion.translation(heightPx, incoming = false)
            })
        if (verticalPresentation != null) {
            TrackPage(verticalPresentation.target, verticalPresentation.target.coverKey, true,
                Modifier.pageLayer(1).graphicsLayer {
                    translationY = verticalMotion.translation(heightPx, incoming = true)
                })
        }
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
            onAiTimingPreview = onAiTimingPreview,
            onOpenExternalTimingEditor = onOpenExternalTimingEditor,
            onModalVisibleChange = onMorePanelVisibleChange,
            onModalDismissActionChange = onModalDismissActionChange,
            onBack = { },
            showHeaderCover = true,
            renderBackdrop = false,
            artworkTransitionState = artworkTransitionState,
            audioVisualizerEnabled = audioVisualizerEnabled,
            onAudioVisualizerEnabledChange = onAudioVisualizerEnabledChange,
            modifier = Modifier.pageLayer(2)
        )
        AnimatedVisibility(
            visible = !queueVisible,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(140)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ImmersiveTopBar(
                pageProgress = settledPageProgress,
                pageProgressState = pageProgressState,
                showPageDots = isImmersiveHorizontalPagingIndicatorVisible(
                    isTransitioning = isTransitioning,
                    fromScene = fromScene,
                    toScene = toScene
                ),
                modifier = Modifier
                    .rawStableStatusBarsPadding()
                    .padding(start = 30.dp, end = 30.dp, top = 2.dp)
                    .graphicsLayer {
                        alpha = if (mainPlayerTransition) mainSheetFraction() else 1f
                    }
            )
        }

        PlayerAnchoredGlassPopup(
            show = showPlayerMore,
            onMountedChange = { immersiveMoreMounted = it },
            anchorBounds = immersiveMoreAnchorBounds,
            sourceArtworkBounds = null,
            artwork = artworkTransitionState?.foregroundArtworkBitmap(),
            onDismiss = ::closePlayerMore,
        ) { _, _ ->
            ImmersiveMoreSheet(
                currentSong = currentSong,
                onAddSongToPlaylist = addSongToPlaylist,
                coverPath = coverPath,
                artworkTransitionState = artworkTransitionState,
                progressStyle = immersiveProgressStyle,
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
                sleepTimerSelection = sleepTimerSelection,
                onSleepTimerSelectionChange = onSleepTimerSelectionChange,
                inlineLyricsOptions = if (com.rawsmusic.core.ui.widget.usesInlinePlayerLyrics(
                    androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp,
                    com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile.current.windowWidth.value,
                    com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile.current.windowHeight.value,
                )) {
                    { InlinePlayerLyricOptions(currentSong, coverPath, lyricSong, displayTranslation,
                        displayRoma, InlinePlayerLyricActions(onLyricTranslationToggle, onLyricRomaToggle,
                            onLyricModifyAlbumArt, onSearchLyrico, onOpenInLyrico, onAiTimingPreview,
                            onOpenExternalTimingEditor), ::closePlayerMore) }
                } else null,
            )
        }
    }
}

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
    audioSpectrumState: State<FloatArray>? = null,
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
    audioInfoPlaybackChainText: String = "",
    smartTransitionVisible: Boolean = false,
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
    waveformBarCount: Int = 160,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onOpenMore: () -> Unit,
    onMoreBoundsChanged: (Rect) -> Unit,
    onOpenLyric: () -> Unit = {},
    onInlineLyricSeek: ((Long) -> Unit)? = null,
    onArtworkLongPress: () -> Unit = {},
    pageProgress: Float = 1f,
    renderBackdrop: Boolean = true,
    renderTopBar: Boolean = true,
    previewOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val visualizerStyle = LocalAudioVisualizerStyle.current
    val windowProfile = com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile.current
    val landscape = windowProfile.windowWidth > windowProfile.windowHeight
    Box(modifier = modifier.fillMaxSize()) {
        val tone = rememberPlayerForegroundTone()
        val audioInfoCapsuleVisible by AudioInfoCapsulePreferences.visible.collectAsState()
        val playerTitlePosition = LyricTextPosition.from(AppPreferences.UI.playerTitleAlignment)
        val miniLyricPosition = LyricTextPosition.from(AppPreferences.UI.miniLyricAlignment)
        val playerTitleTextAlign = when (playerTitlePosition) {
            LyricTextPosition.Left -> TextAlign.Left
            LyricTextPosition.Center -> TextAlign.Center
            LyricTextPosition.Right -> TextAlign.Right
        }
        val statusBarHeight = rawStableStatusBarTopPadding()
        val navigationBarHeight = rawStableNavigationBottomPadding()
        val contentVerticalPadding = 14.dp
        if (renderBackdrop) {
            ImmersiveBackdrop(
                coverPath = coverPath,
                videoCoverUri = videoCoverUri,
                pageProgress = pageProgress,
                artworkTransitionState = artworkTransitionState,
                clearArtworkVisible = !(queueVisible && queueFullscreen),
                motionEnabled = isPlaying,
            )
        }
        if (
            audioVisualizerEnabled &&
            visualizerStyle == AudioVisualizerStyle.Spectrum &&
            audioVisualizerForeground &&
            !queueVisible
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .then(if (landscape) Modifier.width(maxWidth * IMMERSIVE_CLEAR_ARTWORK_FRACTION)
                            .fillMaxHeight() else Modifier.fillMaxWidth()
                            .height(maxHeight * IMMERSIVE_CLEAR_ARTWORK_FRACTION + IMMERSIVE_CLEAR_ARTWORK_FADE_EXTENSION))
                        .align(if (landscape) Alignment.TopStart else Alignment.TopCenter)
                        .clipToBounds()
                ) {
                    AlbumArtworkSpectrumOverlay(
                        spectrum = audioSpectrum,
                        spectrumState = audioSpectrumState,
                        visible = true,
                        isPlaying = isPlaying,
                        layer = AudioVisualizerLayer.Foreground,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().rawStableStatusBarsPadding().rawStableNavigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)) {
                Column(Modifier.width((windowProfile.windowWidth * IMMERSIVE_CLEAR_ARTWORK_FRACTION - 24.dp)
                    .coerceAtLeast(0.dp)).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f)
                                .pointerInput(currentSong?.path, currentSong?.title) {
                                    detectTapGestures(
                                        onTap = { SongContributorsDialogState.show(currentSong) },
                                        onLongPress = {
                                            copySongInfoToClipboard(context, currentSong)
                                        },
                                    )
                                }
                        ) {
                            PlayerContentCrossfade(
                                key = currentSong?.let { "${it.path}|${it.cueTrackIndex}|${it.cueOffsetMs}" },
                                value = currentSong, modifier = Modifier.fillMaxWidth()) { shownSong ->
                            Column(Modifier.fillMaxWidth()) {
                            SharedMarqueeText(text = shownSong?.displayName ?: stringResource(R.string.player_no_song),
                                explicitBadge = com.rawsmusic.core.common.model.LocalLyricExplicit.isExplicit(shownSong),
                                color = tone.primary, fontSizeSp = 21f, fontWeight = FontWeight.SemiBold,
                                textAlign = playerTitleTextAlign, visible = true,
                                modifier = Modifier.fillMaxWidth().height(32.dp))
                            Text(shownSong?.artist.orEmpty(), color = tone.secondary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(), textAlign = playerTitleTextAlign)
                            }
                            }
                        }
                        IconCircle(iconRes = R.drawable.ic_more_vert, size = 38.dp, tint = tone.iconSoft, onClick = onOpenMore,
                            modifier = Modifier.onGloballyPositioned { onMoreBoundsChanged(it.boundsInRoot()) })
                    }
                    Spacer(Modifier.weight(1f).fillMaxWidth().observeArtworkLongPress(onArtworkLongPress))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(iconRes = playModeIconRes, size = 34.dp, tint = tone.iconSoft, onClick = onPlayMode)
                        IconCircle(iconRes = previousIconRes, size = 42.dp, tint = tone.icon, onClick = onPrevious)
                        IconCircle(iconRes = if (isPlaying) pauseIconRes else playIconRes, size = 52.dp, tint = tone.icon, onClick = onPlayPause)
                        IconCircle(iconRes = nextIconRes, size = 42.dp, tint = tone.icon, onClick = onNext)
                        ImmersiveQueueButton(queueVisible, if (queueVisible) tone.icon else tone.iconSoft, onToggleQueue)
                    }
                }
                Spacer(Modifier.width(IMMERSIVE_CLEAR_ARTWORK_FADE_EXTENSION + 24.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (queueVisible) {
                        InlinePlayerQueue(songs = queueSongs, currentIndex = queueCurrentIndex,
                            currentSong = currentSong, currentCoverPath = coverPath,
                            colors = InlinePlayerQueueColors(primaryText = tone.primary, secondaryText = tone.secondary,
                                accent = tone.icon, icon = tone.iconSoft, currentBackground = tone.chipBackground,
                                artworkPlaceholder = tone.chipBackground.copy(alpha = .72f)),
                            onSongClick = onQueueSongClick, onClearPriorityQueue = onClearPriorityQueue,
                            fullscreen = queueFullscreen, onFullscreenChange = onQueueFullscreenChange,
                            modifier = Modifier.weight(1f).fillMaxWidth())
                    } else {
                        StandardMiniLyric(lyricSong, lyricPositionMs, isPlaying, displayTranslation,
                            displayRoma, progressStyle, miniLyricPosition, onOpenLyric,
                            onLineSeek = onInlineLyricSeek, modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                    ImmersiveProgress(coverPath = coverPath, currentSong = if (previewOnly) null else currentSong, currentPositionMs = currentPositionMs,
                        totalDurationMs = totalDurationMs, isPlaying = isPlaying, progressStyle = progressStyle,
                        audioSpectrum = audioSpectrum, audioVisualizerEnabled = audioVisualizerEnabled,
                        climaxEnabled = climaxEnabled, waveformBarCount = waveformBarCount,
                        waveformRemainingColor = waveformRemainingColor, waveformPlayedColor = waveformPlayedColor,
                        waveformClimaxColor = waveformClimaxColor, onSeekStart = onSeekStart, onSeekStop = onSeekStop)
                    if (audioInfoCapsuleVisible || smartTransitionVisible) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            AudioInfoCapsule(song = currentSong, coverPath = coverPath, text = audioInfoText,
                                playbackChainText = audioInfoPlaybackChainText, smartTransitionVisible = smartTransitionVisible,
                                onClick = onAudioQuality, onLongClick = onAudioQualityLongPress)
                        }
                    }
                }
            }
            return@Box
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .rawStableStatusBarsPadding()
                .rawStableNavigationBarsPadding()
                .padding(horizontal = 30.dp, vertical = 14.dp)
        ) {
            var titleInfoHeightPx by remember { mutableIntStateOf(0) }
            var progressPanelHeightPx by remember { mutableIntStateOf(0) }
            var transportControlsHeightPx by remember { mutableIntStateOf(0) }
            var headerActionRailWidthPx by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            // The More control is an independent TopEnd rail. Keep the title, artist
            // and mini-lyric content inside a measured safe lane instead of letting center/right
            // alignment use the full viewport underneath that rail. The 48dp fallback prevents a
            // one-frame overlap before the action rail reports its real width.
            val headerActionRailWidth = with(density) {
                if (headerActionRailWidthPx > 0) headerActionRailWidthPx.toDp() else 48.dp
            }
            val headerContentEndReserve = headerActionRailWidth + 8.dp
            val titleTextLaneHeight = resolveNativeTextLaneHeight(
                fontDensity = density,
                fontSizeSp = 21f,
                minimumHeight = 29.dp,
            )
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
            val estimatedPrimaryLyricRowHeight = with(density) {
                (if (miniLyricHasSecondaryText) 44.sp else 31.sp).toPx().toDp()
            }
            val playerLayoutMetrics = resolveImmersivePlayerLayoutMetrics(
                viewportHeight = maxHeight,
                clearArtworkBottom = clearArtworkBottom,
                titleInfoHeight = measuredTitleInfoHeight,
                progressPanelHeight = measuredProgressPanelHeight,
                transportControlsHeight = measuredTransportHeight,
                estimatedPrimaryRowHeight = estimatedPrimaryLyricRowHeight,
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
                Box(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            // Only title info shares the More rail's vertical band. Lyrics below
                            // it use an independent full-width lane, without double rail padding.
                            modifier = Modifier.padding(
                                start = if (playerTitlePosition == LyricTextPosition.Center) {
                                    headerContentEndReserve
                                } else {
                                    0.dp
                                },
                                end = headerContentEndReserve,
                            )
                                .pointerInput(currentSong?.path, currentSong?.title) {
                                    detectTapGestures(
                                        onTap = { SongContributorsDialogState.show(currentSong) },
                                        onLongPress = {
                                            copySongInfoToClipboard(context, currentSong)
                                        },
                                    )
                                }
                                .onSizeChanged { size ->
                                    if (titleInfoHeightPx != size.height) titleInfoHeightPx = size.height
                                }
                        ) {
                            PlayerContentCrossfade(
                                key = currentSong?.let { "${it.path}|${it.cueTrackIndex}|${it.cueOffsetMs}" },
                                value = currentSong, modifier = Modifier.fillMaxWidth()) { shownSong ->
                            Column(Modifier.fillMaxWidth()) {
                            SharedMarqueeText(
                                text = shownSong?.displayName ?: stringResource(R.string.player_no_song),
                                explicitBadge = com.rawsmusic.core.common.model.LocalLyricExplicit.isExplicit(shownSong),
                                color = tone.primary,
                                fontSizeSp = 21f,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = playerTitleTextAlign,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(titleTextLaneHeight),
                                visible = true
                            )
                            Text(
                                shownSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "",
                                color = tone.secondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = playerTitleTextAlign,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            }
                            }
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
                            textPosition = miniLyricPosition,
                            maxHeight = lyricPreviewHeight,
                            maxPrimaryRows = lyricPreviewRows,
                            modifier = if (miniLyricPosition == LyricTextPosition.Center) {
                                Modifier.padding(horizontal = 12.dp)
                            } else {
                                Modifier.padding(end = headerContentEndReserve)
                            },
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .onSizeChanged { size ->
                                if (headerActionRailWidthPx != size.width) {
                                    headerActionRailWidthPx = size.width
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconCircle(
                            iconRes = R.drawable.ic_more_vert,
                            size = 44.dp,
                            tint = tone.iconSoft,
                            onClick = onOpenMore,
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                onMoreBoundsChanged(coordinates.boundsInRoot())
                            },
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
                        coverPath = coverPath,
                        currentSong = if (previewOnly) null else currentSong,
                        currentPositionMs = currentPositionMs,
                        totalDurationMs = totalDurationMs,
                        isPlaying = isPlaying,
                        progressStyle = progressStyle,
                        audioSpectrum = audioSpectrum,
                        audioVisualizerEnabled = audioVisualizerEnabled,
                        climaxEnabled = climaxEnabled,
                        waveformBarCount = waveformBarCount,
                        waveformRemainingColor = waveformRemainingColor,
                        waveformPlayedColor = waveformPlayedColor,
                        waveformClimaxColor = waveformClimaxColor,
                        onSeekStart = onSeekStart,
                        onSeekStop = onSeekStop
                    )
                    if (audioInfoCapsuleVisible || smartTransitionVisible) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
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
        if (renderBackdrop) {
            ImmersiveBackdrop(
                coverPath = coverPath,
                pageProgress = pageProgress,
                motionEnabled = isPlaying,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .rawStableStatusBarsPadding()
                .rawStableNavigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            if (renderTopBar) {
                ImmersiveTopBar(pageProgress = pageProgress, showPageDots = false)
                Spacer(Modifier.height(46.dp))
            } else {
                Spacer(Modifier.height(94.dp))
            }
            PlayerContentCrossfade(
                key = currentSong?.let { "${it.path}|${it.cueTrackIndex}|${it.cueOffsetMs}" },
                value = currentSong, modifier = Modifier.fillMaxWidth()) { shownSong ->
            Column(Modifier.fillMaxWidth()) {
            Text(
                shownSong?.displayName ?: stringResource(R.string.player_no_song),
                color = tone.primary,
                fontSize = 34.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                shownSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "",
                color = tone.secondary,
                fontSize = 21.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            }
            }
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
                LyricTransportButton(
                    isPlaying = isPlaying,
                    onClick = onPlayPause
                )
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
    motionEnabled: Boolean = false,
    onBack: () -> Unit,
    onOpenMetadata: () -> Unit = {},
    onSongClick: (AudioFile, Int) -> Unit,
    pageProgress: Float = 0f,
    renderBackdrop: Boolean = true,
    renderTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    val tone = rememberPlayerForegroundTone()
    val accent = Color(0xFFE5C78D)
    val cardColor = Color.White.copy(alpha = 0.055f)
    val cardBorder = Color.White.copy(alpha = 0.09f)
    val unknownArtist = stringResource(R.string.player_unknown_artist)
    val unknownAlbum = stringResource(R.string.player_unknown_album)
    val albumArtist = currentSong?.albumArtist?.ifBlank { currentSong.artist }?.ifBlank { unknownArtist } ?: unknownArtist
    val albumName = currentSong?.album?.ifBlank { unknownAlbum } ?: unknownAlbum
    val genre = currentSong?.genre?.takeIf { it.isNotBlank() }
    val year = currentSong?.year?.takeIf { it > 0 }
    val albumDuration = remember(songs) { songs.sumOf { it.duration.coerceAtLeast(0L) } }
    val artworkKey = currentSong?.resolvePlaybackArtworkKey(coverPath)
    val technicalBadges = remember(currentSong) {
        buildList {
            val song = currentSong ?: return@buildList
            song.sampleRate.takeIf { it > 0 }?.let { add(formatSampleRateLabel(it)) }
            song.bitsPerSample.takeIf { it > 0 }?.let { add("$it bit") }
            song.bitRate.takeIf { it > 0 }?.let { add("${it / 1000} kbps") }
        }
    }
    val albumSummary = if (albumDuration > 0L) {
        stringResource(R.string.player_album_detail_summary_with_duration,
            songs.size, AudioUtils.formatDuration(albumDuration))
    } else {
        stringResource(R.string.player_album_detail_summary, songs.size)
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (renderBackdrop) {
            ImmersiveBackdrop(coverPath = coverPath, pageProgress = pageProgress, motionEnabled = motionEnabled)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .rawStableStatusBarsPadding()
                .rawStableNavigationBarsPadding()
                .padding(horizontal = 14.dp),
        ) {
            item(key = "album-hero") {
                if (renderTopBar) {
                    ImmersiveTopBar(pageProgress = pageProgress, showPageDots = false,
                        modifier = Modifier.padding(top = 18.dp))
                    Text("‹", color = tone.primary, fontSize = 30.sp,
                        modifier = Modifier.size(48.dp).clickable(onClick = onBack).padding(start = 12.dp))
                } else {
                    Spacer(Modifier.height(68.dp))
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    val coverSize = (maxWidth * 0.34f).coerceIn(84.dp, 150.dp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(coverSize).clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                        ) {
                            if (!artworkKey.isNullOrBlank()) {
                                BitmapImage(key = artworkKey, contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                    targetWidth = 480, targetHeight = 480, surface = ArtworkSurface.Playback)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(currentSong?.displayName ?: stringResource(R.string.player_song_info_title),
                                color = tone.primary, fontSize = 23.sp, lineHeight = 28.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            Text(currentSong?.artist?.ifBlank { unknownArtist } ?: unknownArtist,
                                color = tone.primary.copy(alpha = 0.90f), fontSize = 15.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(10.dp))
                            Text(albumName, color = tone.secondary, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val secondaryInfo = listOfNotNull(year?.toString(), genre).joinToString(" · ")
                            if (secondaryInfo.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(secondaryInfo, color = tone.tertiary, fontSize = 11.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(10.dp))
                            // Wrap badges instead of clipping a long technical row on narrow phones.
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (currentSong?.isHiRes == true) {
                                    ImmersiveAudioBadge("Hi-Res", emphasized = true, accent = accent)
                                }
                                technicalBadges.forEach {
                                    ImmersiveAudioBadge(it, emphasized = false, accent = accent)
                                }
                            }
                            if (currentSong?.duration?.let { it > 0L } == true) {
                                Spacer(Modifier.height(8.dp))
                                Text(AudioUtils.formatDuration(currentSong.duration),
                                    color = tone.tertiary, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
            item(key = "album-details") {
                ImmersiveAlbumDetailCard(song = currentSong, albumArtist = albumArtist,
                    albumName = albumName, albumSummary = albumSummary, accent = accent,
                    cardColor = cardColor, cardBorder = cardBorder, onOpenMetadata = onOpenMetadata)
                Spacer(Modifier.height(12.dp))
            }
            item(key = "album-tracks-heading") {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                        .background(cardColor).padding(horizontal = 14.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.player_album_detail_tracks),
                            color = tone.primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        if (songs.isNotEmpty()) {
                            Row(
                                Modifier.clip(RoundedCornerShape(24.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(0.5.dp, cardBorder, RoundedCornerShape(24.dp))
                                    .clickable { onSongClick(songs.first(), 0) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("▶", color = tone.primary, fontSize = 10.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.player_album_detail_play_all),
                                    color = tone.primary, fontSize = 12.sp)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(cardBorder))
                }
            }
            // One lazy item per track: a large album must not be laid out as one giant Column.
            itemsIndexed(songs, contentType = { _, _ -> "album-track" }) { index, song ->
                Column(Modifier.fillMaxWidth().background(cardColor).padding(horizontal = 9.dp)) {
                    ImmersiveAlbumTrackRow(
                        song = song, index = index,
                        selected = currentSong?.let { it.path == song.path && it.cueTrackIndex == song.cueTrackIndex } == true,
                        accent = accent, onClick = { onSongClick(song, index) })
                    if (index != songs.lastIndex) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 7.dp)
                            .height(0.5.dp).background(Color.White.copy(alpha = 0.07f)))
                    }
                }
            }
            item(key = "album-tracks-footer") {
                Box(Modifier.fillMaxWidth().height(10.dp)
                    .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                    .background(cardColor))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ImmersiveTopBar(
    pageProgress: Float,
    pageProgressState: State<Float>? = null,
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
            PageDots(
                pageProgress = pageProgress,
                pageProgressState = pageProgressState,
            )
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
private fun PageDots(
    pageProgress: Float,
    pageProgressState: State<Float>? = null,
) {
    // Total width is constant: the three active fractions always sum to 1 while paging between
    // adjacent pages. Draw the width/alpha interpolation directly so scene progress invalidates
    // only this Canvas rather than remeasuring three Compose boxes every animation frame.
    Canvas(
        modifier = Modifier
            .width(43.dp)
            .height(6.dp)
    ) {
        val liveProgress = pageProgressState?.value ?: pageProgress
        val spacing = 6.dp.toPx()
        val baseWidth = 6.dp.toPx()
        val activeExtra = 13.dp.toPx()
        var x = 0f
        repeat(3) { index ->
            val active = (1f - abs(liveProgress - index)).coerceIn(0f, 1f)
            val width = baseWidth + activeExtra * active
            drawRoundRect(
                color = Color.White.copy(alpha = 0.32f + 0.56f * active),
                topLeft = Offset(x, 0f),
                size = Size(width, size.height),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
            )
            x += width + spacing
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
    textPosition: LyricTextPosition,
    maxHeight: Dp,
    maxPrimaryRows: Int,
    modifier: Modifier = Modifier,
) {
    PlayerContentCrossfade(playerLyricIdentity(song), PlayerLyricFrame(song, positionMs), modifier) { frame ->
        MiniLyricPreviewContent(frame.song, frame.positionMs, isPlaying && frame.song === song, displayTranslation,
            displayRoma, onClick, primaryColor, secondaryColor, dimColor,
            textPosition, maxHeight, maxPrimaryRows)
    }
}

@Composable
private fun MiniLyricPreviewContent(
    song: Song?,
    positionMs: Long,
    isPlaying: Boolean,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onClick: () -> Unit,
    primaryColor: Color = Color.White,
    secondaryColor: Color = Color.White.copy(alpha = 0.58f),
    dimColor: Color = Color.White.copy(alpha = 0.40f),
    textPosition: LyricTextPosition,
    maxHeight: Dp,
    maxPrimaryRows: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
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
            textPosition = textPosition,
            zoomGestureEnabled = false,
            blurEnabled = false,
            karaokeGlowEnabled = AppPreferences.UI.lyricKaraokeGlowEnabled,
            karaokeLiftEnabled = AppPreferences.UI.lyricKaraokeLiftEnabled,
            primaryFontSizeRange = 12..20,
            secondaryFontSizeRange = 10..14,
            lineHorizontalPadding = 0.dp,
            compactLineSpacing = 6.dp,
            compactTrailingPullEnabled = false,
            duetAlignmentEnabled = false,
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
    audioSpectrum: FloatArray,
    audioVisualizerEnabled: Boolean,
    climaxEnabled: Boolean,
    waveformBarCount: Int = 160,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    coverPath: String? = null,
) {
    when (progressStyle) {
        ImmersiveProgressStyle.Classic -> ClassicTimelineProgress(
            currentSong = currentSong,
            isPlaying = isPlaying,
            coverPath = coverPath,
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
            waveformBarCount = waveformBarCount,
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
            onSeekStop = onSeekStop,
            horizontalExtension = 22.dp
        )
        ImmersiveProgressStyle.MusicSpine -> MusicSpineTimelineProgress(
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            isPlaying = isPlaying,
            spectrum = audioSpectrum,
            playedColor = waveformPlayedColor,
            remainingColor = waveformRemainingColor,
            timeColor = rememberPlayerForegroundTone().tertiary,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
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
    waveformBarCount: Int = 160,
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
        waveformBarCount = waveformBarCount,
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
    horizontalExtension: Dp = 0.dp,
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
            horizontalExtension = horizontalExtension,
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
    timeColor: Color? = null,
    currentSong: AudioFile? = null,
    isPlaying: Boolean = false,
    coverPath: String? = null,
) {
    val foregroundTone = rememberPlayerForegroundTone()
    val trackKey = remember(currentSong?.path, currentSong?.fileSize, currentSong?.dateModified,
        currentSong?.cueOffsetMs, currentSong?.cueEndMs, currentSong?.cueTrackIndex) {
        "${currentSong?.path}|${currentSong?.fileSize}|${currentSong?.dateModified}|" +
            "${currentSong?.cueOffsetMs}|${currentSong?.cueEndMs}|${currentSong?.cueTrackIndex}"
    }
    ClassicTimelineEffects(
        currentPositionMs = currentPositionMs,
        totalDurationMs = totalDurationMs,
        isPlaying = isPlaying,
        trackKey = trackKey,
        artworkKey = currentSong.resolvePlaybackArtworkKey(coverPath),
        trackColor = trackColor ?: foregroundTone.controlTrack,
        fallbackColor = fillColor ?: foregroundTone.controlFill,
        timeColor = timeColor ?: foregroundTone.tertiary,
        onSeekStart = onSeekStart,
        onSeekStop = onSeekStop,
        modifier = modifier,
    )
}

private const val MUSIC_SPINE_BAR_COUNT = 64

@Composable
internal fun MusicSpineTimelineProgress(
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    playedColor: Color,
    remainingColor: Color,
    timeColor: Color,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val localDensity = LocalDensity.current
    val motion = remember { DisplaySpectrumMotion() }
    val seekHalfTouchBandPx = with(localDensity) { 22.dp.toPx() }

    val realFraction = if (totalDurationMs > 0L) {
        currentPositionMs.toFloat() / totalDurationMs.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)
    val animatedProgress = animateFloatAsState(
        targetValue = realFraction,
        animationSpec = tween(durationMillis = 60, easing = LinearEasing),
        label = "music-spine-progress",
    )
    val displayFraction = if (isDragging) dragFraction else animatedProgress.value
    val displayPositionMs = if (isDragging && totalDurationMs > 0L) {
        (displayFraction * totalDurationMs).toLong()
    } else {
        currentPositionMs
    }
    val trackScaleY by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "music-spine-touch-scale",
    )
    val frameModifier = Modifier.audioVisualizerFrame(
        motion = motion,
        spectrum = spectrum,
        spectrumState = spectrumState,
        visible = true,
        playing = isPlaying,
    )

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(totalDurationMs, widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main,
                        )
                        if (totalDurationMs <= 0L || widthPx <= 1) return@awaitEachGesture
                        val start = down.position
                        val centerY = size.height * 0.5f
                        val seekDownAllowed = abs(start.y - centerY) <= seekHalfTouchBandPx
                        var lastPosition = start
                        var axis = PlayerTimelineGestureAxis.Undecided
                        var seekStarted = false
                        var lastFraction = (start.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        var finishedNormally = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                lastPosition = change.position
                                if (axis == PlayerTimelineGestureAxis.Undecided && change.isConsumed) {
                                    axis = PlayerTimelineGestureAxis.VerticalScene
                                }
                                if (axis == PlayerTimelineGestureAxis.Undecided) {
                                    val dx = change.position.x - start.x
                                    val dy = change.position.y - start.y
                                    axis = if (seekDownAllowed) {
                                        resolvePlayerTimelineGestureAxis(
                                            dx = dx,
                                            dy = dy,
                                            touchSlop = viewConfiguration.touchSlop,
                                        )
                                    } else if (abs(dy) > viewConfiguration.touchSlop) {
                                        PlayerTimelineGestureAxis.VerticalScene
                                    } else {
                                        PlayerTimelineGestureAxis.Undecided
                                    }
                                    if (axis == PlayerTimelineGestureAxis.HorizontalSeek) {
                                        seekStarted = true
                                        isDragging = true
                                        onSeekStart()
                                    }
                                }
                                if (axis == PlayerTimelineGestureAxis.HorizontalSeek) {
                                    lastFraction = (change.position.x / widthPx.toFloat())
                                        .coerceIn(0f, 1f)
                                    dragFraction = lastFraction
                                    change.consume()
                                }
                                if (!change.pressed) {
                                    finishedNormally = true
                                    break
                                }
                            }
                        } finally {
                            if (seekStarted) {
                                // Publish the external seek/hold target before releasing the local
                                // drag owner so finger-up cannot flash the pre-seek position.
                                onSeekStop(lastFraction)
                                isDragging = false
                            } else if (
                                finishedNormally &&
                                seekDownAllowed &&
                                axis == PlayerTimelineGestureAxis.Undecided
                            ) {
                                val tapFraction = (lastPosition.x / widthPx.toFloat())
                                    .coerceIn(0f, 1f)
                                onSeekStart()
                                onSeekStop(tapFraction)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .graphicsLayer {
                        scaleY = trackScaleY
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .then(frameModifier),
            ) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                // The spectrum owns a direct Choreographer invalidation loop. Read seek state in
                // the draw phase so every visualizer frame uses the newest finger position instead
                // of waiting for a composition-time displayFraction snapshot.
                val drawDisplayFraction = if (isDragging) {
                    dragFraction
                } else {
                    animatedProgress.value
                }
                val centerY = size.height * 0.5f
                val slotWidth = size.width / MUSIC_SPINE_BAR_COUNT
                val barWidth = minOf(4f * density, slotWidth * 0.72f)
                    .coerceAtLeast(1f * density)
                val barRadius = minOf(2f * density, barWidth * 0.5f)
                val minAmplitude = (6.5f * density).coerceAtMost(size.height)
                val maxAmplitude = size.height
                val progressX = drawDisplayFraction.coerceIn(0f, 1f) * size.width

                for (bar in 0 until MUSIC_SPINE_BAR_COUNT) {
                    val sampleIndex = if (motion.levels.isEmpty()) {
                        0
                    } else {
                        (bar * motion.levels.size / MUSIC_SPINE_BAR_COUNT)
                            .coerceIn(0, motion.levels.lastIndex)
                    }
                    val normalizedAmplitude = if (motion.levels.isEmpty()) {
                        0f
                    } else {
                        motion.levels[sampleIndex].coerceIn(0f, 1f)
                    }
                    val amplitude = (normalizedAmplitude * maxAmplitude)
                        .coerceIn(minAmplitude, maxAmplitude)
                    val x = (bar + 0.5f) * slotWidth - barWidth * 0.5f
                    val top = centerY - amplitude * 0.5f
                    val right = x + barWidth
                    val radius = CornerRadius(barRadius, barRadius)
                    when {
                        progressX <= x -> {
                            drawRoundRect(
                                color = remainingColor,
                                topLeft = Offset(x, top),
                                size = Size(barWidth, amplitude),
                                cornerRadius = radius,
                            )
                        }
                        progressX >= right -> {
                            drawRoundRect(
                                color = playedColor,
                                topLeft = Offset(x, top),
                                size = Size(barWidth, amplitude),
                                cornerRadius = radius,
                            )
                        }
                        else -> {
                            // Draw the boundary bar once as remaining, then replace only the
                            // physically played slice. This makes timeline progress explicit and
                            // independent of layer BlendMode/compositing semantics.
                            drawRoundRect(
                                color = remainingColor,
                                topLeft = Offset(x, top),
                                size = Size(barWidth, amplitude),
                                cornerRadius = radius,
                            )
                            clipRect(
                                left = x,
                                top = top,
                                right = progressX,
                                bottom = top + amplitude,
                            ) {
                                drawRoundRect(
                                    color = playedColor,
                                    topLeft = Offset(x, top),
                                    size = Size(barWidth, amplitude),
                                    cornerRadius = radius,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AudioUtils.formatDuration(displayPositionMs), color = timeColor, fontSize = 12.sp)
            Text(AudioUtils.formatDuration(totalDurationMs), color = timeColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun IconCircle(
    @DrawableRes iconRes: Int,
    size: androidx.compose.ui.unit.Dp,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
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
private fun LyricTransportButton(isPlaying: Boolean, onClick: () -> Unit) {
    val tone = rememberPlayerForegroundTone()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(tone.controlTrack)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = stringResource(if (isPlaying) R.string.common_pause else R.string.common_play),
            colorFilter = ColorFilter.tint(tone.primary),
            modifier = Modifier.size(25.dp)
        )
    }
}

@Composable
private fun ImmersiveAudioBadge(
    label: String,
    emphasized: Boolean,
    accent: Color,
) {
    val tone = rememberPlayerForegroundTone()
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .height(22.dp)
            .clip(shape)
            .background(
                if (emphasized) accent.copy(alpha = 0.24f)
                else Color.Black.copy(alpha = 0.20f),
            )
            .border(
                width = 0.5.dp,
                color = if (emphasized) accent.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.13f),
                shape = shape,
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (emphasized) accent.copy(alpha = 0.98f) else tone.secondary,
            fontSize = 10.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun ImmersiveAlbumDetailCard(
    song: AudioFile?,
    albumArtist: String,
    albumName: String,
    albumSummary: String,
    accent: Color,
    cardColor: Color,
    cardBorder: Color,
    onOpenMetadata: () -> Unit,
) {
    val tone = rememberPlayerForegroundTone()
    val shape = RoundedCornerShape(18.dp)
    val rows = buildList<Pair<String, String>> {
        add(stringResource(R.string.player_info_artist) to albumArtist)
        add(stringResource(R.string.player_info_album) to albumName)
        song?.year?.takeIf { it > 0 }?.let {
            add(stringResource(R.string.player_album_detail_year) to it.toString())
        }
        song?.genre?.takeIf { it.isNotBlank() }?.let {
            add(stringResource(R.string.player_album_detail_genre) to it)
        }
        song?.composer?.takeIf { it.isNotBlank() }?.let {
            add(stringResource(R.string.player_album_detail_composer) to it)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardColor)
            .border(0.5.dp, cardBorder, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.player_album_detail_title),
                    color = tone.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = albumSummary,
                    color = tone.tertiary,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = stringResource(R.string.player_album_detail_full_metadata),
                color = tone.tertiary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenMetadata)
                    .padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
            )
        }

        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.07f)),
            )
            Spacer(Modifier.height(10.dp))
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        color = tone.tertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.width(74.dp),
                    )
                    Text(
                        text = value,
                        color = tone.secondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveAlbumTrackRow(
    song: AudioFile,
    index: Int,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val tone = rememberPlayerForegroundTone()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (selected) {
                Canvas(Modifier.size(16.dp)) {
                    listOf(0.45f, 0.85f, 0.60f).forEachIndexed { bar, height ->
                        val w = size.width / 6f
                        drawRoundRect(accent,
                            topLeft = Offset(bar * size.width / 3f, size.height * (1f - height) / 2f),
                            size = Size(w, size.height * height),
                            cornerRadius = CornerRadius(w / 2f))
                    }
                }
            } else {
                Text((song.trackNumber.takeIf { it > 0 } ?: (index + 1)).toString(),
                    color = tone.tertiary, fontSize = 13.sp)
            }
        }
        Text(
            text = song.displayName,
            color = if (selected) accent else tone.secondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (song.isHiRes) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, accent.copy(alpha = 0.46f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "Hi-Res",
                    color = accent.copy(alpha = 0.92f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = AudioUtils.formatDuration(song.duration),
            color = if (selected) tone.secondary else tone.tertiary,
            fontSize = 12.sp,
        )
    }
}

private fun formatSampleRateLabel(sampleRate: Int): String = when {
    sampleRate <= 0 -> ""
    sampleRate % 1000 == 0 -> "${sampleRate / 1000} kHz"
    else -> java.lang.String.format(java.util.Locale.US, "%.1f kHz", sampleRate / 1000f)
}

@Composable
private fun playerSleepTimerSummary(selection: Int): String = when (selection) {
    1 -> stringResource(R.string.sleep_timer_10)
    2 -> stringResource(R.string.sleep_timer_15)
    3 -> stringResource(R.string.sleep_timer_20)
    4 -> stringResource(R.string.sleep_timer_30)
    5 -> stringResource(R.string.sleep_timer_45)
    6 -> stringResource(R.string.sleep_timer_60)
    7 -> stringResource(R.string.sleep_timer_90)
    8 -> stringResource(R.string.sleep_timer_current)
    9 -> stringResource(R.string.sleep_timer_3_songs)
    10 -> stringResource(R.string.sleep_timer_5_songs)
    11 -> stringResource(R.string.player_sleep_timer_custom_title)
    else -> stringResource(R.string.sleep_timer_off)
}

@Composable
internal fun ImmersiveMoreSheet(
    currentSong: AudioFile?,
    onAddSongToPlaylist: ((AudioFile) -> Unit)? = null,
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState? = null,
    artworkAlpha: Float = 1f,
    onArtworkBoundsChanged: (Rect) -> Unit = {},
    progressStyle: ImmersiveProgressStyle,
    onProgressStyleChange: (ImmersiveProgressStyle) -> Unit,
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
    audioVisualizerEnabled: Boolean = false,
    onAudioVisualizerEnabledChange: (Boolean) -> Unit = {},
    sleepTimerSelection: Int = 0,
    onSleepTimerSelectionChange: ((Int) -> Unit)? = null,
    artworkAnimationStyle: PlayerArtworkAnimationStyle? = null,
    onArtworkAnimationStyleChange: ((PlayerArtworkAnimationStyle) -> Unit)? = null,
    inlineLyricsOptions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteCoverSuccessText = stringResource(R.string.player_video_cover_remote_success)
    val remoteCoverFailedText = stringResource(R.string.player_video_cover_remote_failed)
    val videoCoverState by VideoCoverPreferences.state.collectAsState()
    val videoCoverSongKey = currentSong?.path.orEmpty()
    var showRemoteCoverDialog by rememberSaveable(videoCoverSongKey) { mutableStateOf(false) }
    var showPlaybackSpeedDialog by rememberSaveable(videoCoverSongKey) { mutableStateOf(false) }
    var showAiPerformanceDialog by rememberSaveable(videoCoverSongKey) { mutableStateOf(false) }
    var playbackSpeedDialogAnchor by remember { mutableStateOf<Rect?>(null) }
    var aiPerformanceDialogAnchor by remember { mutableStateOf<Rect?>(null) }
    var remoteCoverDialogAnchor by remember { mutableStateOf<Rect?>(null) }
    var playbackSpeedPortalMounted by remember { mutableStateOf(false) }
    var playbackSpeedDraft by rememberSaveable(videoCoverSongKey) {
        mutableFloatStateOf(AppPreferences.Player.playbackSpeed)
    }
    var appliedPlaybackSpeed by rememberSaveable(videoCoverSongKey) {
        mutableFloatStateOf(AppPreferences.Player.playbackSpeed)
    }
    var playbackSpeedChangesPitchDraft by rememberSaveable(videoCoverSongKey) {
        mutableStateOf(AppPreferences.Player.playbackSpeedChangesPitch)
    }
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
    LaunchedEffect(showPlaybackSpeedDialog, videoCoverSongKey) {
        if (showPlaybackSpeedDialog) {
            val currentSpeed = AppPreferences.Player.playbackSpeed.coerceIn(0.25f, 3f)
            playbackSpeedDraft = currentSpeed
            appliedPlaybackSpeed = currentSpeed
            playbackSpeedChangesPitchDraft = AppPreferences.Player.playbackSpeedChangesPitch
        }
    }
    fun normalizedPlaybackSpeed(value: Float): Float =
        (kotlin.math.round(value / 0.05f) * 0.05f).coerceIn(0.25f, 3f)
    val aiMelodyModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.toString()?.let(onAiPerformanceImportMelodyModel)
    }
    val aiInstrumentPackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.toString()?.let(onAiPerformanceImportInstrumentPack)
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
    val acrylicColor = if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.White.copy(alpha = 0.46f)
    }
    val acrylicCorner = 6.dp
    val sheetScrollState = rememberScrollState()
    val maxSheetHeight = (LocalWindowInfo.current.containerDpSize.height - 80.dp)
        .coerceIn(180.dp, 600.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerMoreArtworkThumbnail(
                    size = 76.dp,
                    cornerRadiusDp = 8f,
                    artwork = artworkTransitionState?.foregroundArtworkBitmap(),
                    coverPath = coverPath,
                    artworkAlpha = artworkAlpha,
                    onArtworkBoundsChanged = onArtworkBoundsChanged,
                )
                Spacer(Modifier.width(16.dp))
                PlayerContentCrossfade(
                    key = currentSong?.let { "${it.path}|${it.cueTrackIndex}|${it.cueOffsetMs}" },
                    value = currentSong, modifier = Modifier.weight(1f)) { shownSong ->
                Column(Modifier.fillMaxWidth()) {
                    Text(shownSong?.displayName ?: stringResource(R.string.player_no_song), color = scheme.onSurface, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shownSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "", color = scheme.onSurfaceVariantSummary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                }
                PlayerMoreFavoriteButton(currentSong, onAddSongToPlaylist)
            }
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .popupFrostedScroll(sheetScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            inlineLyricsOptions?.invoke()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerMoreAcrylicTile(
                        iconRes = R.drawable.ic_metadata_outline,
                        title = stringResource(R.string.player_more_metadata_short),
                        cardColor = acrylicColor,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenMetadata,
                    )
                    PlayerMoreAcrylicTile(
                        iconRes = R.drawable.ic_audio_effects_custom,
                        title = stringResource(R.string.player_more_effects_short),
                        cardColor = acrylicColor,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenAudioEffects,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerMoreAcrylicTile(
                        iconRes = R.drawable.ic_share,
                        title = stringResource(R.string.player_more_share_audio),
                        cardColor = acrylicColor,
                        modifier = Modifier.weight(1f),
                        onClick = { sharePlayerAudio(context, currentSong) },
                    )
                    PlayerMoreAcrylicTile(
                        iconRes = R.drawable.ic_landscape_player,
                        title = stringResource(R.string.player_more_landscape),
                        cardColor = acrylicColor,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenLandscapePlayer,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerMoreAcrylicTile(
                        iconRes = R.drawable.ic_side_rail_ai,
                        title = stringResource(R.string.player_ai_performance_title),
                        summary = aiPerformanceState.status.takeIf { it.isNotBlank() },
                        value = if (aiPerformanceState.enabled) {
                            stringResource(R.string.player_ai_performance_on)
                        } else {
                            null
                        },
                        cardColor = acrylicColor,
                        modifier = Modifier.weight(1f),
                        onBoundsChanged = { aiPerformanceDialogAnchor = it },
                        onClick = { if (currentSong != null) showAiPerformanceDialog = true },
                    )
                    PlayerMoreAcrylicTile(
                        iconRes = R.drawable.ic_equalizer_bars,
                        title = stringResource(R.string.player_more_spectrum_analysis),
                        cardColor = acrylicColor,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSpectrumAnalysis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerMoreAcrylicTile(
                        iconRes = R.drawable.ic_playback_speed,
                        title = stringResource(R.string.player_more_playback_speed),
                        summary = stringResource(R.string.player_more_playback_speed_summary),
                        value = stringResource(
                            R.string.player_playback_speed_value,
                            appliedPlaybackSpeed,
                        ),
                        cardColor = acrylicColor,
                        modifier = Modifier.weight(1f),
                        onBoundsChanged = { playbackSpeedDialogAnchor = it },
                        onClick = {
                            playbackSpeedDraft = AppPreferences.Player.playbackSpeed
                                .coerceIn(0.25f, 3f)
                            showPlaybackSpeedDialog = true
                        },
                    )
                    val sleepSelectionChange = onSleepTimerSelectionChange
                    if (sleepSelectionChange != null) {
                        val sleepTimerSummary = playerSleepTimerSummary(sleepTimerSelection)
                        PlayerMoreAcrylicTile(
                            iconRes = 0,
                            title = stringResource(R.string.player_sleep_timer),
                            summary = sleepTimerSummary,
                            value = sleepTimerSummary,
                            cardColor = acrylicColor,
                            modifier = Modifier.weight(1f),
                            onClick = { sleepSelectionChange(SLEEP_TIMER_OPEN_PANEL_REQUEST) },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            RealtimeVocalSeparationCard(
                enabled = realtimeSeparationEnabled,
                preparing = realtimeSeparationPreparing,
                stem = realtimeSeparationStem,
                strength = realtimeSeparationStrength,
                status = realtimeSeparationStatus,
                onEnabledChange = onRealtimeSeparationEnabledChange,
                onStemChange = onRealtimeSeparationStemChange,
                onStrengthChange = onRealtimeSeparationStrengthChange,
                containerColor = acrylicColor,
                cornerRadius = acrylicCorner,
            )
            Spacer(Modifier.height(8.dp))
            VideoCoverSettingsCard(
                state = videoCoverState,
                songKey = videoCoverSongKey,
                onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
                onImportRemote = {
                    remoteCoverMessage = null
                    showRemoteCoverDialog = true
                },
                onRemoteAnchorBoundsChanged = { remoteCoverDialogAnchor = it },
                containerColor = acrylicColor,
                cornerRadius = acrylicCorner,
            )
            Spacer(Modifier.height(8.dp))
            ImmersiveProgressSettingsCard(
                progressStyle = progressStyle,
                onProgressStyleChange = onProgressStyleChange,
                containerColor = acrylicColor,
                cornerRadius = acrylicCorner,
            )
            Spacer(Modifier.height(8.dp))
            ImmersiveVisualizerSettingsCard(
                enabled = audioVisualizerEnabled,
                onEnabledChange = onAudioVisualizerEnabledChange,
                containerColor = acrylicColor,
                cornerRadius = acrylicCorner,
            )
            Spacer(Modifier.height(8.dp))
        }
        }
        PlayerAiPerformanceDialog(
            show = showAiPerformanceDialog,
            anchorBounds = aiPerformanceDialogAnchor,
            state = aiPerformanceState,
            onDismiss = { showAiPerformanceDialog = false },
            onModeChange = onAiPerformanceModeChange,
            onInstrumentChange = onAiPerformanceInstrumentChange,
            onCancel = onAiPerformanceCancel,
            onImportMelodyModel = { aiMelodyModelPicker.launch(arrayOf("*/*")) },
            onImportInstrumentPack = {
                aiInstrumentPackPicker.launch(arrayOf("application/zip", "application/octet-stream"))
            },
        )

        PlayerPopupPortal(active = showPlaybackSpeedDialog || playbackSpeedPortalMounted) {
            PlayerAnchoredGlassPopup(
                show = showPlaybackSpeedDialog,
                anchorBounds = playbackSpeedDialogAnchor,
                sourceArtworkBounds = null,
                artwork = null,
                onDismiss = { showPlaybackSpeedDialog = false },
                onMountedChange = { playbackSpeedPortalMounted = it },
                layerZ = 40f,
            ) { _, _ ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.player_playback_speed_dialog_title),
                        color = scheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.player_playback_speed_dialog_summary),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    )
                Text(
                    text = stringResource(
                        R.string.player_playback_speed_value,
                        playbackSpeedDraft,
                    ),
                    color = scheme.primary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Slider(
                    value = playbackSpeedDraft,
                    onValueChange = { value ->
                        playbackSpeedDraft = normalizedPlaybackSpeed(value)
                    },
                    onValueChangeFinished = {
                        playbackSpeedDraft = normalizedPlaybackSpeed(playbackSpeedDraft)
                    },
                    valueRange = 0.25f..3f,
                    steps = 54,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ImmersiveSettingToggleRow(
                    title = stringResource(R.string.player_playback_speed_change_pitch),
                    subtitle = stringResource(R.string.player_playback_speed_change_pitch_summary),
                    enabled = true,
                    checked = playbackSpeedChangesPitchDraft,
                    onClick = { playbackSpeedChangesPitchDraft = !playbackSpeedChangesPitchDraft },
                )
                Text(
                    text = stringResource(R.string.player_playback_speed_presets),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0.5f, 0.75f, 1f).forEach { preset ->
                        TextButton(
                            text = stringResource(R.string.player_playback_speed_value, preset),
                            onClick = { playbackSpeedDraft = preset },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1.25f, 1.5f, 2f).forEach { preset ->
                        TextButton(
                            text = stringResource(R.string.player_playback_speed_value, preset),
                            onClick = { playbackSpeedDraft = preset },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = stringResource(R.string.player_playback_speed_cancel),
                        onClick = { showPlaybackSpeedDialog = false },
                    )
                    TextButton(
                        text = stringResource(R.string.player_playback_speed_apply),
                        onClick = {
                            val normalized = normalizedPlaybackSpeed(playbackSpeedDraft)
                            val pitchModeChanged =
                                AppPreferences.Player.playbackSpeedChangesPitch != playbackSpeedChangesPitchDraft
                            AppPreferences.Player.playbackSpeedChangesPitch = playbackSpeedChangesPitchDraft
                            playbackSpeedDraft = normalized
                            appliedPlaybackSpeed = normalized
                            // Applying speed always rebuilds as needed. If only the pitch mode changed
                            // at 1.00x, both lanes are identity so no decoder reopen is necessary.
                            if (kotlin.math.abs(normalized - 1f) > 0.0001f || !pitchModeChanged ||
                                kotlin.math.abs(AppPreferences.Player.playbackSpeed - normalized) > 0.0001f
                            ) {
                                onPlaybackSpeedChange(normalized)
                            }
                            showPlaybackSpeedDialog = false
                        },
                    )
                }
            }
        }
        }
        VideoCoverRemoteImportDialog(
                show = showRemoteCoverDialog,
                anchorBounds = remoteCoverDialogAnchor,
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


@Composable
private fun PlayerAiPerformanceDialog(
    show: Boolean,
    anchorBounds: Rect?,
    state: PlayerAiPerformanceUiState,
    onDismiss: () -> Unit,
    onModeChange: (PlayerAiPerformanceMode) -> Unit,
    onInstrumentChange: (String) -> Unit,
    onCancel: () -> Unit,
    onImportMelodyModel: () -> Unit,
    onImportInstrumentPack: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var portalMounted by remember { mutableStateOf(show) }
    PlayerPopupPortal(active = show || portalMounted) {
        PlayerAnchoredGlassPopup(
            show = show,
            anchorBounds = anchorBounds,
            sourceArtworkBounds = null,
            artwork = null,
            onDismiss = onDismiss,
            onMountedChange = { portalMounted = it },
            layerZ = 40f,
        ) { _, _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_ai_performance_title),
                    color = scheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.enabled,
                    enabled = state.supported && !state.busy,
                    onCheckedChange = { enabled ->
                        onModeChange(
                            if (enabled) PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE
                            else PlayerAiPerformanceMode.ORIGINAL
                        )
                    },
                )
            }
            if (state.busy) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.onSurface.copy(alpha = 0.10f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(state.normalizedProgress.coerceAtLeast(0.02f))
                            .height(3.dp)
                            .background(scheme.primary),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
            ) {
                Column(
                    modifier = Modifier.weight(0.20f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    PlayerAiModeTab(
                        label = stringResource(R.string.player_ai_performance_instrument_short),
                        selected = state.mode == PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE,
                        enabled = state.supported && !state.busy,
                        onClick = { onModeChange(PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE) },
                    )
                    PlayerAiModeTab(
                        label = stringResource(R.string.player_ai_performance_ensemble_short),
                        selected = state.mode == PlayerAiPerformanceMode.VOCAL_ENSEMBLE,
                        enabled = state.supported && !state.busy,
                        onClick = { onModeChange(PlayerAiPerformanceMode.VOCAL_ENSEMBLE) },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(scheme.onSurface.copy(alpha = 0.10f)),
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier
                        .weight(0.80f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (state.instruments.isEmpty()) {
                        Text(
                            text = stringResource(R.string.player_ai_performance_no_instrument),
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                        )
                    } else {
                        state.instruments.chunked(4).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                row.forEach { option ->
                                    PlayerAiInstrumentTile(
                                        option = option,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onInstrumentChange(option.key) },
                                    )
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                    }
                    if (state.instruments.isEmpty() || !state.melodyModelReady || state.busy) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!state.melodyModelReady) {
                                Text(
                                    text = stringResource(R.string.player_ai_performance_model),
                                    color = scheme.primary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable(onClick = onImportMelodyModel),
                                )
                            }
                            if (state.instruments.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.player_ai_performance_import_pack),
                                    color = scheme.primary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable(onClick = onImportInstrumentPack),
                                )
                            }
                            if (state.busy) {
                                Text(
                                    text = stringResource(R.string.player_ai_performance_cancel),
                                    color = scheme.primary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable(onClick = onCancel),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun PlayerAiModeTab(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Text(
        text = label,
        color = when {
            !enabled -> scheme.onSurface.copy(alpha = 0.28f)
            selected -> scheme.primary
            else -> scheme.onSurface
        },
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 3.dp),
    )
}

@Composable
private fun PlayerAiInstrumentTile(
    option: PlayerAiInstrumentOption,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val iconRes = when (option.iconKey) {
        "guitar", "cello" -> R.drawable.ic_music_2_fill
        "drum", "handpan", "triangle", "tambourine" -> R.drawable.ic_equalizer_bars
        else -> R.drawable.ic_music_note
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (option.selected) scheme.primary.copy(alpha = 0.12f) else scheme.onSurface.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (option.selected) scheme.primary else scheme.onSurface),
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = option.label,
            color = if (option.selected) scheme.primary else scheme.onSurface,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
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
    containerColor: Color? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
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
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor ?: scheme.surfaceContainerHigh.copy(alpha = 0.52f))
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
    onImportRemote: () -> Unit,
    onRemoteAnchorBoundsChanged: (Rect) -> Unit = {},
    containerColor: Color? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val currentUri = state.resolve(songKey)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor ?: scheme.surfaceContainerHigh.copy(alpha = 0.52f))
            .padding(10.dp)
    ) {
        ImmersiveSettingToggleRow(
            title = stringResource(R.string.player_video_cover),
            subtitle = stringResource(R.string.player_video_cover_summary),
            enabled = true,
            checked = state.enabled,
            onClick = { VideoCoverPreferences.setEnabled(!state.enabled) }
        )
        Spacer(Modifier.height(6.dp))
        val modeTitles = mapOf(
            VideoCoverMode.PERMANENT to stringResource(R.string.player_video_cover_permanent),
            VideoCoverMode.CURRENT to stringResource(R.string.player_video_cover_current),
            VideoCoverMode.TEMPORARY to stringResource(R.string.player_video_cover_temporary)
        )
        val modeSummaries = mapOf(
            VideoCoverMode.PERMANENT to stringResource(R.string.player_video_cover_permanent_summary),
            VideoCoverMode.CURRENT to stringResource(R.string.player_video_cover_current_summary),
            VideoCoverMode.TEMPORARY to stringResource(R.string.player_video_cover_temporary_summary)
        )
        val modeDropdown = DropdownEntry(
            items = VideoCoverMode.entries.map { mode ->
                DropdownItem(
                    text = modeTitles.getValue(mode),
                    summary = modeSummaries.getValue(mode),
                    selected = mode == state.mode,
                    onClick = { VideoCoverPreferences.setMode(mode) }
                )
            }
        )
        RawWindowDropdownPreference(
            entry = modeDropdown,
            title = stringResource(R.string.player_video_cover_mode),
            summary = stringResource(R.string.player_video_cover_mode_summary),
            showValue = true,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            maxHeight = 360.dp,
            collapseOnSelection = true
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.primary.copy(alpha = 0.14f))
                    .clickable(enabled = songKey.isNotBlank(), onClick = onPickVideo)
                    .padding(horizontal = 8.dp),
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.primary.copy(alpha = 0.14f))
                    .onGloballyPositioned { coordinates ->
                        onRemoteAnchorBoundsChanged(coordinates.boundsInRoot())
                    }
                    .clickable(enabled = songKey.isNotBlank(), onClick = onImportRemote)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.player_video_cover_remote_import),
                    color = scheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (currentUri != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.onSurfaceVariantSummary.copy(alpha = 0.12f))
                        .clickable { VideoCoverPreferences.clearForMode(songKey) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.player_video_cover_clear), color = scheme.onSurface, fontSize = 14.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
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
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.player_music_video_open),
                        color = scheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoCoverRemoteImportDialog(
    show: Boolean,
    anchorBounds: Rect?,
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
    var portalMounted by remember { mutableStateOf(show) }
    PlayerPopupPortal(active = show || portalMounted) {
        PlayerAnchoredGlassPopup(
            show = show,
            anchorBounds = anchorBounds,
            sourceArtworkBounds = null,
            artwork = null,
            onDismiss = { if (!busy) onDismiss() },
            onMountedChange = { portalMounted = it },
            layerZ = 40f,
        ) { _, _ ->
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
                text = stringResource(R.string.player_video_cover_remote_title),
                color = scheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.player_video_cover_remote_summary),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
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
                            candidate.artworkUrl.takeIf(String::isNotBlank)
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
                                    BitmapImage(
                                        key = artworkRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                        targetWidth = 192,
                                        targetHeight = 192,
                                        priority = BitmapRequest.Priority.LOADING_LIST,
                                        surface = ArtworkSurface.List,
                                        showDefaultArtwork = false,
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
                    selectedCandidate.artworkUrl.takeIf(String::isNotBlank)
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
                        selectedArtworkRequest != null -> BitmapImage(
                            key = selectedArtworkRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            targetWidth = 512,
                            targetHeight = 512,
                            priority = BitmapRequest.Priority.LOADING_WIDGET,
                            surface = ArtworkSurface.Playback,
                            showDefaultArtwork = false,
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
}

@Composable
private fun ImmersiveSettingToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.42f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = scheme.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else 0.42f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun ImmersiveProgressSettingsCard(
    progressStyle: ImmersiveProgressStyle,
    onProgressStyleChange: (ImmersiveProgressStyle) -> Unit,
    containerColor: Color? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val cardColor = if (isDark) scheme.surfaceContainerHigh.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.88f)
    val classicLabel = stringResource(R.string.immersive_progress_style_classic)
    val waveformLabel = stringResource(R.string.immersive_progress_style_waveform)
    val secondsLabel = stringResource(R.string.immersive_progress_style_seconds)
    val musicSpineLabel = stringResource(R.string.immersive_progress_style_music_spine)
    val progressStyleEntry = DropdownEntry(
        items = listOf(
            ImmersiveProgressStyle.Classic to classicLabel,
            ImmersiveProgressStyle.Waveform to waveformLabel,
            ImmersiveProgressStyle.Seconds to secondsLabel,
            ImmersiveProgressStyle.MusicSpine to musicSpineLabel,
        ).map { (style, label) ->
            DropdownItem(
                text = label,
                selected = progressStyle == style,
                onClick = { onProgressStyleChange(style) },
            )
        },
    )
    val selectedProgressStyleLabel = when (progressStyle) {
        ImmersiveProgressStyle.Classic -> classicLabel
        ImmersiveProgressStyle.Waveform -> waveformLabel
        ImmersiveProgressStyle.Seconds -> secondsLabel
        ImmersiveProgressStyle.MusicSpine -> musicSpineLabel
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor ?: cardColor)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.immersive_progress_settings_title),
            color = scheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        RawWindowDropdownPreference(
            entry = progressStyleEntry,
            title = stringResource(R.string.immersive_progress_style),
            summary = selectedProgressStyleLabel,
            showValue = true,
            maxHeight = 320.dp,
            collapseOnSelection = true,
        )
    }
}

@Composable
private fun ImmersiveVisualizerSettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    containerColor: Color? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val cardColor = if (isDark) {
        scheme.surfaceContainerHigh.copy(alpha = 0.62f)
    } else {
        Color.White.copy(alpha = 0.88f)
    }
    val style = LocalAudioVisualizerStyle.current
    val onStyleChange = LocalAudioVisualizerStyleChange.current
    val offLabel = stringResource(R.string.player_more_visualizer_off)
    val spectrumLabel = stringResource(R.string.player_more_visualizer_spectrum)
    val particleLabel = stringResource(R.string.player_more_visualizer_particle)
    val particleTrailsLabel = stringResource(R.string.player_more_visualizer_particle_trails)
    val pulseRingsLabel = stringResource(R.string.player_more_visualizer_pulse_rings)
    val energyRibbonLabel = stringResource(R.string.player_more_visualizer_energy_ribbon)
    val rippleLabel = stringResource(R.string.player_more_visualizer_ripple)
    val selectedLabel = when {
        !enabled -> offLabel
        style == AudioVisualizerStyle.Particle -> particleLabel
        style == AudioVisualizerStyle.ParticleTrails -> particleTrailsLabel
        style == AudioVisualizerStyle.PulseRings -> pulseRingsLabel
        style == AudioVisualizerStyle.EnergyRibbon -> energyRibbonLabel
        style == AudioVisualizerStyle.Ripple -> rippleLabel
        else -> spectrumLabel
    }
    val entry = DropdownEntry(
        items = listOf(
            DropdownItem(
                text = offLabel,
                selected = !enabled,
                onClick = { onEnabledChange(false) },
            ),
            DropdownItem(
                text = spectrumLabel,
                selected = enabled && style == AudioVisualizerStyle.Spectrum,
                onClick = {
                    onStyleChange(AudioVisualizerStyle.Spectrum)
                    if (!enabled) onEnabledChange(true)
                },
            ),
            DropdownItem(
                text = particleLabel,
                selected = enabled && style == AudioVisualizerStyle.Particle,
                onClick = {
                    onStyleChange(AudioVisualizerStyle.Particle)
                    if (!enabled) onEnabledChange(true)
                },
            ),
            DropdownItem(
                text = particleTrailsLabel,
                selected = enabled && style == AudioVisualizerStyle.ParticleTrails,
                onClick = {
                    onStyleChange(AudioVisualizerStyle.ParticleTrails)
                    if (!enabled) onEnabledChange(true)
                },
            ),
            DropdownItem(
                text = pulseRingsLabel,
                selected = enabled && style == AudioVisualizerStyle.PulseRings,
                onClick = {
                    onStyleChange(AudioVisualizerStyle.PulseRings)
                    if (!enabled) onEnabledChange(true)
                },
            ),
            DropdownItem(
                text = energyRibbonLabel,
                selected = enabled && style == AudioVisualizerStyle.EnergyRibbon,
                onClick = {
                    onStyleChange(AudioVisualizerStyle.EnergyRibbon)
                    if (!enabled) onEnabledChange(true)
                },
            ),
            DropdownItem(
                text = rippleLabel,
                selected = enabled && style == AudioVisualizerStyle.Ripple,
                onClick = {
                    onStyleChange(AudioVisualizerStyle.Ripple)
                    if (!enabled) onEnabledChange(true)
                },
            ),
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor ?: cardColor)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.player_more_visualizer),
            color = scheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.player_more_visualizer_style_summary),
            color = scheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        RawWindowDropdownPreference(
            entry = entry,
            title = stringResource(R.string.player_more_visualizer_effect),
            summary = selectedLabel,
            showValue = true,
            maxHeight = 420.dp,
            collapseOnSelection = true,
        )
    }
}

@Composable
private fun PlayerMoreAcrylicTile(
    @DrawableRes iconRes: Int,
    title: String,
    summary: String? = null,
    value: String? = null,
    cardColor: Color,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit = {},
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(cardColor)
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(coordinates.boundsInRoot())
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != 0) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(scheme.onSurface),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = scheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = value,
                color = scheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
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
