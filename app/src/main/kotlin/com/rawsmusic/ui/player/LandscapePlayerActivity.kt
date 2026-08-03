@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.rawsmusic.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.core.ui.theme.RawSMusicTheme
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransition
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkAnimationStyle
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.playbackArtworkSwipeGesture
import com.rawsmusic.core.ui.widget.bitmaps.rememberPlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.player.ComposeLyricView
import com.rawsmusic.core.ui.widget.player.FullCoverPage
import com.rawsmusic.core.ui.widget.player.FullCoverPredictiveBackHandler
import com.rawsmusic.core.ui.widget.player.resolveFullCoverPredictiveProgress
import com.rawsmusic.core.ui.widget.player.resolveLandscapeFullCoverTransitionFrame
import com.rawsmusic.core.ui.widget.player.resolveLandscapeLaunchSourceBounds
import com.rawsmusic.core.ui.widget.player.ReusablePlayerTimelineProgress
import com.rawsmusic.core.ui.widget.player.LyricMoreOverlayDialog
import com.rawsmusic.core.ui.widget.player.LyricTextPosition
import com.rawsmusic.core.ui.widget.player.StandardPlayerBackdrop
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.scanner.LyricReader
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

        setContent {
            RawSMusicTheme {
                top.yukonga.miuix.kmp.basic.Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MiuixTheme.colorScheme.background,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                ) {
                    LandscapePlayerScreen(
                        playerController = playerController,
                        directFullscreenLaunch = fullscreenLaunchRequest,
                        onBack = ::finish
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
    }

    override fun onPause() {
        portraitExitListener?.disable()
        super.onPause()
    }

    override fun onDestroy() {
        portraitExitListener?.disable()
        portraitExitListener = null
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
    directFullscreenLaunch: FullscreenCarouselLaunchRequest?,
    onBack: () -> Unit
) {
    val currentSong by playerController.currentSong.collectAsState()
    val playState by playerController.playState.collectAsState()
    val positionMs by playerController.position.collectAsState()
    val durationMs by playerController.duration.collectAsState()
    val queue by playerController.queue.collectAsState()
    val isPlaying = playState == PlayState.PLAYING
    val artworkKey = currentSong.resolvePlaybackArtworkKey()
    val artworkState = rememberPlaybackArtworkTransitionState(
        currentKey = artworkKey,
        queueCurrentIndex = queue.currentIndex,
        queueSize = queue.songs.size
    )
    val artworkStyle = remember {
        PlayerArtworkAnimationStyle.from(AppPreferences.UI.playerArtworkAnimationStyle)
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
    var landscapeLocked by remember { mutableStateOf(AppPreferences.UI.landscapePlayerLocked) }
    val activity = LocalContext.current.findActivity()
    var overlayActivityToken by remember { mutableIntStateOf(0) }
    var displayTranslation by remember {
        mutableStateOf(AppPreferences.Lyricon.displayTranslation)
    }
    var displayRoma by remember { mutableStateOf(AppPreferences.Lyricon.displayRoma) }
    var blurEnabled by remember { mutableStateOf(AppPreferences.UI.lyricBlurEnabled) }
    var highlightAll by remember {
        mutableStateOf(AppPreferences.UI.lyricHighlightAllEnabled)
    }
    var lyricFontSizeSp by remember { mutableStateOf(AppPreferences.UI.lyricFontSizeSp) }
    var lyricTextPosition by remember {
        mutableStateOf(LyricTextPosition.from(AppPreferences.UI.lyricTextPosition))
    }
    var progressStyleValue by remember {
        mutableIntStateOf(AppPreferences.UI.immersiveProgressStyle)
    }
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
        enabled = fullscreenSceneActive,
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
        enabled = !fullscreenSceneActive && landscapeLocked && !moreVisible,
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
        val selectedSong = if (gestureTargetKey != null) gestureCommand() else command()
        val selectedKey = selectedSong.resolvePlaybackArtworkKey()
        if (selectedSong != null && !selectedKey.isNullOrBlank()) {
            artworkState.prepare(
                direction = direction,
                expectedKey = selectedKey,
                expectedQueueIndex = songIndex(selectedSong)
            )
        } else {
            artworkState.expectConfirmedNavigation(direction)
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
            gestureCommand = playerController::next
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
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )
        }

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
                                PlaybackArtworkTransition(
                                    state = artworkState,
                                    animationStyle = artworkStyle,
                                    contentScale = ContentScale.Crop,
                                    cornerRadius = 24.dp,
                                    modifier = Modifier
                                        .size(artworkSize)
                                        .onGloballyPositioned { coordinates ->
                                            playerArtworkBounds = coordinates.boundsInRoot()
                                        }
                                        .graphicsLayer {
                                            alpha = if (artworkSceneTransitionRunning) 0f else 1f
                                        }
                                        .playbackArtworkSwipeGesture(
                                            state = artworkState,
                                            previousKey = playerController.previewPreviousSong()
                                                .resolvePlaybackArtworkKey(),
                                            nextKey = playerController.previewNextSong()
                                                .resolvePlaybackArtworkKey(),
                                            onLongPress = ::openFullscreenCarousel,
                                            onPrevious = ::previousFromPlayer,
                                            onNext = ::nextFromPlayer,
                                        )
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = currentSong?.displayName.orEmpty(),
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(0.82f)
                            )
                            Spacer(Modifier.height(4.dp))
                            ReusablePlayerTimelineProgress(
                                styleValue = progressStyleValue,
                                currentSong = currentSong,
                                currentPositionMs = positionMs,
                                totalDurationMs = durationMs,
                                isPlaying = isPlaying,
                                climaxEnabled = AppPreferences.UI.immersiveClimaxEnabled,
                                waveformDebugPanel = AppPreferences.UI.immersiveWaveformDebugPanel,
                                waveformRemainingColor = Color(AppPreferences.UI.immersiveWaveformRemainingColor),
                                waveformPlayedColor = Color(AppPreferences.UI.immersiveWaveformPlayedColor),
                                waveformClimaxColor = Color(AppPreferences.UI.immersiveWaveformClimaxColor),
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
                        }
                    )
                }

                LyricMoreOverlayDialog(
                    show = moreVisible && !artworkSceneTransitionRunning,
                    onDismissRequest = {
                        moreVisible = false
                        overlayActivityToken++
                    },
                    currentSong = currentSong,
                    coverPath = artworkKey,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    blurEnabled = blurEnabled,
                    highlightAllEnabled = highlightAll,
                    fontSizeSp = lyricFontSizeSp,
                    textPosition = lyricTextPosition,
                    onTranslationToggle = {
                        displayTranslation = !displayTranslation
                        AppPreferences.Lyricon.displayTranslation = displayTranslation
                    },
                    onRomaToggle = {
                        displayRoma = !displayRoma
                        AppPreferences.Lyricon.displayRoma = displayRoma
                    },
                    onBlurEnabledChange = {
                        blurEnabled = it
                        AppPreferences.UI.lyricBlurEnabled = it
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
                        progressStyleValue = value.coerceIn(0, 2)
                        AppPreferences.UI.immersiveProgressStyle = progressStyleValue
                    },
                    landscapeLockEnabled = landscapeLocked,
                    onLandscapeLockEnabledChange = { enabled ->
                        landscapeLocked = enabled
                        AppPreferences.UI.landscapePlayerLocked = enabled
                    }
                )
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
                contentScale = ContentScale.Crop,
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

}

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
    onMore: () -> Unit
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
