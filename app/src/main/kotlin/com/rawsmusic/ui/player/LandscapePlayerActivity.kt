@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.rawsmusic.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.helper.SongActionSheetHelper
import com.rawsmusic.helper.SongActionSheetOverlay
import com.rawsmusic.core.ui.widget.player.PlayerPlaylistActionProvider
import com.rawsmusic.core.ui.widget.player.rememberPlayerPlaylistAction
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.core.ui.theme.RawSMusicTheme
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransition
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkMetadataTransition
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkAnimationStyle
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.playbackArtworkSwipeGesture
import com.rawsmusic.core.ui.widget.bitmaps.rememberPlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackQueueBindingIndex
import com.rawsmusic.core.ui.widget.player.ComposeLyricView
import com.rawsmusic.core.ui.widget.player.AlbumArtworkSpectrumOverlay
import com.rawsmusic.core.ui.widget.player.AudioVisualizerLayer
import com.rawsmusic.core.ui.widget.player.AudioVisualizerParticleColorMode
import com.rawsmusic.core.ui.widget.player.AudioVisualizerStyle
import com.rawsmusic.core.ui.widget.player.FullCoverPage
import com.rawsmusic.core.ui.widget.player.FullCoverPredictiveBackHandler
import com.rawsmusic.core.ui.widget.player.resolveFullCoverPredictiveProgress
import com.rawsmusic.core.ui.widget.player.resolveLandscapeFullCoverTransitionFrame
import com.rawsmusic.core.ui.widget.player.resolveLandscapeLaunchSourceBounds
import com.rawsmusic.core.ui.widget.player.ReusablePlayerTimelineProgress
import com.rawsmusic.core.ui.widget.player.LyricMoreSheetContent
import com.rawsmusic.core.ui.widget.player.LyricTextPosition
import com.rawsmusic.core.ui.widget.player.LocalAudioVisualizerParticleColorMode
import com.rawsmusic.core.ui.widget.player.SharedRealtimeVisualizerOverlay
import com.rawsmusic.core.ui.widget.player.StandardPlayerBackdrop
import com.rawsmusic.core.ui.widget.player.rememberCoverAccentColor
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlayerProgressPreferences
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.player.dsp.NativeStereoSpectrumAnalyzer
import com.rawsmusic.module.player.dsp.RealtimeSpectrumPipeline
import com.rawsmusic.module.player.control.PcmWaveformConsumerBinding
import com.rawsmusic.module.scanner.LyricReader
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

class LandscapePlayerActivity : ComponentActivity() {

    private var portraitExitListener: OrientationEventListener? = null
    private var allowPortraitExit = false
    private var finishingForPortraitRotation = false
    private var visualizerRuntime: LandscapeVisualizerRuntime? = null
    private var playlistModalVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enter through a user-respecting landscape request first. The persisted in-app lock is
        // applied from Compose after the landscape surface exists, avoiding a portrait LOCKED
        // race when this Activity is launched from the portrait main screen.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        configureFullscreenWindow()
        val fullscreenLaunchRequest = FullscreenCarouselLaunchRequest.fromIntent(intent)

        val playerController = PlayerService.currentRuntimeController()
            ?: PlayerController.getInstanceOrNull()
            ?: PlayerService.obtainRuntimeController(
                this,
                "landscape_player_activity",
                ensureService = true
            )
        val localVisualizerRuntime = LandscapeVisualizerRuntime(playerController)
        visualizerRuntime = localVisualizerRuntime
        val playlistHelper = SongActionSheetHelper(
            context = this,
            getPlayerController = { playerController },
            setGestureInterceptDisabled = { playlistModalVisible = it },
            closePlayPage = {},
            resolveCoverUri = { it.resolvePlaybackArtworkKey().orEmpty() },
            getCoroutineScope = { lifecycleScope },
            openAlbumDetail = { _, _, _ -> },
        )

        setContent {
            RawSMusicTheme {
                com.rawsmusic.core.ui.widget.bitmaps.SceneHdrWindow(com.rawsmusic.module.data.prefs.HdrScene.PLAYER)
                val noticeBackdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    PlayerPlaylistActionProvider(onAddSong = { song ->
                        if (!playlistHelper.isPlaylistPickerShowing) {
                            playlistHelper.showPlayerPlaylistPicker(song)
                        }
                    }) {
                        top.yukonga.miuix.kmp.basic.Scaffold(
                            modifier = Modifier.fillMaxSize().layerBackdrop(noticeBackdrop),
                            containerColor = MiuixTheme.colorScheme.background,
                            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                        ) {
                            LandscapePlayerScreen(
                                playerController = playerController,
                                visualizerRuntime = localVisualizerRuntime,
                                directFullscreenLaunch = fullscreenLaunchRequest,
                                playlistModalVisible = playlistModalVisible,
                                onBack = ::finish
                            )
                        }
                    }
                    SongActionSheetOverlay(
                        helper = playlistHelper,
                        isImmersiveEnabled = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                    com.rawsmusic.core.ui.scene.AppNoticeHost(
                        backdrop = noticeBackdrop,
                        modifier = Modifier.fillMaxSize().zIndex(2000f),
                    )
                }
            }
        }

        portraitExitListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (!allowPortraitExit || orientation == ORIENTATION_UNKNOWN) return
                if (AppPreferences.UI.landscapePlayerLocked) return
                if (isAutoRotateEnabled(this@LandscapePlayerActivity) && orientation.isPortraitAngle()) {
                    if (finishingForPortraitRotation) return
                    finishingForPortraitRotation = true
                    finish()
                }
            }
        }
        if (fullscreenLaunchRequest == null) {
            window.decorView.postDelayed({ allowPortraitExit = true }, 900L)
        }
    }

    override fun onResume() {
        super.onResume()
        portraitExitListener?.takeIf { it.canDetectOrientation() }?.enable()
        visualizerRuntime?.setForeground(true)
    }

    override fun onPause() {
        visualizerRuntime?.setForeground(false)
        portraitExitListener?.disable()
        super.onPause()
    }

    override fun onDestroy() {
        portraitExitListener?.disable()
        portraitExitListener = null
        visualizerRuntime?.close()
        visualizerRuntime = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        if (finishingForPortraitRotation) {
            // Keep the outgoing landscape surface opaque while the retained portrait player
            // receives its final size/insets, then fade it away. Predictive back already provides
            // this masking, which is why only sensor-driven portrait exits used to visibly jump.
            overridePendingTransition(0, R.anim.landscape_player_rotate_exit)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    private fun configureFullscreenWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        internal const val EXTRA_DIRECT_FULLSCREEN =
            "com.rawsmusic.extra.DIRECT_FULLSCREEN_CAROUSEL"
        internal const val EXTRA_SOURCE_LEFT = "com.rawsmusic.extra.FULL_COVER_SOURCE_LEFT"
        internal const val EXTRA_SOURCE_TOP = "com.rawsmusic.extra.FULL_COVER_SOURCE_TOP"
        internal const val EXTRA_SOURCE_RIGHT = "com.rawsmusic.extra.FULL_COVER_SOURCE_RIGHT"
        internal const val EXTRA_SOURCE_BOTTOM = "com.rawsmusic.extra.FULL_COVER_SOURCE_BOTTOM"
        internal const val EXTRA_SOURCE_VIEWPORT_WIDTH =
            "com.rawsmusic.extra.FULL_COVER_SOURCE_VIEWPORT_WIDTH"
        internal const val EXTRA_SOURCE_VIEWPORT_HEIGHT =
            "com.rawsmusic.extra.FULL_COVER_SOURCE_VIEWPORT_HEIGHT"

        fun createIntent(context: Context): Intent =
            Intent(context, LandscapePlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun createFullscreenCarouselIntent(
            context: Context,
            sourceBounds: Rect,
            sourceViewportWidthPx: Float,
            sourceViewportHeightPx: Float,
        ): Intent = createIntent(context).apply {
            putExtra(EXTRA_DIRECT_FULLSCREEN, true)
            putExtra(EXTRA_SOURCE_LEFT, sourceBounds.left)
            putExtra(EXTRA_SOURCE_TOP, sourceBounds.top)
            putExtra(EXTRA_SOURCE_RIGHT, sourceBounds.right)
            putExtra(EXTRA_SOURCE_BOTTOM, sourceBounds.bottom)
            putExtra(EXTRA_SOURCE_VIEWPORT_WIDTH, sourceViewportWidthPx)
            putExtra(EXTRA_SOURCE_VIEWPORT_HEIGHT, sourceViewportHeightPx)
        }

        fun isAutoRotateEnabled(context: Context): Boolean = runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            ) == 1
        }.getOrDefault(false)
    }
}

