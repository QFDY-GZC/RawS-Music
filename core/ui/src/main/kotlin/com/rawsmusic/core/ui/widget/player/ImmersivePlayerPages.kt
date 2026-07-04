package com.rawsmusic.core.ui.widget.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.blur as composeBlur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun ImmersivePlayerHorizontalStack(
    currentScene: PlayerSceneController.Scene,
    fromScene: PlayerSceneController.Scene,
    toScene: PlayerSceneController.Scene,
    progress: Float,
    isTransitioning: Boolean,
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
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    audioInfoText: String = "",
    albumSongs: List<AudioFile>,
    albumCoverPath: String?,
    onBack: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onMorePanelVisibleChange: (Boolean) -> Unit,
    onLyricSeek: (Long) -> Unit,
    onLyricTranslationToggle: () -> Unit,
    onAlbumSongClick: (AudioFile, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val basePage = if (isTransitioning) {
        lerp(scenePageIndex(fromScene), scenePageIndex(toScene), progress.coerceIn(0f, 1f))
    } else {
        scenePageIndex(currentScene).toFloat()
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun Modifier.pageLayer(page: Int): Modifier {
            val distance = page - basePage
            return graphicsLayer {
                translationX = distance * widthPx
                alpha = 1f
            }
        }
        ImmersiveBackdrop(coverPath = coverPath, pageProgress = basePage)
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
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
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
            onBack = onBack,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPlayMode = onPlayMode,
            onAudioQuality = onAudioQuality,
            onAudioQualityLongPress = onAudioQualityLongPress,
            onMorePanelVisibleChange = onMorePanelVisibleChange,
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
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            onSeek = onLyricSeek,
            onTranslationToggle = onLyricTranslationToggle,
            moreIconRes = R.drawable.ic_more_vert,
            onMore = { },
            onBack = { },
            renderBackdrop = false,
            modifier = Modifier.pageLayer(2)
        )
        ImmersiveTopBar(
            pageProgress = basePage,
            showPageDots = isImmersiveHorizontalPagingIndicatorVisible(
                isTransitioning = isTransitioning,
                fromScene = fromScene,
                toScene = toScene
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(start = 30.dp, end = 30.dp, top = 2.dp)
        )
    }
}

@Composable
fun ImmersivePlayerMainPage(
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
    lyricSong: Song?,
    lyricPositionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    audioInfoText: String = "",
    onBack: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayMode: () -> Unit,
    onAudioQuality: () -> Unit = {},
    onAudioQualityLongPress: () -> Unit = onAudioQuality,
    onMorePanelVisibleChange: (Boolean) -> Unit,
    pageProgress: Float = 1f,
    renderBackdrop: Boolean = true,
    renderTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showMore by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxSize()) {
        if (renderBackdrop) ImmersiveBackdrop(coverPath = coverPath, pageProgress = pageProgress)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 30.dp, vertical = 14.dp)
        ) {
            if (renderTopBar) {
                ImmersiveTopBar(pageProgress = pageProgress, showPageDots = false)
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.height(58.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        currentSong?.displayName ?: stringResource(R.string.player_no_song),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        currentSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "",
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(18.dp))
                    MiniLyricPreview(
                        song = lyricSong,
                        positionMs = lyricPositionMs,
                        displayTranslation = displayTranslation,
                        displayRoma = displayRoma
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♡", color = Color.White.copy(alpha = 0.84f), fontSize = 37.sp)
                    Text("••", color = Color.White.copy(alpha = 0.84f), fontSize = 23.sp, modifier = Modifier.clickable {
                        showMore = true
                        onMorePanelVisibleChange(true)
                    })
                }
            }
            Spacer(Modifier.height(24.dp))
            ImmersiveProgress(
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                onSeekStart = onSeekStart,
                onSeekStop = onSeekStop
            )
            Spacer(Modifier.height(8.dp))
            // 音频信息胶囊
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
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconCircle(iconRes = playModeIconRes, size = 38.dp, tint = Color.White.copy(alpha = 0.72f), onClick = onPlayMode)
                IconCircle(iconRes = previousIconRes, size = 50.dp, tint = Color.White, onClick = onPrevious)
                IconCircle(
                    iconRes = if (isPlaying) pauseIconRes else playIconRes,
                    size = 68.dp,
                    tint = Color.White,
                    onClick = onPlayPause
                )
                IconCircle(iconRes = nextIconRes, size = 50.dp, tint = Color.White, onClick = onNext)
                Text("≡", color = Color.White.copy(alpha = 0.72f), fontSize = 33.sp)
            }
        }
        AnimatedVisibility(
            visible = showMore,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            ImmersiveMoreSheet(
                currentSong = currentSong,
                coverPath = coverPath,
                onDismiss = {
                    showMore = false
                    onMorePanelVisibleChange(false)
                }
            )
        }
    }
}

