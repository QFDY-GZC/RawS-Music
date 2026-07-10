package com.rawsmusic.core.ui.widget.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.core.ui.theme.ThemeManager
import io.github.proify.lyricon.lyric.model.Song

@Composable
fun LyricPage(
    currentSong: AudioFile? = null,
    coverPath: String? = null,
    song: Song? = null,
    positionMs: Long = 0L,
    displayTranslation: Boolean = false,
    displayRoma: Boolean = false,
    @DrawableRes moreIconRes: Int = R.drawable.ic_more_vert,
    onSeek: (Long) -> Unit = {},
    onTranslationToggle: () -> Unit = {},
    onMore: () -> Unit = {},
    onBack: () -> Unit = {},
    onCoverSwipeDownStart: () -> Unit = {},
    onCoverSwipeDownProgress: (Float) -> Unit = {},
    onCoverSwipeDownEnd: (Boolean, Float) -> Unit = { _, _ -> },
    showHeaderCover: Boolean = true,
    renderBackdrop: Boolean = true,
    contentAlpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val dark = ThemeManager.isDarkMode(LocalContext.current)
    val lyricTextColor = if (dark) Color.White else Color(0xFF1B1B20)
    val lyricSecondaryColor = lyricTextColor.copy(alpha = if (dark) 0.86f else 0.70f)
    val lyricTertiaryColor = lyricTextColor.copy(alpha = if (dark) 0.50f else 0.48f)
    val accent = rememberCoverAccentColor(coverPath)
    LaunchedEffect(coverPath) {
        if (!coverPath.isNullOrBlank()) {
            BitmapProvider.warmPlaybackArt(coverPath)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (renderBackdrop) {
            StandardPlayerBackdrop(coverPath = coverPath, accent = accent)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
                .padding(horizontal = 18.dp, vertical = 22.dp)
        ) {
            LyricHeader(
                currentSong = currentSong,
                coverPath = coverPath,
                primaryColor = lyricTextColor,
                secondaryColor = lyricSecondaryColor,
                tertiaryColor = lyricTertiaryColor,
                moreIconRes = moreIconRes,
                onMore = onMore,
                showCover = showHeaderCover,
                onCoverSwipeDown = onBack,
                onCoverSwipeDownStart = onCoverSwipeDownStart,
                onCoverSwipeDownProgress = onCoverSwipeDownProgress,
                onCoverSwipeDownEnd = onCoverSwipeDownEnd
            )
            Spacer(Modifier.height(24.dp))
            Box(modifier = Modifier.weight(1f)) {
                ComposeLyricView(
                    song = song,
                    positionMs = positionMs,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    textColor = lyricTextColor,
                    dimColor = lyricTextColor.copy(alpha = 0.42f),
                    secondaryColor = lyricTextColor.copy(alpha = 0.68f),
                    onLineClick = onSeek,
                    onSwipeRight = onBack,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TranslationButton(
                    selected = displayTranslation,
                    onClick = onTranslationToggle
                )
            }
        }
    }
}

@Composable
private fun LyricHeader(
    currentSong: AudioFile?,
    coverPath: String?,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    showCover: Boolean,
    onCoverSwipeDown: () -> Unit,
    onCoverSwipeDownStart: () -> Unit,
    onCoverSwipeDownProgress: (Float) -> Unit,
    onCoverSwipeDownEnd: (Boolean, Float) -> Unit
) {
    var coverSize by remember { mutableStateOf(IntSize.Zero) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(
            coverPath = coverPath,
            title = currentSong?.displayName.orEmpty(),
            modifier = Modifier
                .size(104.dp)
                .onSizeChanged { coverSize = it }
                .graphicsLayer { alpha = if (showCover) 1f else 0f }
                .pointerInput(Unit) {
                    var dragY = 0f
                    var draggingDown = false
                    detectDragGestures(
                        onDragStart = {
                            dragY = 0f
                            draggingDown = false
                        },
                        onDragEnd = {
                            if (draggingDown) {
                                val commit = dragY / (coverSize.height.toFloat().coerceAtLeast(1f) * 2.2f) > 0.3f
                                onCoverSwipeDownEnd(commit, 0f)
                                if (!commit) Unit
                            } else if (dragY > 72f) {
                                onCoverSwipeDown()
                            }
                            dragY = 0f
                            draggingDown = false
                        },
                        onDragCancel = {
                            if (draggingDown) onCoverSwipeDownEnd(false, 0f)
                            dragY = 0f
                            draggingDown = false
                        },
                        onDrag = { change, amount ->
                            if (!draggingDown && amount.y > 0f && kotlin.math.abs(amount.y) > kotlin.math.abs(amount.x) * 1.2f) {
                                draggingDown = true
                                onCoverSwipeDownStart()
                            }
                            if (draggingDown) {
                                dragY = (dragY + amount.y).coerceAtLeast(0f)
                                val ratio = (dragY / (coverSize.height.toFloat().coerceAtLeast(1f) * 2.2f)).coerceIn(0f, 1f)
                                onCoverSwipeDownProgress(ratio)
                                change.consume()
                            }
                        }
                    )
                }
        )
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentSong?.displayName ?: stringResource(R.string.player_no_song),
                color = primaryColor,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artistLine(currentSong),
                color = secondaryColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = lyricAudioMeta(currentSong),
                color = tertiaryColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.10f))
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(moreIconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(secondaryColor),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun LyricMiniNowPlaying(
    currentSong: AudioFile?,
    coverPath: String?,
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    showCover: Boolean,
    primaryColor: Color = Color.White,
    secondaryColor: Color = Color.White.copy(alpha = 0.82f)
) {
    Row(
        modifier = Modifier
            .height(72.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(primaryColor.copy(alpha = 0.10f))
            .clickable(onClick = onMore)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(
            coverPath = coverPath,
            title = currentSong?.displayName.orEmpty(),
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer { alpha = if (showCover) 1f else 0f },
            radius = 14
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.width(190.dp)) {
            Text(
                text = currentSong?.displayName ?: stringResource(R.string.player_no_song),
                color = primaryColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artistLine(currentSong),
                color = secondaryColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Image(
            painter = painterResource(moreIconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(secondaryColor),
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun CoverThumb(
    coverPath: String?,
    title: String,
    modifier: Modifier = Modifier,
    radius: Int = 24
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius.dp))
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        if (!coverPath.isNullOrBlank()) {
            CrossfadeAlbumArt(
                key = coverPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                lowResSize = 192,
                hiResSize = 512,
                fadeMillis = 120,
                holdPreviousOnKeyChange = true,
                priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_WIDGET
            )
        } else {
            Text("*", color = Color.White.copy(alpha = 0.5f), fontSize = 22.sp)
        }
    }
}

@Composable
private fun TranslationButton(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (selected) 0.20f else 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "译",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

private fun artistLine(song: AudioFile?): String {
    val artist = song?.artist?.takeIf { it.isNotBlank() } ?: return ""
    val album = song.album.takeIf { it.isNotBlank() }
    return listOfNotNull(artist, album).joinToString(" - ")
}

private fun lyricAudioMeta(song: AudioFile?): String {
    val audio = song
    val duration = audio?.duration?.takeIf { it > 0 }?.let {
        val seconds = it / 1000L
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
    val format = audio?.format?.takeIf { it.isNotBlank() }?.lowercase()
    val sample = audio?.let { current ->
        current.sampleRate.takeIf { it > 0 }?.let {
            com.rawsmusic.core.common.utils.SampleRateNormalizer.formatKhz(
                sampleRate = it,
                codecName = current.encodingFormat,
                formatName = current.format,
                filePath = current.path
            )
        }
    }?.takeIf { it.isNotBlank() }
    val bits = audio?.bitsPerSample?.takeIf { it > 0 }?.let { "$it bit" }
    return listOfNotNull(duration, format, sample, bits).joinToString(" | ")
}