private typealias LandscapePcmWaveformCallback = (
    buffer: ByteArray,
    read: Int,
    channels: Int,
    sampleRate: Int,
    validBitsPerSample: Int,
    sampleEncoding: Int,
) -> Unit

/**
 * Temporary realtime-spectrum owner for the dedicated landscape Activity.
 *
 * MainActivity owns the same PCM callback while portrait UI is foreground. Landscape takes that
 * callback only for its resumed lifetime, restores the previous owner on pause, and never creates a
 * second playback transport. Music Spine can keep this runtime active even when the optional visual
 * overlay itself is disabled.
 */
private class LandscapeVisualizerRuntime(
    private val controller: PlayerController,
) : AutoCloseable {
    val spectrumState = mutableStateOf(
        FloatArray(NativeStereoSpectrumAnalyzer.OUTPUT_SIZE),
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pipeline = RealtimeSpectrumPipeline { spectrum ->
        mainHandler.post {
            if (foreground && requested) {
                spectrumState.value = spectrum
            }
        }
    }
    private var foreground = false
    private var requested = false
    private var playing = false
    private var closed = false

    private val callback: LandscapePcmWaveformCallback = waveform@{
            buffer, read, channels, sampleRate, validBitsPerSample, sampleEncoding ->
        if (!foreground || !requested || closed) return@waveform
        pipeline.submit(
            buffer = buffer,
            read = read,
            channels = channels,
            sampleRate = sampleRate,
            sampleEncoding = sampleEncoding,
            validBitsPerSample = validBitsPerSample,
        )
    }

    private val consumerBinding = PcmWaveformConsumerBinding(
        callback = callback,
        currentCallback = { controller.onPcmWaveformFrame },
        setCallback = { controller.onPcmWaveformFrame = it },
        setCaptureActive = { controller.ffmpegPlayerRef.setWaveformConsumerActive(it) },
    )

    fun setForeground(value: Boolean) {
        if (foreground == value || closed) return
        foreground = value
        if (!value) consumerBinding.release()
        updateRuntime()
    }

    fun setRequested(value: Boolean) {
        if (requested == value || closed) return
        requested = value
        updateRuntime()
    }

    fun setPlaying(value: Boolean) {
        if (playing == value || closed) return
        playing = value
        pipeline.setPlaying(value)
    }

    private fun updateRuntime() {
        val active = foreground && requested && !closed
        consumerBinding.update(active)
        pipeline.setPlaying(playing)
        pipeline.setActive(active)
        if (!active) {
            spectrumState.value = FloatArray(NativeStereoSpectrumAnalyzer.OUTPUT_SIZE)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        consumerBinding.release()
        pipeline.setActive(false)
        pipeline.close()
    }
}

private data class FullscreenCarouselLaunchRequest(
    val sourceBounds: Rect?,
    val sourceViewportWidthPx: Float,
    val sourceViewportHeightPx: Float,
) {
    companion object {
        fun fromIntent(intent: Intent): FullscreenCarouselLaunchRequest? {
            if (!intent.getBooleanExtra(
                    LandscapePlayerActivity.EXTRA_DIRECT_FULLSCREEN,
                    false,
                )
            ) {
                return null
            }
            val sourceWidth = intent.getFloatExtra(
                LandscapePlayerActivity.EXTRA_SOURCE_VIEWPORT_WIDTH,
                0f,
            )
            val sourceHeight = intent.getFloatExtra(
                LandscapePlayerActivity.EXTRA_SOURCE_VIEWPORT_HEIGHT,
                0f,
            )
            val bounds = Rect(
                left = intent.getFloatExtra(LandscapePlayerActivity.EXTRA_SOURCE_LEFT, 0f),
                top = intent.getFloatExtra(LandscapePlayerActivity.EXTRA_SOURCE_TOP, 0f),
                right = intent.getFloatExtra(LandscapePlayerActivity.EXTRA_SOURCE_RIGHT, 0f),
                bottom = intent.getFloatExtra(LandscapePlayerActivity.EXTRA_SOURCE_BOTTOM, 0f),
            ).takeIf { it.width > 1f && it.height > 1f }
            return FullscreenCarouselLaunchRequest(bounds, sourceWidth, sourceHeight)
        }
    }
}

private enum class LandscapeArtworkScene {
    Player,
    FullscreenCarousel,
}

@Composable
private fun LandscapePlayerScreen(
    playerController: PlayerController,
    visualizerRuntime: LandscapeVisualizerRuntime,
    directFullscreenLaunch: FullscreenCarouselLaunchRequest?,
    playlistModalVisible: Boolean,
    onBack: () -> Unit
) {
    val currentSong by playerController.currentSong.collectAsState()
    val playState by playerController.playState.collectAsState()
    val positionMs by playerController.position.collectAsState()
    val durationMs by playerController.duration.collectAsState()
    val queue by playerController.visibleQueue.collectAsState()
    val isPlaying = playState == PlayState.PLAYING
    val artworkKey = currentSong.resolvePlaybackArtworkKey()
    val committedQueueIndex = resolvePlaybackQueueBindingIndex(
        currentSong = currentSong,
        queueSongs = queue.songs,
        reportedIndex = queue.currentIndex,
    )
    val artworkState = rememberPlaybackArtworkTransitionState(
        currentKey = artworkKey,
        queueCurrentIndex = committedQueueIndex,
        queueSize = queue.songs.size,
        automaticCrossfadeEnabled = AppPreferences.Player.automaticCrossfadeEnabled,
    )
    val artworkStyle = remember {
        PlayerArtworkAnimationStyle.from(AppPreferences.UI.playerArtworkAnimationStyle)
    }
    val previousNavigationArtworkKey = playerController
        .previewPreviousSong(queue)
        .resolvePlaybackArtworkKey()
    val nextNavigationArtworkKey = playerController
        .previewNextSong(queue)
        .resolvePlaybackArtworkKey()
    // Reference portrait/landscape share the same ArtworkPagerMotion/ArtworkPager artwork holders. Keep landscape on the
    // same hot previous/current/next identities as the standard portrait player instead of
    // constructing its neighbour for the first time after a drag has already started.
    SideEffect {
        artworkState.updateNavigationNeighbourKeys(
            previousKey = previousNavigationArtworkKey,
            nextKey = nextNavigationArtworkKey,
        )
    }
    LaunchedEffect(previousNavigationArtworkKey, nextNavigationArtworkKey) {
        artworkState.prefetchNavigationNeighbours(
            previousKey = previousNavigationArtworkKey,
            nextKey = nextNavigationArtworkKey,
        )
    }
    val lyricSong by produceState<Song?>(initialValue = null, currentSong) {
        val song = currentSong
        value = if (song == null) {
            null
        } else {
            value = null
            withContext(Dispatchers.IO) {
                LyricReader.readLyrics(song).toLyriconSong(
                    name = song.title.ifBlank { song.displayName },
                    artist = song.artist,
                    durationMs = song.duration
                )
            }
        }
    }
    val isDirectFullscreenLaunch = directFullscreenLaunch != null
    var controlsVisible by remember { mutableStateOf(true) }
    var artworkScene by remember(isDirectFullscreenLaunch) {
        mutableStateOf(
            if (isDirectFullscreenLaunch) LandscapeArtworkScene.FullscreenCarousel
            else LandscapeArtworkScene.Player
        )
    }
    val artworkSceneProgress = remember(isDirectFullscreenLaunch) {
        Animatable(
            if (isDirectFullscreenLaunch && directFullscreenLaunch?.sourceBounds == null) 1f
            else 0f
        )
    }
    val artworkSceneScope = rememberCoroutineScope()
    var artworkSceneTransitionRunning by remember { mutableStateOf(false) }
    var artworkSceneTransitionJob by remember { mutableStateOf<Job?>(null) }
    var fullscreenPredictiveBackActive by remember { mutableStateOf(false) }
    var fullscreenPredictiveBackStartProgress by remember { mutableFloatStateOf(0f) }
    var fullscreenPredictiveVisualProgress by remember { mutableFloatStateOf(0f) }
    var playerArtworkBounds by remember { mutableStateOf<Rect?>(null) }
    var sharedTransitionSourceBounds by remember { mutableStateOf<Rect?>(null) }
    var topOverlayVisible by remember { mutableStateOf(false) }
    var moreVisible by remember { mutableStateOf(false) }
    var moreAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var moreArtworkOverlayMounted by remember { mutableStateOf(false) }
    val addSongToPlaylist = rememberPlayerPlaylistAction()
    var landscapeLocked by remember { mutableStateOf(AppPreferences.UI.landscapePlayerLocked) }
    val activity = LocalContext.current.findActivity()
    var overlayActivityToken by remember { mutableIntStateOf(0) }
    val displayTranslation by LyriconProviderManager.displayTranslationState.collectAsState()
    val displayRoma by LyriconProviderManager.displayRomaState.collectAsState()
    var blurEnabled by remember { mutableStateOf(AppPreferences.UI.lyricBlurEnabled) }
    var relativeLineProgressEnabled by remember {
        mutableStateOf(AppPreferences.UI.lyricRelativeLineProgressEnabled)
    }
    var highlightAll by remember {
        mutableStateOf(AppPreferences.UI.lyricHighlightAllEnabled)
    }
    var lyricFontSizeSp by remember { mutableStateOf(AppPreferences.UI.lyricFontSizeSp) }
    var lyricTextPosition by remember {
        mutableStateOf(LyricTextPosition.from(AppPreferences.UI.lyricTextPosition))
    }
    val progressStyleValue by PlayerProgressPreferences.progressStyle.collectAsState()
    var audioVisualizerEnabled by remember {
        mutableStateOf(AppPreferences.UI.isAudioVisualizerEnabled)
    }
    var audioVisualizerStyle by remember {
        mutableStateOf(AudioVisualizerStyle.from(AppPreferences.UI.audioVisualizerStyle))
    }
    val audioVisualizerParticleColorMode = remember {
        AudioVisualizerParticleColorMode.from(AppPreferences.UI.audioVisualizerParticleColorMode)
    }
    val audioSpectrumState: State<FloatArray> = visualizerRuntime.spectrumState
    val audioSpectrumFallback = remember { FloatArray(0) }
    val realtimeSpectrumNeeded =
        audioVisualizerEnabled || progressStyleValue == 3
    LaunchedEffect(realtimeSpectrumNeeded, isPlaying) {
        visualizerRuntime.setRequested(realtimeSpectrumNeeded)
        visualizerRuntime.setPlaying(isPlaying)
    }
    val waveformColorMode by PlayerProgressPreferences.waveformColorMode.collectAsState()
    val waveformRemainingColorInt by PlayerProgressPreferences.remainingColor.collectAsState()
    val waveformPlayedColorInt by PlayerProgressPreferences.playedColor.collectAsState()
    val waveformClimaxColorInt by PlayerProgressPreferences.climaxColor.collectAsState()
    val waveformClimaxEnabled by PlayerProgressPreferences.climaxEnabled.collectAsState()
    var backgroundReady by remember { mutableStateOf(false) }
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (backgroundReady) 1f else 0f,
        animationSpec = tween(2_500),
        label = "landscapeBackgroundFade"
    )

    LaunchedEffect(Unit) {
        backgroundReady = true
    }
    LaunchedEffect(topOverlayVisible, moreVisible, overlayActivityToken) {
        if (topOverlayVisible && !moreVisible) {
            delay(3_000L)
            topOverlayVisible = false
        }
    }
    fun animateArtworkSceneTo(
        target: Float,
        baseDurationMs: Int,
        startProgress: Float = artworkSceneProgress.value,
        onFinished: () -> Unit = {},
    ) {
        artworkSceneTransitionJob?.cancel()
        artworkSceneTransitionRunning = true
        artworkSceneTransitionJob = artworkSceneScope.launch {
            val start = startProgress.coerceIn(0f, 1f)
            try {
                artworkSceneProgress.snapTo(start)
                val distance = kotlin.math.abs(target - start).coerceIn(0f, 1f)
                artworkSceneProgress.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = (baseDurationMs * distance).roundToInt().coerceIn(72, baseDurationMs),
                        easing = FastOutSlowInEasing,
                    ),
                )
                onFinished()
            } finally {
                if (artworkSceneTransitionJob == kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    artworkSceneTransitionRunning = false
                    artworkSceneTransitionJob = null
                }
            }
        }
    }

    fun openFullscreenCarousel() {
        if (artworkSceneProgress.value >= 0.999f) return
        val sourceBounds = playerArtworkBounds ?: return
        sharedTransitionSourceBounds = sourceBounds
        topOverlayVisible = false
        moreVisible = false
        artworkScene = LandscapeArtworkScene.FullscreenCarousel
        fullscreenPredictiveBackActive = false
        animateArtworkSceneTo(target = 1f, baseDurationMs = 360)
    }

    fun closeFullscreenCarousel(startProgress: Float? = null) {
        val resolvedStart = (startProgress ?: artworkSceneProgress.value).coerceIn(0f, 1f)
        if (artworkScene == LandscapeArtworkScene.Player && resolvedStart <= 0.001f) return
        fullscreenPredictiveBackActive = false
        animateArtworkSceneTo(
            target = 0f,
            baseDurationMs = 320,
            startProgress = resolvedStart,
            onFinished = {
                if (isDirectFullscreenLaunch) {
                    onBack()
                } else {
                    artworkScene = LandscapeArtworkScene.Player
                    sharedTransitionSourceBounds = null
                }
            },
        )
    }

    fun cancelFullscreenPredictiveBack(startProgress: Float) {
        fullscreenPredictiveBackActive = false
        animateArtworkSceneTo(
            target = 1f,
            baseDurationMs = 320,
            startProgress = startProgress,
        )
    }

    val fullscreenSceneActive = artworkScene == LandscapeArtworkScene.FullscreenCarousel ||
        artworkSceneTransitionRunning || artworkSceneProgress.value > 0.001f ||
        fullscreenPredictiveBackActive
    FullCoverPredictiveBackHandler(
        enabled = fullscreenSceneActive && !playlistModalVisible,
        onProgress = { backProgress ->
            if (!fullscreenPredictiveBackActive) {
                artworkSceneTransitionJob?.cancel()
                artworkSceneTransitionJob = null
                fullscreenPredictiveBackStartProgress = artworkSceneProgress.value.coerceIn(0f, 1f)
                fullscreenPredictiveVisualProgress = fullscreenPredictiveBackStartProgress
                fullscreenPredictiveBackActive = true
                artworkSceneTransitionRunning = true
            }
            fullscreenPredictiveVisualProgress = resolveFullCoverPredictiveProgress(
                fullscreenPredictiveBackStartProgress,
                backProgress,
            )
        },
        onCancelled = {
            val current = if (fullscreenPredictiveBackActive) {
                fullscreenPredictiveVisualProgress
            } else {
                artworkSceneProgress.value
            }.coerceIn(0f, 1f)
            cancelFullscreenPredictiveBack(current)
        },
        onCompleted = {
            val current = if (fullscreenPredictiveBackActive) {
                fullscreenPredictiveVisualProgress
            } else {
                artworkSceneProgress.value
            }.coerceIn(0f, 1f)
            closeFullscreenCarousel(current)
        },
    )
    BackHandler(
        enabled = !fullscreenSceneActive && landscapeLocked && !moreVisible && !playlistModalVisible,
    ) {
        // A locked landscape session ignores the system back gesture. The explicit top-bar back
        // button remains available so the user can always leave intentionally.
    }
    LaunchedEffect(landscapeLocked, activity) {
        activity?.requestedOrientation = if (landscapeLocked) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            // Respect the user's system rotation lock. Unlike SENSOR_LANDSCAPE, USER_LANDSCAPE
            // lets SystemUI offer the standard rotate-suggestion affordance when supported.
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
    }

    val backgroundTapInteraction = remember { MutableInteractionSource() }
    val lyricTapInteraction = remember { MutableInteractionSource() }

    fun songIndex(song: AudioFile?): Int {
        if (song == null) return -1
        return queue.songs.indexOfFirst {
            it.path == song.path &&
                it.cueOffsetMs == song.cueOffsetMs &&
                it.cueTrackIndex == song.cueTrackIndex
        }
    }

    fun issueTrackCommand(
        direction: PlayerArtworkDirection,
        command: () -> AudioFile?,
        gestureCommand: () -> AudioFile?
    ) {
        val gestureTargetKey = artworkState.pendingGestureTarget(direction)
        val previewKey = artworkState.manualNavigationTargetKey(direction)
        val previewIndex = previewKey?.takeIf { it.isNotBlank() }?.let { key ->
            queue.songs.indexOfFirst { it.resolvePlaybackArtworkKey() == key }
        } ?: -1

        artworkState.armManualNavigation(
            direction = direction,
            expectedKey = previewKey,
            expectedQueueIndex = previewIndex,
        )

        // Match portrait/Reference ownership: transport is submitted immediately; the authoritative
        // committed track/index binding starts programmatic page motion. Gesture settle remains the
        // sole visual owner when this callback came from direct manipulation.
        val selectedSong = if (gestureTargetKey != null) gestureCommand() else command()
        val selectedKey = selectedSong.resolvePlaybackArtworkKey()
        val selectedIndex = songIndex(selectedSong)

        if (selectedKey.isNullOrBlank() || selectedKey == artworkState.foregroundCurrentKey()) {
            artworkState.cancelManualNavigationExpectation()
            return
        }
        if (previewKey != selectedKey) {
            artworkState.cancelManualNavigationExpectation()
            artworkState.armManualNavigation(
                direction = direction,
                expectedKey = selectedKey,
                expectedQueueIndex = selectedIndex,
            )
        }
        if (gestureTargetKey != null) {
            artworkState.confirmManualNavigationBinding(selectedKey, selectedIndex, direction)
        }
    }

    fun previousFromPlayer() {
        issueTrackCommand(
            direction = PlayerArtworkDirection.Previous,
            command = playerController::previous,
            gestureCommand = playerController::previousTrackFromArtworkGesture
        )
    }

    fun nextFromPlayer() {
        issueTrackCommand(
            direction = PlayerArtworkDirection.Next,
            command = playerController::next,
            gestureCommand = playerController::nextTrackFromArtworkGesture
        )
    }

    val density = LocalDensity.current
    val transitionProgress = if (fullscreenPredictiveBackActive) {
        fullscreenPredictiveVisualProgress.coerceIn(0f, 1f)
    } else {
        artworkSceneProgress.value.coerceIn(0f, 1f)
    }
    val showPlayerScene = !isDirectFullscreenLaunch && (
        artworkScene == LandscapeArtworkScene.Player ||
            artworkSceneTransitionRunning || transitionProgress < 0.999f
        )
    val showFullCoverScene = artworkScene == LandscapeArtworkScene.FullscreenCarousel ||
        artworkSceneTransitionRunning || transitionProgress > 0.001f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val targetRotationDegrees = when (activity?.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        LaunchedEffect(
            isDirectFullscreenLaunch,
            directFullscreenLaunch,
            viewportWidthPx,
            viewportHeightPx,
            targetRotationDegrees,
        ) {
            if (
                !isDirectFullscreenLaunch ||
                artworkSceneProgress.value > 0.001f ||
                viewportWidthPx <= viewportHeightPx
            ) {
                return@LaunchedEffect
            }
            val request = directFullscreenLaunch ?: return@LaunchedEffect
            val mappedSource = request.sourceBounds?.let { source ->
                resolveLandscapeLaunchSourceBounds(
                    sourceBounds = source,
                    sourceViewportWidthPx = request.sourceViewportWidthPx,
                    sourceViewportHeightPx = request.sourceViewportHeightPx,
                    targetViewportWidthPx = viewportWidthPx,
                    targetViewportHeightPx = viewportHeightPx,
                    targetRotationDegrees = targetRotationDegrees,
                )
            }
            if (mappedSource == null) {
                artworkSceneProgress.snapTo(1f)
            } else {
                sharedTransitionSourceBounds = mappedSource
                animateArtworkSceneTo(target = 1f, baseDurationMs = 360)
            }
        }
        val transitionFrame = (
            sharedTransitionSourceBounds ?: playerArtworkBounds.takeUnless { isDirectFullscreenLaunch }
        )?.let { sourceBounds ->
            resolveLandscapeFullCoverTransitionFrame(
                sourceLeftPx = sourceBounds.left,
                sourceTopPx = sourceBounds.top,
                sourceWidthPx = sourceBounds.width,
                sourceHeightPx = sourceBounds.height,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                progress = transitionProgress,
            )
        }
        val sourceContentAlpha = transitionFrame?.sourceContentAlpha ?: (1f - transitionProgress)
        val sourceContentScale = transitionFrame?.sourceContentScale ?: 1f
        val targetContentAlpha = transitionFrame?.targetContentAlpha ?: transitionProgress
        val targetContentScale = transitionFrame?.targetContentScale ?: 1f
        val morePanelBackgroundBackdrop = rememberLayerBackdrop()
        val morePanelSceneBackdrop = rememberLayerBackdrop()
        val morePanelBackdrop = rememberCombinedBackdrop(
            morePanelBackgroundBackdrop,
            morePanelSceneBackdrop,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .layerBackdrop(morePanelBackgroundBackdrop),
        ) {
            // The backdrop is one persistent scene layer. Step81 rendered a second backdrop inside
            // FullCoverPage, so the two RawFlow instances and their different scrims briefly crossed.
            // Keeping this layer outside both foreground scenes also means the shared artwork can fly
            // without dragging or fading the background with either side.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundAlpha),
            ) {
            StandardPlayerBackdrop(
                coverPath = artworkKey,
                accent = Color.Transparent,
                artworkTransitionState = artworkState,
                motionEnabled = isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(morePanelSceneBackdrop),
        ) {
        if (showPlayerScene) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = sourceContentAlpha
                        scaleX = sourceContentScale
                        scaleY = sourceContentScale
                    }
            ) {
                // Retain the player's background tap target, but leave all backdrop drawing in
                // the shared layer above. This foreground node participates in the source-only
                // scale/fade while the background remains visually stable.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            interactionSource = backgroundTapInteraction,
                            indication = null,
                            onClick = {
                                if (!artworkSceneTransitionRunning) {
                                    topOverlayVisible = !topOverlayVisible
                                    overlayActivityToken++
                                    if (!topOverlayVisible) moreVisible = false
                                }
                            }
                        )
                )

                CompositionLocalProvider(
                    LocalAudioVisualizerParticleColorMode provides audioVisualizerParticleColorMode,
                ) {
                    SharedRealtimeVisualizerOverlay(
                        style = audioVisualizerStyle,
                        spectrum = audioSpectrumFallback,
                        spectrumState = audioSpectrumState,
                        visible =
                            audioVisualizerEnabled &&
                                audioVisualizerStyle != AudioVisualizerStyle.Spectrum &&
                                !moreVisible &&
                                !artworkSceneTransitionRunning,
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(
                                with(density) { (viewportHeightPx * 0.30f).toDp() }
                                    .coerceAtMost(220.dp)
                            )
                            .clipToBounds(),
                    )
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                val artworkSize = minOf(maxWidth * 0.72f, maxHeight, 320.dp)
                                Box(
                                    modifier = Modifier
                                        .size(artworkSize)
                                        .onGloballyPositioned { coordinates ->
                                            playerArtworkBounds = coordinates.boundsInRoot()
                                        }
                                        .playbackArtworkSwipeGesture(
                                            state = artworkState,
                                            previousKey = previousNavigationArtworkKey,
                                            nextKey = nextNavigationArtworkKey,
                                            onLongPress = ::openFullscreenCarousel,
                                            onPrevious = ::previousFromPlayer,
                                            onNext = ::nextFromPlayer,
                                        )
                                ) {
                                    PlaybackArtworkTransition(
                                        state = artworkState,
                                        animationStyle = artworkStyle,
                                        contentScale = ContentScale.Fit,
                                        cornerRadius = 24.dp,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                alpha = if (
                                                    artworkSceneTransitionRunning || moreArtworkOverlayMounted
                                                ) 0f else 1f
                                            },
                                    )
                                    AlbumArtworkSpectrumOverlay(
                                        spectrum = audioSpectrumFallback,
                                        spectrumState = audioSpectrumState,
                                        visible =
                                            audioVisualizerEnabled &&
                                                audioVisualizerStyle == AudioVisualizerStyle.Spectrum &&
                                                !artworkSceneTransitionRunning &&
                                                !moreArtworkOverlayMounted,
                                        isPlaying = isPlaying,
                                        layer = AudioVisualizerLayer.Foreground,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                scaleX = 1.08f
                                                clip = true
                                                shape = RoundedCornerShape(24.dp)
                                            },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            PlaybackArtworkMetadataTransition(
                                state = artworkState,
                                animationStyle = artworkStyle,
                                modifier = Modifier
                                    .fillMaxWidth(0.82f)
                                    .height(24.dp),
                            ) { metadataKey, isInteractiveCurrent ->
                                // Keep the physical metadata holder alive through the page motion,
                                // but always resolve its current payload. Caching the song by
                                // artwork key leaves the previous title when two tracks share the
                                // same cover identity.
                                val metadataSong = currentSong?.takeIf {
                                    isInteractiveCurrent && it.resolvePlaybackArtworkKey() == metadataKey
                                } ?: queue.songs.firstOrNull {
                                    it.resolvePlaybackArtworkKey() == metadataKey
                                } ?: currentSong?.takeIf {
                                    it.resolvePlaybackArtworkKey() == metadataKey
                                }
                                Text(
                                    text = metadataSong?.displayName.orEmpty(),
                                    color = Color.White.copy(alpha = 0.92f),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            val waveformAlbumAccent = rememberCoverAccentColor(artworkKey)
                            val landscapeRemainingColor = if (waveformColorMode == PlayerProgressPreferences.COLOR_MODE_ALBUM_ART) {
                                waveformAlbumAccent.copy(alpha = 0.90f)
                            } else {
                                Color(waveformRemainingColorInt)
                            }
                            val landscapePlayedColor = if (waveformColorMode == PlayerProgressPreferences.COLOR_MODE_ALBUM_ART) {
                                waveformAlbumAccent.copy(alpha = 0.30f)
                            } else {
                                Color(waveformPlayedColorInt)
                            }
                            ReusablePlayerTimelineProgress(
                                styleValue = progressStyleValue,
                                currentSong = currentSong,
                                currentPositionMs = positionMs,
                                totalDurationMs = durationMs,
                                isPlaying = isPlaying,
                                spectrum = audioSpectrumFallback,
                                spectrumState = audioSpectrumState,
                                climaxEnabled = waveformClimaxEnabled,
                                waveformRemainingColor = landscapeRemainingColor,
                                waveformPlayedColor = landscapePlayedColor,
                                waveformClimaxColor = Color(waveformClimaxColorInt),
                                onSeekStart = {},
                                onSeekStop = { fraction ->
                                    playerController.seekTo((durationMs * fraction).toLong())
                                },
                                modifier = Modifier
                                    .fillMaxWidth(0.86f)
                                    .padding(bottom = 2.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .combinedClickable(
                                    interactionSource = lyricTapInteraction,
                                    indication = null,
                                    onClick = {
                                        if (!artworkSceneTransitionRunning) {
                                            topOverlayVisible = !topOverlayVisible
                                            overlayActivityToken++
                                            if (!topOverlayVisible) moreVisible = false
                                        }
                                    },
                                    onDoubleClick = {
                                        if (!artworkSceneTransitionRunning) {
                                            controlsVisible = !controlsVisible
                                        }
                                    }
                                )
                        ) {
                            ComposeLyricView(
                                song = lyricSong,
                                positionMs = positionMs,
                                isPlaying = isPlaying,
                                displayTranslation = displayTranslation,
                                displayRoma = displayRoma,
                                topPadding = 26.dp,
                                bottomPadding = if (controlsVisible) 78.dp else 20.dp,
                                textColor = Color.White,
                                dimColor = Color.White.copy(alpha = 0.34f),
                                secondaryColor = Color.White.copy(alpha = 0.66f),
                                fontSizeSp = lyricFontSizeSp,
                                textPosition = lyricTextPosition,
                                blurEnabled = blurEnabled,
                                relativeLineProgressEnabled = relativeLineProgressEnabled,
                                highlightAll = highlightAll,
                                karaokeGlowEnabled = AppPreferences.UI.lyricKaraokeGlowEnabled,
                                karaokeLiftEnabled = AppPreferences.UI.lyricKaraokeLiftEnabled,
                                onLineClick = { playerController.seekTo(it) },
                                modifier = Modifier.fillMaxSize()
                            )

                            LandscapeAnimatedTransportControls(
                                visible = controlsVisible,
                                isPlaying = isPlaying,
                                onPrevious = ::previousFromPlayer,
                                onPlayPause = playerController::playPause,
                                onNext = ::nextFromPlayer,
                                onLongPress = { controlsVisible = false },
                                modifier = Modifier.align(Alignment.BottomEnd)
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = topOverlayVisible && !artworkSceneTransitionRunning,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                ) {
                    LandscapeTopOverlay(
                        onBack = onBack,
                        onMore = {
                            overlayActivityToken++
                            moreVisible = true
                        },
                        onMoreBoundsChanged = { moreAnchorBounds = it },
                    )
                }
            }
        }

        if (showFullCoverScene) {
            FullCoverPage(
                currentSong = currentSong,
                queueSongs = queue.songs,
                queueCurrentIndex = queue.currentIndex,
                coverPath = artworkKey,
                title = currentSong?.title.orEmpty(),
                lyricSong = lyricSong,
                lyricPositionMs = positionMs,
                lyricIsPlaying = isPlaying,
                onLyricSeek = { positionMs -> playerController.seekTo(positionMs) },
                onQueueSongClick = { song, index ->
                    playerController.play(song, queue.songs, index)
                },
                onCurrentArtworkLongPress = ::closeFullscreenCarousel,
                onBack = ::closeFullscreenCarousel,
                // The current artwork is hidden here and stays owned by the shared overlay.
                // Every other full-cover element uses one target-only scale/fade layer, so opening
                // and closing are exact reverses without changing Step81 timing or easing values.
                sceneRevealProgress = 1f,
                hideCenterForSceneTransition = artworkSceneTransitionRunning,
                sceneInteractionEnabled = !artworkSceneTransitionRunning && !fullscreenPredictiveBackActive && transitionProgress >= 0.999f,
                renderBackdrop = false,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = targetContentAlpha
                        scaleX = targetContentScale
                        scaleY = targetContentScale
                    },
            )
        }

        if (artworkSceneTransitionRunning && transitionFrame != null) {
            val widthDp = with(density) { transitionFrame.sharedWidthPx.toDp() }
            val heightDp = with(density) { transitionFrame.sharedHeightPx.toDp() }
            val cornerRadius = (24f - 2f * transitionProgress).dp
            PlaybackArtworkTransition(
                state = artworkState,
                animationStyle = artworkStyle,
                contentScale = ContentScale.Fit,
                cornerRadius = cornerRadius,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            transitionFrame.sharedLeftPx.roundToInt(),
                            transitionFrame.sharedTopPx.roundToInt(),
                        )
                    }
                    .width(widthDp)
                    .height(heightDp)
                    .graphicsLayer {
                        alpha = 1f
                    },
            )
        }

        }

        LandscapeAnchoredGlassPopup(
            show = moreVisible && !artworkSceneTransitionRunning,
            anchorBounds = moreAnchorBounds,
            backdrop = morePanelBackdrop,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            sourceArtworkBounds = playerArtworkBounds,
            artwork = artworkState.overlayArtworkBitmap(),
            sourceArtworkRadiusDp = 24f,
            targetArtworkRadiusDp = 12f,
            onMountedChange = { moreArtworkOverlayMounted = it },
            onDismissRequest = {
                moreVisible = false
                overlayActivityToken++
            },
        ) { artworkAlpha, onArtworkBoundsChanged ->
            LyricMoreSheetContent(
                currentSong = currentSong,
                onAddSongToPlaylist = addSongToPlaylist,
                coverPath = artworkKey,
                artworkTransitionState = artworkState,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                blurEnabled = blurEnabled,
                relativeLineProgressEnabled = relativeLineProgressEnabled,
                highlightAllEnabled = highlightAll,
                fontSizeSp = lyricFontSizeSp,
                textPosition = lyricTextPosition,
                onTranslationToggle = {
                    LyriconProviderManager.setDisplayTranslation(!displayTranslation)
                },
                onRomaToggle = {
                    LyriconProviderManager.setDisplayRoma(!displayRoma)
                },
                onBlurEnabledChange = {
                    blurEnabled = it
                    AppPreferences.UI.lyricBlurEnabled = it
                },
                onRelativeLineProgressEnabledChange = {
                    relativeLineProgressEnabled = it
                    AppPreferences.UI.lyricRelativeLineProgressEnabled = it
                },
                onHighlightAllEnabledChange = {
                    highlightAll = it
                    AppPreferences.UI.lyricHighlightAllEnabled = it
                },
                onFontSizeChange = {
                    lyricFontSizeSp = it
                    AppPreferences.UI.lyricFontSizeSp = it
                },
                onTextPositionChange = {
                    lyricTextPosition = it
                    AppPreferences.UI.lyricTextPosition = it.value
                },
                progressStyleValue = progressStyleValue,
                onProgressStyleValueChange = { value ->
                    PlayerProgressPreferences.progressStyleValue = value
                },
                landscapeLockEnabled = landscapeLocked,
                onLandscapeLockEnabledChange = { enabled ->
                    landscapeLocked = enabled
                    AppPreferences.UI.landscapePlayerLocked = enabled
                },
                onModifyAlbumArt = null,
                onSearchLyrico = null,
                onOpenInLyrico = null,
                onAiTimingPreview = null,
                onOpenExternalTimingEditor = null,
                audioVisualizerEnabled = audioVisualizerEnabled,
                onAudioVisualizerEnabledChange = { enabled ->
                    audioVisualizerEnabled = enabled
                    AppPreferences.UI.isAudioVisualizerEnabled = enabled
                },
                audioVisualizerStyleOverride = audioVisualizerStyle,
                onAudioVisualizerStyleChangeOverride = { style ->
                    audioVisualizerStyle = style
                    AppPreferences.UI.audioVisualizerStyle = style.value
                },
                artworkAlpha = artworkAlpha,
                onArtworkBoundsChanged = onArtworkBoundsChanged,
                twoColumnAcrylicLayout = true,
            )
        }
    }

}