@Composable
fun ImmersiveLyricPage(
    currentSong: AudioFile?,
    coverPath: String?,
    song: Song?,
    positionMs: Long,
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
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 34.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                currentSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 21.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                ComposeLyricView(
                    song = song,
                    positionMs = positionMs,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    textColor = Color.White,
                    dimColor = Color.White.copy(alpha = 0.28f),
                    secondaryColor = Color.White.copy(alpha = 0.58f),
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
                LyricBottomButton(text = if (displayTranslation) "译 on" else "译 off", onClick = onTranslationToggle)
                Spacer(Modifier.width(1.dp))
            }
        }
    }
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
                    currentSong?.displayName ?: "歌曲信息",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "本地歌曲详情  >",
                    color = Color.White.copy(alpha = 0.66f),
                    fontSize = 20.sp
                )
            }
            item { InfoLine("歌手", currentSong?.artist?.ifBlank { "未知艺术家" } ?: "未知艺术家", coverPath) }
            item { InfoLine("专辑", currentSong?.album?.ifBlank { "未知专辑" } ?: "未知专辑", coverPath) }
            item { InfoLine("制作", "作词 / 作曲信息来自本地元数据", coverPath) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoChip("↗ ${sameArtist.size}")
                    InfoChip("◉ 本地在听")
                    InfoChip("#${currentSong?.genre?.ifBlank { "Music" } ?: "Music"}")
                }
            }
            item {
                Text(
                    "${currentSong?.displayName ?: "这首歌"} ${currentSong?.artist?.ifBlank { "" } ?: ""}",
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "这里展示基于本地播放器可获得的歌曲、专辑、歌手和播放列表信息。",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 19.sp,
                    lineHeight = 30.sp
                )
            }
            item {
                Text(
                    "听「${currentSong?.artist?.ifBlank { "本地音乐" } ?: "本地音乐"}」的也在听",
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

/**
 * 双版本模糊 Modifier：
 * - Android 12+ (API 31+): 使用 RenderEffect 硬件加速
 * - Android 12 以下: 使用 Modifier.blur() 软件渲染
 */
@Composable
private fun Modifier.immersiveBlur(radius: Dp): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            renderEffect = android.graphics.RenderEffect
                .createBlurEffect(radius.toPx(), radius.toPx(), android.graphics.Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else {
        this.blur(radius)
    }
}

private enum class EdgeFade {
    Top,
    Bottom
}

private fun Modifier.edgeTransparent(
    edge: EdgeFade,
    widthPx: Float
): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()

        if (widthPx <= 0f) return@drawWithContent

        when (edge) {
            EdgeFade.Top -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White,
                            1.00f to Color.Transparent
                        ),
                        startY = 0f,
                        endY = widthPx
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, widthPx),
                    blendMode = BlendMode.DstOut
                )
            }

            EdgeFade.Bottom -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            1.00f to Color.White
                        ),
                        startY = size.height - widthPx,
                        endY = size.height
                    ),
                    topLeft = Offset(0f, size.height - widthPx),
                    size = Size(size.width, widthPx),
                    blendMode = BlendMode.DstOut
                )
            }
        }
    }

