package com.rawsmusic.core.ui.widget.player

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.flow.rememberCurrentRawFlowMode
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.bitmaps.AlbumArtTiers
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
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
    onModalVisibleChange: (Boolean) -> Unit = {},
    onAudioQuality: () -> Unit,
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onOpenLyric: () -> Unit,
    onOpenQueue: () -> Unit,
    onClosePlayer: () -> Unit = {},
    onCoverSwipeUpStart: () -> Unit = {},
    onCoverSwipeUpProgress: (Float) -> Unit = {},
    onCoverSwipeUpEnd: (Boolean, Float) -> Unit = { _, _ -> },
    onCoverSwipeDownStart: () -> Unit = {},
    onCoverSwipeDownProgress: (Float) -> Unit = {},
    onCoverSwipeDownEnd: (Boolean, Float) -> Unit = { _, _ -> },
    showAlbumArt: Boolean = true,
    renderBackdrop: Boolean = true,
    contentAlpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val baseColor = rememberCoverAccentColor(coverPath)
    var showAudioChain by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var progressStyle by remember { mutableStateOf(ImmersiveProgressStyle.from(AppPreferences.UI.immersiveProgressStyle)) }
    var climaxEnabled by remember { mutableStateOf(AppPreferences.UI.immersiveClimaxEnabled) }
    var waveformDebugPanel by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformDebugPanel) }
    var waveformRemainingColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformRemainingColor) }
    var waveformPlayedColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformPlayedColor) }
    var waveformClimaxColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformClimaxColor) }

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

    fun openUnifiedMoreSheet() {
        showMoreSheet = true
        onModalVisibleChange(true)
    }

    fun closeUnifiedMoreSheet() {
        showMoreSheet = false
        onModalVisibleChange(false)
    }

    LaunchedEffect(coverPath) {
        if (!coverPath.isNullOrBlank()) {
            BitmapProvider.warmPlaybackArt(coverPath)
            BitmapProvider.warmFullCoverArt(coverPath)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (renderBackdrop) {
            StandardPlayerBackdrop(coverPath = coverPath, accent = baseColor)
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
                    title = currentSong?.displayName.orEmpty(),
                    onSwipeUp = onOpenLyric,
                    onSwipeDown = onClosePlayer,
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    onSwipeDownStart = onCoverSwipeDownStart,
                    onSwipeDownProgress = onCoverSwipeDownProgress,
                    onSwipeDownEnd = onCoverSwipeDownEnd,
                    showArt = showAlbumArt,
                    modifier = Modifier
                        .weight(0.46f)
                        .aspectRatio(1f)
                )
                Spacer(Modifier.width(28.dp))
                StandardPlayerBody(
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
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
                    onOpenQueue = onOpenQueue,
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
                    title = currentSong?.displayName.orEmpty(),
                    onSwipeUp = onOpenLyric,
                    onSwipeDown = onClosePlayer,
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    onSwipeDownStart = onCoverSwipeDownStart,
                    onSwipeDownProgress = onCoverSwipeDownProgress,
                    onSwipeDownEnd = onCoverSwipeDownEnd,
                    showArt = showAlbumArt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.02f)
                )
                Spacer(Modifier.height(20.dp))
                StandardPlayerBody(
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
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
                    onOpenQueue = onOpenQueue,
                    onSwipeUpStart = onCoverSwipeUpStart,
                    onSwipeUpProgress = onCoverSwipeUpProgress,
                    onSwipeUpEnd = onCoverSwipeUpEnd,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        BackHandler(enabled = showMoreSheet) {
            closeUnifiedMoreSheet()
        }
        AnimatedVisibility(
            visible = showMoreSheet,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
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
                onDismiss = ::closeUnifiedMoreSheet
            )
        }
    }
}