@Composable
private fun LandscapeAnchoredGlassPopup(
    show: Boolean,
    anchorBounds: Rect?,
    backdrop: Backdrop,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    sourceArtworkBounds: Rect?,
    artwork: Bitmap?,
    sourceArtworkRadiusDp: Float,
    targetArtworkRadiusDp: Float,
    onMountedChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    content: @Composable (artworkAlpha: Float, onArtworkBoundsChanged: (Rect) -> Unit) -> Unit,
) {
    val anchor = anchorBounds ?: return
    val density = LocalDensity.current
    var mounted by remember { mutableStateOf(show) }
    var targetArtworkBounds by remember { mutableStateOf<Rect?>(null) }
    var entranceComplete by remember { mutableStateOf(show) }
    var headerArtworkVisible by remember { mutableStateOf(show) }
    var predictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveExitStart by remember { mutableStateOf<Float?>(null) }
    val progressX = remember { Animatable(if (show) 1f else 0f) }
    val progressY = remember { Animatable(if (show) 1f else 0f) }
    val popupScope = rememberCoroutineScope()
    val latestMountedChange by rememberUpdatedState(onMountedChange)

    LaunchedEffect(show, anchor, viewportWidthPx, viewportHeightPx) {
        if (show) {
            mounted = true
            latestMountedChange(true)
            predictiveBackActive = false
            predictiveBackProgress = 0f
            predictiveExitStart = null
            targetArtworkBounds = null
            entranceComplete = false
            headerArtworkVisible = false
            if (progressX.value <= 0.001f) progressX.snapTo(0f)
            if (progressY.value <= 0.001f) progressY.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch {
                    progressX.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.56f,
                            stiffness = 235f,
                            visibilityThreshold = 0.0005f,
                        ),
                    )
                }
                launch {
                    progressY.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.64f,
                            stiffness = 185f,
                            visibilityThreshold = 0.0005f,
                        ),
                    )
                }
            }
            if (show && mounted) {
                // Handoff only after both springs have actually settled. Keep one frame where the
                // header and the floating actor draw the exact same bitmap so an overshoot/owner
                // boundary can never expose the acrylic placeholder underneath.
                headerArtworkVisible = true
                withFrameNanos { }
                entranceComplete = true
            }
        } else if (mounted) {
            predictiveExitStart?.let { start ->
                val retained = start.coerceIn(0f, 1f)
                progressX.snapTo(retained)
                progressY.snapTo(retained)
                predictiveBackActive = false
                predictiveBackProgress = 0f
                predictiveExitStart = null
            }
            // Reverse ownership in the opposite order: first bring the floating actor back, then
            // retire the header one frame later. This mirrors the entrance handoff and prevents a
            // white flash at the beginning of the return spring.
            entranceComplete = false
            withFrameNanos { }
            headerArtworkVisible = false
            kotlinx.coroutines.coroutineScope {
                launch {
                    progressX.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = 0.56f,
                            stiffness = 235f,
                            visibilityThreshold = 0.012f,
                        ),
                    )
                }
                launch {
                    progressY.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = 0.64f,
                            stiffness = 185f,
                            visibilityThreshold = 0.012f,
                        ),
                    )
                }
            }
            // Match the standard Player More ownership handoff: restore the source artwork one
            // frame before removing the floating actor so there is never a blank source slot.
            latestMountedChange(false)
            withFrameNanos { }
            mounted = false
            targetArtworkBounds = null
        }
    }

    if (!mounted) return
    FullCoverPredictiveBackHandler(
        enabled = show,
        onProgress = { value ->
            predictiveBackActive = true
            predictiveBackProgress = value.coerceIn(0f, 1f)
        },
        onCancelled = {
            val released = (1f - predictiveBackProgress).coerceIn(0f, 1f)
            popupScope.launch {
                progressX.snapTo(released)
                progressY.snapTo(released)
                predictiveBackActive = false
                predictiveBackProgress = 0f
                kotlinx.coroutines.coroutineScope {
                    launch {
                        progressX.animateTo(
                            1f,
                            spring(dampingRatio = 0.72f, stiffness = 310f),
                        )
                    }
                    launch {
                        progressY.animateTo(
                            1f,
                            spring(dampingRatio = 0.76f, stiffness = 270f),
                        )
                    }
                }
            }
        },
        onCompleted = { wasPredictive ->
            if (wasPredictive) {
                predictiveExitStart = (1f - predictiveBackProgress).coerceIn(0f, 1f)
            }
            onDismissRequest()
        },
    )

    val halfWidthPx = viewportWidthPx * 0.5f
    val useLeftHalf = anchor.center.x < halfWidthPx
    val sideStartPx = if (useLeftHalf) 0f else halfWidthPx
    val sideEndPx = sideStartPx + halfWidthPx
    val horizontalInsetPx = with(density) { 6.dp.toPx() }
    val targetLeftPx = sideStartPx + horizontalInsetPx
    val targetRightPx = sideEndPx - horizontalInsetPx
    val targetTopPx = viewportHeightPx * 0.13f
    val targetBottomPx = viewportHeightPx * 0.87f
    val targetWidthPx = (targetRightPx - targetLeftPx).coerceAtLeast(1f)
    val targetHeightPx = (targetBottomPx - targetTopPx).coerceAtLeast(1f)
    val targetWidthDp = with(density) { targetWidthPx.toDp() }
    val targetHeightDp = with(density) { targetHeightPx.toDp() }
    val halfWidthDp = with(density) { halfWidthPx.toDp() }

    // Keep the target rectangle fixed and animate only a RenderNode transform. This is the
    // original soft anchored morph: no per-frame layout measurement, no material API participating
    // in geometry, and X/Y retain their independent spring inertia.
    val predictiveProgress = if (predictiveBackActive) {
        (1f - predictiveBackProgress).coerceIn(0f, 1f)
    } else {
        null
    }
    val rawProgressX = predictiveProgress ?: progressX.value
    val rawProgressY = predictiveProgress ?: progressY.value
    val geometryProgressX = rawProgressX.coerceIn(0f, 1f)
    val geometryProgressY = rawProgressY.coerceIn(0f, 1f)
    val startScaleX = (anchor.width / targetWidthPx).coerceIn(0.01f, 1f)
    val startScaleY = (anchor.height / targetHeightPx).coerceIn(0.01f, 1f)
    val morphScaleX = lerpLandscapePopup(startScaleX, 1f, geometryProgressX)
    val morphScaleY = lerpLandscapePopup(startScaleY, 1f, geometryProgressY)
    val startTranslationX = if (useLeftHalf) {
        anchor.left - targetLeftPx
    } else {
        anchor.right - targetRightPx
    }
    val morphTranslationX = lerpLandscapePopup(startTranslationX, 0f, geometryProgressX)
    val morphTranslationY = lerpLandscapePopup(
        anchor.top - targetTopPx,
        0f,
        geometryProgressY,
    )
    val elasticOvershootX = (rawProgressX - 1f).coerceIn(0f, 0.12f)
    val elasticOvershootY = (rawProgressY - 1f).coerceIn(0f, 0.12f)
    val elasticScaleX = 1f + elasticOvershootX * 0.42f
    val elasticScaleY = 1f + elasticOvershootY * 0.34f
    val contentProgress = minOf(geometryProgressX, geometryProgressY)
    val contentAlpha = ((contentProgress - 0.24f) / 0.34f).coerceIn(0f, 1f)
    val scrimAlpha = (contentProgress * 0.14f).coerceIn(0f, 0.14f)
    // The spring may spend a few frames numerically settling at the anchor. Fade the glass shell
    // out before that tail so the More icon never appears to have a stuck bubble sitting on it.
    val shellAlpha = if (show) {
        ((contentProgress - 0.035f) / 0.12f).coerceIn(0f, 1f)
    } else {
        // Keep the shell visible for almost the whole reverse morph, then let the relaxed
        // visibility threshold remove the overlay before the spring's microscopic tail can leave
        // a bubble parked over the More icon.
        ((contentProgress - 0.018f) / 0.08f).coerceIn(0f, 1f)
    }
    val panelShape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    val activeArtwork = artwork?.takeUnless { it.isRecycled }
    val sourceArtwork = sourceArtworkBounds?.takeIf { it.isLandscapeSharedArtworkUsable }
    val targetArtwork = targetArtworkBounds?.takeIf { it.isLandscapeSharedArtworkUsable }
    // Keep the same physical bitmap actor mounted after it reaches the header. It simply rests at
    // the target rect until dismissal and then follows the reverse geometry. Avoiding the previous
    // floating->header owner swap removes the visible artwork "kick" after the glass panel settles.
    val floatingArtworkActive = activeArtwork != null && sourceArtwork != null
    val headerArtworkAlpha = if (floatingArtworkActive) 0f else 1f

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .then(
                    if (show) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onDismissRequest,
                        )
                    } else {
                        Modifier
                    }
                ),
        )

        val glassModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { panelShape },
                effects = {
                    vibrancy(1.04f)
                    blur(44.dp.toPx())
                },
                highlight = {
                    Highlight.Default.copy(alpha = 0.24f)
                },
                shadow = {
                    Shadow(
                        radius = 14.dp,
                        color = Color.Black.copy(alpha = 0.24f),
                    )
                },
                onDrawSurface = {
                    drawRect(Color.Black.copy(alpha = 0.10f))
                },
            )
        } else {
            Modifier.background(
                color = Color.Black.copy(alpha = 0.78f),
                shape = panelShape,
            )
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(sideStartPx.roundToInt(), 0)
                }
                .width(halfWidthDp)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (targetLeftPx - sideStartPx).roundToInt(),
                            targetTopPx.roundToInt(),
                        )
                    }
                    .width(targetWidthDp)
                    .height(targetHeightDp)
                    .graphicsLayer {
                        translationX = morphTranslationX
                        translationY = morphTranslationY
                        scaleX = morphScaleX * elasticScaleX
                        scaleY = morphScaleY * elasticScaleY
                        alpha = shellAlpha
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                            pivotFractionX = if (useLeftHalf) 0f else 1f,
                            pivotFractionY = 0f,
                        )
                    }
                    .clip(panelShape)
                    .then(glassModifier),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = contentAlpha }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    content(headerArtworkAlpha) { bounds ->
                        if (bounds.isLandscapeSharedArtworkUsable) {
                            targetArtworkBounds = bounds
                        }
                    }
                }
            }
        }

        if (activeArtwork != null && sourceArtwork != null && floatingArtworkActive) {
            Image(
                bitmap = activeArtwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .offset {
                        val bounds = interpolateLandscapeSharedArtworkBounds(
                            source = sourceArtwork,
                            target = targetArtwork,
                            progress = contentProgress,
                        )
                        IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt())
                    }
                    .layout { measurable, _ ->
                        val bounds = interpolateLandscapeSharedArtworkBounds(
                            source = sourceArtwork,
                            target = targetArtwork,
                            progress = contentProgress,
                        )
                        val width = bounds.width.roundToInt().coerceAtLeast(1)
                        val height = bounds.height.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        val radiusDp = sourceArtworkRadiusDp +
                            (targetArtworkRadiusDp - sourceArtworkRadiusDp) * contentProgress
                        shape = RoundedCornerShape(radiusDp.dp)
                        clip = true
                    }
                    .zIndex(50f),
            )
        }
    }
}

