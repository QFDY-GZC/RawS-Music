package com.rawsmusic.core.ui.widget.player

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
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                    onMore = onMore,
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
                    onMore = onMore,
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
    }
}

@Composable
internal fun StandardPlayerBackdrop(
    coverPath: String?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(accent)
    ) {
        if (!coverPath.isNullOrBlank()) {
            BitmapImage(
                key = coverPath,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(34.dp),
                contentScale = ContentScale.Crop,
                targetWidth = 480,
                targetHeight = 480
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.62f),
                        0.34f to accent.copy(alpha = 0.72f),
                        0.72f to Color(0xE6161320),
                        1f to Color.Black.copy(alpha = 0.94f)
                    )
                )
        )
    }
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
                    .graphicsLayer { alpha = if (showArt) 1f else 0f }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.player_no_song),
                    color = Color.White.copy(alpha = 0.56f),
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
        ComposePlayerControls(
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            textColor = Color.White.copy(alpha = 0.86f),
            iconColor = Color.White,
            playIconTint = Color.White,
            previousIconRes = previousIconRes,
            playIconRes = playIconRes,
            pauseIconRes = pauseIconRes,
            nextIconRes = nextIconRes,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
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
private fun PillTitleInfo(
    currentSong: AudioFile?,
    onSwipeUpStart: () -> Unit,
    onSwipeUpProgress: (Float) -> Unit,
    onSwipeUpEnd: (Boolean, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragY by remember { mutableStateOf(0f) }
    var titleSize by remember { mutableStateOf(IntSize.Zero) }
    ComposePlayerTitleInfo(
        title = currentSong?.displayName ?: stringResource(R.string.player_no_song),
        artist = currentSong?.artist?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.player_unknown_artist),
        album = currentSong?.album?.takeIf { it.isNotBlank() }.orEmpty(),
        titleColor = Color.White,
        artistColor = Color.White.copy(alpha = 0.86f),
        albumColor = Color.White.copy(alpha = 0.62f),
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
        colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.86f)),
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
            .background(Color.Black.copy(alpha = 0.28f))
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
            color = Color.White.copy(alpha = 0.85f),
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
            .background(Color.Black.copy(alpha = 0.30f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(audioChainText(song), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            text = listOfNotNull(
                song?.format?.takeIf { it.isNotBlank() },
                song?.channelCount?.takeIf { it > 0 }?.let { "${it}ch" },
                song?.bitRate?.takeIf { it > 0 }?.let {
                    com.rawsmusic.core.common.utils.BitrateNormalizer.formatKbps(it, song.duration, song.fileSize)
                }
            ).joinToString("  "),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 12.sp
        )
    }
}

@Composable
internal fun rememberCoverAccentColor(coverPath: String?): Color {
    var color by remember(coverPath) { mutableStateOf(Color(0xFF6B5A70)) }
    LaunchedEffect(coverPath) {
        if (coverPath.isNullOrBlank()) {
            color = Color(0xFF6B5A70)
            return@LaunchedEffect
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        val next = withContext(Dispatchers.IO) {
            // 先查缓存（复用 CrossfadeAlbumArt 已加载的 128px / 1080px）
            val peek128 = BitmapProvider.peek(coverPath, 128, 128)
            val peek1080 = if (peek128 == null) BitmapProvider.peek(coverPath, 1080, 1080) else null
            val cached = peek128 ?: peek1080 ?: BitmapProvider.execute(coverPath, 96, 96)
            val hitType = when {
                peek128 != null -> "PEEK_128"
                peek1080 != null -> "PEEK_1080"
                cached != null -> "EXEC_96"
                else -> "NULL"
            }
            val elapsed = android.os.SystemClock.uptimeMillis() - t0
            android.util.Log.d("AlbumArt", "COLOR key=${coverPath.takeLast(30)} hit=$hitType result=${cached != null} ${elapsed}ms")
            if (cached != null && !cached.isRecycled) {
                val swatch = Palette.from(cached).maximumColorCount(8).generate()
                val rgb = swatch.mutedSwatch?.rgb
                    ?: swatch.vibrantSwatch?.rgb
                    ?: swatch.dominantSwatch?.rgb
                    ?: 0xFF6B5A70.toInt()
                Color(rgb).softenedForPlayer()
            } else {
                Color(0xFF6B5A70)
            }
        }
        color = next
    }
    return color
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
    val bits = song?.bitsPerSample?.takeIf { it > 0 }?.let { "$it BIT" }
    val sampleRate = song?.sampleRate?.takeIf { it > 0 }?.let {
        val khz = it / 1000.0
        if (khz == khz.toLong().toDouble()) "${khz.toLong()} KHZ" else "%.1f KHZ".format(khz)
    }
    val bitRate = song?.bitRate?.takeIf { it > 0 }?.let {
        com.rawsmusic.core.common.utils.BitrateNormalizer.formatKbps(it, song.duration, song.fileSize).uppercase()
    }
    val format = song?.format?.takeIf { it.isNotBlank() }?.uppercase()
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