@Composable
internal fun StandardPlayerBackdrop(
    coverPath: String?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    // 普通播放页和沉浸播放页统一使用 RawFlowBackground，不再维护另一套模糊封面背景。
    RawFlowBackground(
        mode = rememberCurrentRawFlowMode(),
        sourceCoverKey = coverPath,
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun AlbumArtCard(
    coverPath: String?,
    title: String,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    onSwipeDownStart: () -> Unit,
    onSwipeDownProgress: (Float) -> Unit,
    onSwipeDownEnd: (Boolean, Float) -> Unit,
    showArt: Boolean,
    modifier: Modifier = Modifier
) {
    var dragY by remember { mutableStateOf(0f) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val hasCover = !coverPath.isNullOrBlank()
    Box(
        modifier = modifier
            .onSizeChanged { cardSize = it }
            .clip(RoundedCornerShape(28.dp))
            .then(
                if (hasCover) {
                    Modifier
                } else {
                    Modifier.background(Color.White.copy(alpha = 0.11f))
                }
            )
            .pointerInput(Unit) {
                var direction = 0
                detectDragGestures(
                    onDragStart = {
                        dragY = 0f
                        direction = 0
                    },
                    onDragEnd = {
                        val commit = kotlin.math.abs(dragY) / cardSize.height.toFloat().coerceAtLeast(1f) > 0.3f
                        when {
                            direction < 0 -> {
                                onSwipeUpEnd(commit, 0f)
                            }
                            direction > 0 -> {
                                onSwipeDownEnd(commit, 0f)
                            }
                        }
                        dragY = 0f
                        direction = 0
                    },
                    onDragCancel = {
                        if (direction < 0) onSwipeUpEnd(false, 0f)
                        if (direction > 0) onSwipeDownEnd(false, 0f)
                        dragY = 0f
                        direction = 0
                    },
                    onDrag = { change, amount ->
                        if (direction == 0 && kotlin.math.abs(amount.y) > kotlin.math.abs(amount.x) * 1.2f) {
                            direction = if (amount.y < 0f) -1 else 1
                            if (direction < 0) onSwipeUpStart() else onSwipeDownStart()
                        }
                        if (direction != 0) {
                            dragY += amount.y
                            val relevant = if (direction < 0) -dragY else dragY
                            val ratio = (relevant / cardSize.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            if (direction < 0) onSwipeUpProgress(ratio) else onSwipeDownProgress(ratio)
                            change.consume()
                        }
                    }
                )
            }
    ) {
        if (hasCover) {
            CrossfadeAlbumArt(
                key = coverPath,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (showArt) 1f else 0f },
                priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                lowResSize = AlbumArtTiers.HI_RES_SIDE,
                hiResSize = AlbumArtTiers.FULL_RES_SIDE,
                holdPreviousOnKeyChange = true,
                fadeMillis = 0,
                freezeBitmapUpdates = dragY != 0f,
                skipLowResPlaceholder = false,
                forceTargetHighRequest = true
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
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
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
    onOpenQueue: () -> Unit,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillTitleInfo(
                currentSong = currentSong,
                onSwipeUpStart = onSwipeUpStart,
                onSwipeUpProgress = onSwipeUpProgress,
                onSwipeUpEnd = onSwipeUpEnd,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            IconOnlyButton(iconRes = moreIconRes, onClick = onMore)
        }
        Spacer(Modifier.weight(1f))
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
        Spacer(Modifier.height(14.dp))
        StandardTransportButtons(
            isPlaying = isPlaying,
            previousIconRes = previousIconRes,
            playIconRes = playIconRes,
            pauseIconRes = pauseIconRes,
            nextIconRes = nextIconRes,
            playModeIconRes = playModeIconRes,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPlayMode = onPlayMode,
            onQueuePlaceholder = onOpenQueue
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QualityPill(
                song = currentSong,
                text = audioInfoText,
                onClick = onAudioQuality,
                onLongClick = onAudioQualityLongPress
            )
        }
        Spacer(Modifier.height(16.dp))
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
        PlayerQueuePlaceholder(size = 40.dp, tint = tone.tertiary, onClick = onQueuePlaceholder)
    }
}

@Composable
private fun PlayerQueuePlaceholder(
    size: androidx.compose.ui.unit.Dp,
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
        Text("≡", color = tint, fontSize = 31.sp, fontWeight = FontWeight.SemiBold)
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
private fun PillTitleInfo(
    currentSong: AudioFile?,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragY by remember { mutableStateOf(0f) }
    var titleSize by remember { mutableStateOf(IntSize.Zero) }
    val tone = rememberStandardPlayerTone()
    ComposePlayerTitleInfo(
        title = currentSong?.displayName ?: stringResource(R.string.player_no_song),
        artist = currentSong?.artist?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.player_unknown_artist),
        album = currentSong?.album?.takeIf { it.isNotBlank() }.orEmpty(),
        titleColor = tone.primary,
        artistColor = tone.secondary,
        albumColor = tone.tertiary,
        modifier = modifier
            .onSizeChanged { titleSize = it }
            .pointerInput(Unit) {
                var draggingUp = false
                detectDragGestures(
                    onDragStart = {
                        dragY = 0f
                        draggingUp = false
                    },
                    onDragEnd = {
                        if (draggingUp) {
                            val ratio = (-dragY / titleSize.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            onSwipeUpEnd(ratio > 0.30f, 0f)
                        }
                        dragY = 0f
                        draggingUp = false
                    },
                    onDragCancel = {
                        if (draggingUp) onSwipeUpEnd(false, 0f)
                        dragY = 0f
                        draggingUp = false
                    },
                    onDrag = { change, amount ->
                        if (!draggingUp && amount.y < 0f && kotlin.math.abs(amount.y) > kotlin.math.abs(amount.x) * 1.2f) {
                            draggingUp = true
                            onSwipeUpStart()
                        }
                        if (draggingUp) {
                            dragY += amount.y
                            val ratio = (-dragY / titleSize.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            onSwipeUpProgress(ratio)
                            change.consume()
                        }
                    }
                )
            }
    )
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
            .background(rememberStandardPlayerTone().chipBackground)
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
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.ifBlank { audioChainText(song) },
            color = rememberStandardPlayerTone().chipText,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
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