@Composable
private fun ImmersiveBackdrop(
    coverPath: String?,
    pageProgress: Float = 1f
) {
    val dominant = rememberDominantCoverColor(coverPath)

    val baseColor = darken(dominant, 0.62f)
    val deepColor = darken(dominant, 0.34f)
    val shadowColor = darken(dominant, 0.24f)

    // page: 0 = 专辑页，1 = 播放页，2 = 歌词页
    val sideProgress = abs(pageProgress - 1f).coerceIn(0f, 1f)
    val albumProgress = (1f - pageProgress).coerceIn(0f, 1f)
    val lyricProgress = (pageProgress - 1f).coerceIn(0f, 1f)
    val playerProgress = (1f - sideProgress).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(deepColor)
    ) {
        if (coverPath.isNullOrBlank()) return@BoxWithConstraints

        val density = LocalDensity.current

        // 主播放页顶部封面高度，按你当前要求使用 41%
        val splitY = maxHeight * 0.41f
        val overlap = 68.dp
        val bottomStart = (splitY - overlap).coerceAtLeast(0.dp)
        val bottomHeight = maxHeight - bottomStart

        // 遮罩层从 splitY 开始，不进入清晰封面区域
        val maskStart = splitY
        val maskHeight = maxHeight - maskStart

        val topMaskHeight = maxWidth * 70f / 195f
        val topEdgeFadePx = with(density) { 88.dp.toPx() }
        val bottomEdgeFadePx = with(density) { 180.dp.toPx() }

        val cWidth = constraints.maxWidth.toFloat()
        val cHeight = constraints.maxHeight.toFloat()

        // ---------------------------------------------------------
        // A. 主播放页背景：顶部清晰封面 + 底部重模糊倒影
        // ---------------------------------------------------------

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomHeight)
                .offset(y = bottomStart)
                .align(Alignment.TopCenter)
                .clipToBounds()
                .graphicsLayer {
                    alpha = playerProgress
                }
        ) {
            BitmapImage(
                key = coverPath,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = -1f
                        rotationZ = 180f
                        alpha = 0.94f
                    }
                    .immersiveBlur(64.dp),
                contentScale = ContentScale.Crop,
                targetWidth = 12,
                targetHeight = 12
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(baseColor.copy(alpha = 0.12f))
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomHeight)
                .offset(y = bottomStart)
                .align(Alignment.TopCenter)
                .clipToBounds()
                .edgeTransparent(
                    edge = EdgeFade.Top,
                    widthPx = bottomEdgeFadePx
                )
                .graphicsLayer {
                    alpha = playerProgress
                }
        ) {
            BitmapImage(
                key = coverPath,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = -1f
                        rotationZ = 180f
                        alpha = 0.18f
                    }
                    .immersiveBlur(48.dp),
                contentScale = ContentScale.Crop,
                targetWidth = 24,
                targetHeight = 24
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(splitY + 32.dp)
                .align(Alignment.TopCenter)
                .clipToBounds()
                .edgeTransparent(
                    edge = EdgeFade.Bottom,
                    widthPx = topEdgeFadePx
                )
                .graphicsLayer {
                    alpha = playerProgress
                }
        ) {
            BitmapImage(
                key = coverPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                targetWidth = 1080,
                targetHeight = 1080
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(overlap * 2.8f)
                .offset(y = splitY - overlap)
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    alpha = playerProgress
                }
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.20f to baseColor.copy(alpha = 0.12f),
                            0.50f to deepColor.copy(alpha = 0.28f),
                            0.78f to shadowColor.copy(alpha = 0.18f),
                            1.00f to Color.Transparent
                        )
                    )
                )
        )

        // ---------------------------------------------------------
        // B. 左右侧页背景：整屏低采样高斯模糊
        // albumProgress: 左滑进入专辑页
        // lyricProgress: 右滑进入歌词页
        // sideProgress: 任意离开播放页都会显示模糊背景
        // ---------------------------------------------------------

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = sideProgress
                }
        ) {
            // 主模糊色场：12x12 + 重模糊
            BitmapImage(
                key = coverPath,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                        alpha = 0.86f
                    }
                    .immersiveBlur(76.dp),
                contentScale = ContentScale.Crop,
                targetWidth = 12,
                targetHeight = 12
            )

            // 弱结构层：24x24，避免纯色死板，但不要看出封面
            BitmapImage(
                key = coverPath,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.10f
                        scaleY = 1.10f
                        alpha = 0.08f
                    }
                    .immersiveBlur(60.dp),
                contentScale = ContentScale.Crop,
                targetWidth = 24,
                targetHeight = 24
            )

            // 中央光晕：左右侧页都有，但位置略不同
            val glowX = cWidth * if (albumProgress > lyricProgress) 0.44f else 0.54f
            val glowY = cHeight * if (albumProgress > lyricProgress) 0.42f else 0.36f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colorStops = arrayOf(
                                0.00f to Color.White.copy(alpha = 0.14f),
                                0.20f to baseColor.copy(alpha = 0.12f),
                                0.48f to Color.Transparent,
                                1.00f to Color.Transparent
                            ),
                            center = Offset(
                                x = glowX,
                                y = glowY
                            ),
                            radius = cWidth * 0.78f
                        )
                    )
            )

            // 左侧专辑页比歌词页稍微亮一点，右侧歌词页更深
            val sideOverlayAlpha = if (albumProgress > lyricProgress) 0.46f else 0.54f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(deepColor.copy(alpha = sideOverlayAlpha))
            )

            // 侧页底部压暗
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Black.copy(alpha = 0.14f),
                                0.36f to Color.Transparent,
                                0.70f to shadowColor.copy(alpha = 0.24f),
                                1.00f to Color.Black.copy(alpha = 0.32f)
                            )
                        )
                    )
            )
        }

        // ---------------------------------------------------------
        // C. 通用顶部 mask
        // ---------------------------------------------------------

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topMaskHeight)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.18f),
                            0.58f to Color.Black.copy(alpha = 0.06f),
                            1.00f to Color.Transparent
                        )
                    )
                )
        )

        // ---------------------------------------------------------
        // D. 主播放页底部 mask — 注意：offset 用 maskStart，不进入清晰封面
        // ---------------------------------------------------------

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(maskHeight)
                .offset(y = maskStart)
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    alpha = playerProgress
                }
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.18f to baseColor.copy(alpha = 0.08f),
                            0.42f to deepColor.copy(alpha = 0.34f),
                            0.68f to shadowColor.copy(alpha = 0.58f),
                            1.00f to Color.Black.copy(alpha = 0.34f)
                        )
                    )
                )
        )
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
    displayRoma: Boolean
) {
    val lines = remember(song, positionMs, displayTranslation, displayRoma) {
        currentLyricPreviewLines(song?.lyrics.orEmpty(), positionMs, displayTranslation, displayRoma)
    }
    if (lines.isEmpty()) {
        Text(stringResource(R.string.player_no_lyric), color = Color.White.copy(alpha = 0.42f), fontSize = 18.sp, maxLines = 1)
        return
    }
    lines.take(4).forEachIndexed { index, line ->
        Text(
            line,
            color = Color.White.copy(alpha = if (index < 2) 0.74f else 0.42f),
            fontSize = if (index < 2) 19.sp else 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (index != lines.lastIndex) Spacer(Modifier.height(if (index == 1) 10.dp else 5.dp))
    }
}

@Composable
private fun ImmersiveProgress(
    currentPositionMs: Long,
    totalDurationMs: Long,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit
) {
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

    Column {
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
                    .background(Color.White.copy(alpha = 0.16f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayFraction.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.88f))
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AudioUtils.formatDuration(displayPositionMs), color = Color.White.copy(alpha = 0.54f), fontSize = 12.sp)
            Text(AudioUtils.formatDuration(totalDurationMs), color = Color.White.copy(alpha = 0.54f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ImmersiveQualityPill(song: AudioFile?, text: String, onClick: () -> Unit, onLongClick: () -> Unit = onClick) {
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
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White.copy(alpha = 0.78f), fontSize = 17.sp)
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
                BitmapImage(key = coverPath, contentDescription = null, modifier = Modifier.fillMaxSize(), targetWidth = 120, targetHeight = 120)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text("$label：", color = Color.White.copy(alpha = 0.64f), fontSize = 20.sp)
        Text(value, color = Color.White.copy(alpha = 0.82f), fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(text, color = Color.White, fontSize = 16.sp, maxLines = 1)
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
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            if (song.albumArtPath.isNotBlank()) {
                BitmapImage(key = song.albumArtPath, contentDescription = null, modifier = Modifier.fillMaxSize(), targetWidth = 180, targetHeight = 180)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.displayName, color = Color.White, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { "未知艺术家" }, color = Color.White.copy(alpha = 0.58f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("♡", color = Color.White.copy(alpha = 0.58f), fontSize = 31.sp)
    }
}

@Composable
private fun ImmersiveMoreSheet(currentSong: AudioFile?, coverPath: String?, onDismiss: () -> Unit) {
    val actionLabels = listOf(
        stringResource(R.string.player_more_add_playlist),
        stringResource(R.string.player_more_effects),
        stringResource(R.string.player_more_play_mode),
        stringResource(R.string.player_more_queue),
        stringResource(R.string.player_more_metadata)
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.54f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFFEFF4F5))
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 22.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                ) {
                    if (!coverPath.isNullOrBlank()) {
                        BitmapImage(key = coverPath, contentDescription = null, modifier = Modifier.fillMaxSize(), targetWidth = 220, targetHeight = 220)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(currentSong?.displayName ?: stringResource(R.string.player_no_song), color = Color(0xFF20242B), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(currentSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "", color = Color(0xFF707783), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(26.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(actionLabels) { _, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(actionGlyph(label), color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(label, color = Color.Black, fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

private fun actionGlyph(label: String): String = when (label) {
    "添加到歌单" -> "+"
    "音效设置" -> "≋"
    "播放模式" -> "↻"
    "播放列表" -> "≡"
    else -> "i"
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

private fun currentLyricPreviewLines(
    lines: List<IRichLyricLine>,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean
): List<String> {
    if (lines.isEmpty()) return emptyList()
    val current = lines.indexOfLast { line ->
        val end = if (line.end > line.begin) line.end else line.begin + line.duration
        positionMs >= line.begin && positionMs < end
    }.let { if (it >= 0) it else lines.indexOfLast { line -> line.begin <= positionMs }.coerceAtLeast(0) }
    val result = mutableListOf<String>()
    for (line in lines.drop(current).take(2)) {
        val main = line.text.orEmpty().ifBlank { continue }
        result += main
        val secondary = when {
            displayTranslation && !line.translation.isNullOrBlank() -> line.translation
            displayRoma && !line.roma.isNullOrBlank() -> line.roma
            !line.secondary.isNullOrBlank() -> line.secondary
            else -> null
        }
        if (!secondary.isNullOrBlank()) result += secondary
    }
    return if (result.isNotEmpty()) result.take(4) else lines.drop(current).take(4).mapNotNull { it.text }
}

private fun darken(color: Color, factor: Float): Color {
    return Color(
        red = (color.red * factor).coerceIn(0f, 1f),
        green = (color.green * factor).coerceIn(0f, 1f),
        blue = (color.blue * factor).coerceIn(0f, 1f),
        alpha = color.alpha
    )
}