private fun lerpLandscapePopup(start: Float, end: Float, amount: Float): Float =
    start + (end - start) * amount.coerceIn(0f, 1f)

private fun interpolateLandscapeSharedArtworkBounds(
    source: Rect,
    target: Rect?,
    progress: Float,
): Rect {
    val end = target ?: source
    val t = progress.coerceIn(0f, 1f)
    return Rect(
        left = source.left + (end.left - source.left) * t,
        top = source.top + (end.top - source.top) * t,
        right = source.right + (end.right - source.right) * t,
        bottom = source.bottom + (end.bottom - source.bottom) * t,
    )
}

private val Rect.isLandscapeSharedArtworkUsable: Boolean
    get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        width > 1f && height > 1f

@Composable
private fun LandscapeAnimatedTransportControls(
    visible: Boolean,
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
        modifier = modifier
    ) {
        LandscapeTransportControls(
            isPlaying = isPlaying,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onLongPress = onLongPress
        )
    }
}

@Composable
private fun LandscapeTransportControls(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LandscapeControlButton(R.drawable.ic_rewind_fill, onPrevious, onLongPress, 24)
        LandscapeControlButton(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            onPlayPause,
            onLongPress,
            29
        )
        LandscapeControlButton(R.drawable.ic_speed_fill, onNext, onLongPress, 24)
    }
}

@Composable
private fun LandscapeControlButton(
    iconRes: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    iconSize: Int
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(46.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}

@Composable
private fun LandscapeTopOverlay(
    onBack: () -> Unit,
    onMore: () -> Unit,
    onMoreBoundsChanged: (Rect) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 18.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(46.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Back,
                contentDescription = stringResource(R.string.landscape_player_back),
                tint = Color.White,
                modifier = Modifier.size(25.dp)
            )
        }
        IconButton(
            onClick = onMore,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(46.dp)
                .onGloballyPositioned { coordinates ->
                    onMoreBoundsChanged(coordinates.boundsInRoot())
                }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.landscape_player_more),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Int.isPortraitAngle(): Boolean = this in 0..28 || this in 332..359
