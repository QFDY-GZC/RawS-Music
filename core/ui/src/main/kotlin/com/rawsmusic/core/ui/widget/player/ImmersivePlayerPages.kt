package com.rawsmusic.core.ui.widget.player

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.rawsmusic.core.ui.widget.flow.rememberCurrentRawFlowMode
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    onOpenLyric: () -> Unit,
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
            onOpenLyric = onOpenLyric,
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
            showHeaderCover = true,
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

@OptIn(ExperimentalFoundationApi::class)
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
    onOpenLyric: () -> Unit = {},
    pageProgress: Float = 1f,
    renderBackdrop: Boolean = true,
    renderTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showMore by remember { mutableStateOf(false) }
    var immersiveProgressStyle by remember { mutableStateOf(ImmersiveProgressStyle.from(AppPreferences.UI.immersiveProgressStyle)) }
    var climaxEnabled by remember { mutableStateOf(AppPreferences.UI.immersiveClimaxEnabled) }
    var waveformDebugPanel by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformDebugPanel) }
    var waveformRemainingColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformRemainingColor) }
    var waveformPlayedColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformPlayedColor) }
    var waveformClimaxColorInt by remember { mutableStateOf(AppPreferences.UI.immersiveWaveformClimaxColor) }

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

    Box(modifier = modifier.fillMaxSize()) {
        val tone = rememberPlayerForegroundTone()
        if (renderBackdrop) ImmersiveBackdrop(coverPath = coverPath, pageProgress = pageProgress)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 30.dp, vertical = 14.dp)
        ) {
            val titleTop = (maxHeight * 0.405f + 8.dp).coerceAtLeast(116.dp)

            if (renderTopBar) {
                ImmersiveTopBar(
                    pageProgress = pageProgress,
                    showPageDots = false,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = titleTop),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        currentSong?.displayName ?: stringResource(R.string.player_no_song),
                        color = tone.primary,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 900)
                    )
                    Text(
                        currentSong?.artist?.ifBlank { stringResource(R.string.player_unknown_artist) } ?: "",
                        color = tone.secondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    MiniLyricPreview(
                        song = lyricSong,
                        positionMs = lyricPositionMs,
                        displayTranslation = displayTranslation,
                        displayRoma = displayRoma,
                        onClick = onOpenLyric,
                        primaryColor = tone.primary,
                        secondaryColor = tone.secondary,
                        dimColor = tone.tertiary
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♡", color = tone.icon, fontSize = 33.sp)
                    Text("••", color = tone.iconSoft, fontSize = 23.sp, modifier = Modifier.clickable {
                        showMore = true
                        onMorePanelVisibleChange(true)
                    })
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ImmersiveProgress(
                    currentSong = currentSong,
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
                    isPlaying = isPlaying,
                    progressStyle = immersiveProgressStyle,
                    climaxEnabled = climaxEnabled,
                    waveformDebugPanel = waveformDebugPanel,
                    waveformRemainingColor = Color(waveformRemainingColorInt),
                    waveformPlayedColor = Color(waveformPlayedColorInt),
                    waveformClimaxColor = Color(waveformClimaxColorInt),
                    onSeekStart = onSeekStart,
                    onSeekStop = onSeekStop
                )
                Spacer(Modifier.height(8.dp))
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
                    IconCircle(iconRes = playModeIconRes, size = 38.dp, tint = tone.iconSoft, onClick = onPlayMode)
                    IconCircle(iconRes = previousIconRes, size = 50.dp, tint = tone.icon, onClick = onPrevious)
                    IconCircle(
                        iconRes = if (isPlaying) pauseIconRes else playIconRes,
                        size = 68.dp,
                        tint = tone.icon,
                        onClick = onPlayPause
                    )
                    IconCircle(iconRes = nextIconRes, size = 50.dp, tint = tone.icon, onClick = onNext)
                    Text("≡", color = tone.iconSoft, fontSize = 33.sp)
                }
            }
        }
        BackHandler(enabled = showMore) {
            showMore = false
            onMorePanelVisibleChange(false)
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
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    textColor = tone.primary,
                    dimColor = tone.tertiary.copy(alpha = 0.62f),
                    secondaryColor = tone.secondary,
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
    // page: 0 = 专辑页，1 = 播放页，2 = 歌词页。
    // 背景统一走 RawFlowBackground，和主界面/列表页共用同一套主题跟随与流光开关。
    val flowMode = rememberCurrentRawFlowMode()
    val playerProgress = (1f - abs(pageProgress - 1f)).coerceIn(0f, 1f)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        RawFlowBackground(
            mode = flowMode,
            sourceCoverKey = coverPath,
            modifier = Modifier.fillMaxSize()
        )

        if (!coverPath.isNullOrBlank()) {
            val splitY = maxHeight * 0.41f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(splitY + 32.dp)
                    .align(Alignment.TopCenter)
                    .clipToBounds()
                    .graphicsLayer {
                        // 横滑进入歌词页时只做清晰封面淡出；返回播放页时自然淡入。
                        alpha = playerProgress
                    }
            ) {
                val fadeHeightPx = with(density) { 118.dp.toPx() }
                BitmapImage(
                    key = coverPath,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .edgeTransparent(EdgeFade.Bottom, fadeHeightPx),
                    contentScale = ContentScale.Crop,
                    targetWidth = 1080,
                    targetHeight = 1080,
                    priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_WIDGET,
                    surface = ArtworkSurface.Playback,
                    fadeInMillis = 0,
                    holdPreviousOnKeyChange = false,
                    fadeOnBitmapChange = false
                )
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

private data class ImmersiveLyricPreviewLine(
    val text: String,
    val secondary: String?,
    val active: Boolean,
    val allowWrap: Boolean
)

@Composable
private fun MiniLyricPreview(
    song: Song?,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onClick: () -> Unit,
    primaryColor: Color = Color.White,
    secondaryColor: Color = Color.White.copy(alpha = 0.58f),
    dimColor: Color = Color.White.copy(alpha = 0.40f)
) {
    val lines = remember(song, positionMs, displayTranslation, displayRoma) {
        currentLyricPreviewLines(song?.lyrics.orEmpty(), positionMs, displayTranslation, displayRoma)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (lines.any { !it.secondary.isNullOrBlank() }) 118.dp else 132.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.player_no_lyric),
                color = dimColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            return@Column
        }
        lines.forEachIndexed { index, line ->
            val alpha by animateFloatAsState(
                targetValue = if (line.active) 0.88f else 0.48f,
                animationSpec = tween(180)
            )
            Column(
                modifier = Modifier.graphicsLayer {
                    scaleX = if (line.active) 1f else 0.985f
                    scaleY = if (line.active) 1f else 0.985f
                }
            ) {
                Text(
                    text = line.text,
                    color = if (line.active) primaryColor.copy(alpha = alpha) else dimColor.copy(alpha = alpha),
                    fontSize = if (line.active) 15.sp else 13.sp,
                    fontWeight = if (line.active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = if (line.allowWrap) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
                val secondary = line.secondary
                if (!secondary.isNullOrBlank()) {
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
            if (index != lines.lastIndex) Spacer(Modifier.height(6.dp))
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
    if (progressStyle == ImmersiveProgressStyle.Waveform) {
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
                needle = Color.White.copy(alpha = 0.92f)
            ),
            climaxEnabled = climaxEnabled,
            showDebugPanel = waveformDebugPanel,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop
        )
        return
    }
    if (progressStyle == ImmersiveProgressStyle.Seconds) {
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
                needle = Color.White.copy(alpha = 0.92f)
            ),
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop
        )
        return
    }

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
                    .background(rememberPlayerForegroundTone().controlTrack)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayFraction.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(rememberPlayerForegroundTone().controlFill)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val tone = rememberPlayerForegroundTone()
            Text(AudioUtils.formatDuration(displayPositionMs), color = tone.tertiary, fontSize = 12.sp)
            Text(AudioUtils.formatDuration(totalDurationMs), color = tone.tertiary, fontSize = 12.sp)
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
            .background(rememberPlayerForegroundTone().chipBackground)
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
            color = rememberPlayerForegroundTone().chipText,
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
    onDismiss: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val sheetColor = if (isDark) scheme.background else scheme.surface
    val cardColor = if (isDark) scheme.surfaceContainerHigh.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.88f)
    val actionIconColor = if (isDark) scheme.primary else scheme.onSurface
    val actions = remember { immersiveMoreActions() }
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
                .background(sheetColor)
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(actions) { _, action ->
                    val label = stringResource(action.labelRes)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(cardColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(action.glyph, color = actionIconColor, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(label, color = scheme.onSurface, fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
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
        Text(
            text = stringResource(R.string.immersive_progress_style),
            color = scheme.onSurfaceVariantSummary,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ImmersiveSettingChip(
                label = stringResource(R.string.immersive_progress_style_classic),
                selected = progressStyle == ImmersiveProgressStyle.Classic,
                onClick = { onProgressStyleChange(ImmersiveProgressStyle.Classic) }
            )
            ImmersiveSettingChip(
                label = stringResource(R.string.immersive_progress_style_waveform),
                selected = progressStyle == ImmersiveProgressStyle.Waveform,
                onClick = { onProgressStyleChange(ImmersiveProgressStyle.Waveform) }
            )
            ImmersiveSettingChip(
                label = "秒级柱状",
                selected = progressStyle == ImmersiveProgressStyle.Seconds,
                onClick = { onProgressStyleChange(ImmersiveProgressStyle.Seconds) }
            )
        }
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

private data class ImmersiveMoreAction(
    @StringRes val labelRes: Int,
    val glyph: String
)

private fun immersiveMoreActions(): List<ImmersiveMoreAction> = listOf(
    ImmersiveMoreAction(R.string.player_more_add_playlist, "+"),
    ImmersiveMoreAction(R.string.player_more_effects, "≋"),
    ImmersiveMoreAction(R.string.player_more_play_mode, "↻"),
    ImmersiveMoreAction(R.string.player_more_queue, "≡"),
    ImmersiveMoreAction(R.string.player_more_metadata, "i"),
    ImmersiveMoreAction(R.string.player_more_preferences, "⚙")
)

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
): List<ImmersiveLyricPreviewLine> {
    if (lines.isEmpty()) return emptyList()
    val visibleIndices = lines.indices.filter { index ->
        lines[index].text.orEmpty().trim().isNotBlank()
    }
    if (visibleIndices.isEmpty()) return emptyList()

    val rawCurrent = lines.indexOfLast { line ->
        val end = if (line.end > line.begin) line.end else line.begin + line.duration
        positionMs >= line.begin && positionMs < end
    }.let { if (it >= 0) it else lines.indexOfLast { line -> line.begin <= positionMs }.coerceAtLeast(0) }
    val anchor = rawCurrent.coerceIn(0, lines.lastIndex)
    val anchorVisiblePosition = visibleIndices.indexOf(anchor).let { position ->
        if (position >= 0) position else visibleIndices.indexOfLast { it < anchor }.coerceAtLeast(0)
    }

    val hasSecondaryText = lines.any { line ->
        visiblePreviewSecondary(line, displayTranslation, displayRoma) != null
    }
    val maxPrimaryRows = if (hasSecondaryText) 3 else 5
    val preferredBefore = if (hasSecondaryText) 1 else 2
    var start = (anchorVisiblePosition - preferredBefore).coerceAtLeast(0)
    var endExclusive = (start + maxPrimaryRows).coerceAtMost(visibleIndices.size)
    start = (endExclusive - maxPrimaryRows).coerceAtLeast(0)
    endExclusive = (start + maxPrimaryRows).coerceAtMost(visibleIndices.size)

    return visibleIndices.subList(start, endExclusive).map { index ->
        val line = lines[index]
        val text = line.text.orEmpty().trim()
        val secondary = visiblePreviewSecondary(line, displayTranslation, displayRoma)
        ImmersiveLyricPreviewLine(
            text = text,
            secondary = secondary,
            active = index == anchor,
            allowWrap = index == anchor && text.length > 28
        )
    }
}

private fun visiblePreviewSecondary(
    line: IRichLyricLine,
    displayTranslation: Boolean,
    displayRoma: Boolean
): String? {
    return when {
        displayTranslation && !line.translation.isNullOrBlank() -> line.translation
        displayRoma && !line.roma.isNullOrBlank() -> line.roma
        !line.secondary.isNullOrBlank() -> line.secondary
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }
}

private fun darken(color: Color, factor: Float): Color {
    return Color(
        red = (color.red * factor).coerceIn(0f, 1f),
        green = (color.green * factor).coerceIn(0f, 1f),
        blue = (color.blue * factor).coerceIn(0f, 1f),
        alpha = color.alpha
    )
}
