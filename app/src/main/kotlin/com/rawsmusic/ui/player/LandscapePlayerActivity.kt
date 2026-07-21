@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.rawsmusic.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
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
import com.rawsmusic.core.ui.widget.player.ImmersiveSecondProgressBar
import com.rawsmusic.core.ui.widget.player.ImmersiveWaveformColors
import com.rawsmusic.core.ui.widget.player.LyricMoreOverlayDialog
import com.rawsmusic.core.ui.widget.player.LyricTextPosition
import com.rawsmusic.core.ui.widget.player.StandardPlayerBackdrop
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.scanner.LyricReader
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

class LandscapePlayerActivity : ComponentActivity() {

    private var portraitExitListener: OrientationEventListener? = null
    private var allowPortraitExit = false
    private var finishingForPortraitRotation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        configureFullscreenWindow()

        val playerController = PlayerService.currentRuntimeController()
            ?: PlayerController.getInstanceOrNull()
            ?: PlayerService.obtainRuntimeController(
                this,
                "landscape_player_activity",
                ensureService = true
            )

        setContent {
            RawSMusicTheme {
                val navEventOwner = rememberNavigationEventDispatcherOwner(enabled = true)
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navEventOwner) {
                    top.yukonga.miuix.kmp.basic.Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MiuixTheme.colorScheme.background,
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                    ) {
                        LandscapePlayerScreen(
                            playerController = playerController,
                            onBack = ::finish
                        )
                    }
                }
            }
        }

        portraitExitListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (!allowPortraitExit || orientation == ORIENTATION_UNKNOWN) return
                if (isAutoRotateEnabled(this@LandscapePlayerActivity) && orientation.isPortraitAngle()) {
                    if (finishingForPortraitRotation) return
                    finishingForPortraitRotation = true
                    finish()
                }
            }
        }
        window.decorView.postDelayed({ allowPortraitExit = true }, 900L)
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
        fun createIntent(context: Context): Intent =
            Intent(context, LandscapePlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun isAutoRotateEnabled(context: Context): Boolean = runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            ) == 1
        }.getOrDefault(false)
    }
}

@Composable
private fun LandscapePlayerScreen(
    playerController: PlayerController,
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
    var controlsVisible by remember { mutableStateOf(true) }
    var topOverlayVisible by remember { mutableStateOf(false) }
    var moreVisible by remember { mutableStateOf(false) }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(backgroundAlpha)
                .combinedClickable(
                    interactionSource = backgroundTapInteraction,
                    indication = null,
                    onClick = {
                        topOverlayVisible = !topOverlayVisible
                        overlayActivityToken++
                        if (!topOverlayVisible) moreVisible = false
                    }
                )
        ) {
            StandardPlayerBackdrop(
                coverPath = artworkKey,
                accent = Color.Transparent,
                artworkTransitionState = artworkState,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 18.dp)
        ) {
            val artworkSize = minOf(maxWidth * 0.32f, maxHeight * 0.58f, 320.dp)
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PlaybackArtworkTransition(
                        state = artworkState,
                        animationStyle = artworkStyle,
                        contentScale = ContentScale.Crop,
                        cornerRadius = 24.dp,
                        modifier = Modifier
                            .size(artworkSize)
                            .playbackArtworkSwipeGesture(
                                state = artworkState,
                                previousKey = playerController.previewPreviousSong()
                                    .resolvePlaybackArtworkKey(),
                                nextKey = playerController.previewNextSong()
                                    .resolvePlaybackArtworkKey(),
                                onPrevious = {
                                    previousFromPlayer()
                                },
                                onNext = { nextFromPlayer() }
                            )
                    )
                    Spacer(Modifier.height(12.dp))
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
                    ImmersiveSecondProgressBar(
                        currentSong = currentSong,
                        currentPositionMs = positionMs,
                        totalDurationMs = durationMs,
                        isPlaying = isPlaying,
                        colors = ImmersiveWaveformColors(
                            played = Color.White.copy(alpha = 0.34f),
                            remaining = Color.White.copy(alpha = 0.90f),
                            climaxPlayed = Color.White.copy(alpha = 0.34f),
                            climaxRemaining = Color.White,
                            needle = Color.White,
                            time = Color.White.copy(alpha = 0.72f)
                        ),
                        onSeekStart = {},
                        onSeekStop = { fraction ->
                            playerController.seekTo((durationMs * fraction).toLong())
                        },
                        modifier = Modifier.fillMaxWidth(0.86f)
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
                                topOverlayVisible = !topOverlayVisible
                                overlayActivityToken++
                                if (!topOverlayVisible) moreVisible = false
                            },
                            onDoubleClick = { controlsVisible = !controlsVisible }
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
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = topOverlayVisible,
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
            show = moreVisible,
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
            }
        )
    }
}

@Composable
private fun LandscapeAnimatedTransportControls(
    visible: Boolean,
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
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
            onNext = onNext
        )
    }
}

@Composable
private fun LandscapeTransportControls(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LandscapeControlButton(R.drawable.ic_rewind_fill, onPrevious, 24)
        LandscapeControlButton(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            onPlayPause,
            29
        )
        LandscapeControlButton(R.drawable.ic_speed_fill, onNext, 24)
    }
}

@Composable
private fun LandscapeControlButton(iconRes: Int, onClick: () -> Unit, iconSize: Int) {
    IconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
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

private fun Int.isPortraitAngle(): Boolean = this in 0..28 || this in 332..359
