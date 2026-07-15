package com.rawsmusic.core.ui.widget.player

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.core.ui.theme.ThemeManager
import io.github.proify.lyricon.lyric.model.Song
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LyricPage(
    currentSong: AudioFile? = null,
    coverPath: String? = null,
    song: Song? = null,
    positionMs: Long = 0L,
    isPlaying: Boolean = false,
    displayTranslation: Boolean = false,
    displayRoma: Boolean = false,
    @DrawableRes moreIconRes: Int = R.drawable.ic_more_vert,
    onSeek: (Long) -> Unit = {},
    onTranslationToggle: () -> Unit = {},
    onModifyAlbumArt: () -> Unit = {},
    onBack: () -> Unit = {},
    onModalVisibleChange: (Boolean) -> Unit = {},
    onModalDismissActionChange: ((() -> Unit)?) -> Unit = {},
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
    var showLyricMore by remember { mutableStateOf(false) }
    var lyricFontSizeSp by remember { mutableStateOf(AppPreferences.UI.lyricFontSizeSp) }
    var lyricBlurEnabled by remember { mutableStateOf(AppPreferences.UI.lyricBlurEnabled) }
    var lyricHighlightAllEnabled by remember {
        mutableStateOf(AppPreferences.UI.lyricHighlightAllEnabled)
    }
    var lyricTextPosition by remember {
        mutableStateOf(LyricTextPosition.from(AppPreferences.UI.lyricTextPosition))
    }

    fun closeLyricMore() {
        showLyricMore = false
        onModalDismissActionChange(null)
        onModalVisibleChange(false)
    }

    fun openLyricMore() {
        showLyricMore = true
        onModalDismissActionChange(::closeLyricMore)
        onModalVisibleChange(true)
    }

    DisposableEffect(Unit) {
        onDispose {
            onModalDismissActionChange(null)
            onModalVisibleChange(false)
        }
    }

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
                .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
                .padding(start = 18.dp, top = 22.dp, end = 18.dp)
        ) {
            LyricHeader(
                currentSong = currentSong,
                coverPath = coverPath,
                primaryColor = lyricTextColor,
                secondaryColor = lyricSecondaryColor,
                tertiaryColor = lyricTertiaryColor,
                moreIconRes = moreIconRes,
                onMore = ::openLyricMore,
                showCover = showHeaderCover
            )
            Spacer(Modifier.height(24.dp))
            Box(modifier = Modifier.weight(1f)) {
                ComposeLyricView(
                    song = song,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    textColor = lyricTextColor,
                    dimColor = lyricTextColor.copy(alpha = 0.42f),
                    secondaryColor = lyricTextColor.copy(alpha = 0.68f),
                    fontSizeSp = lyricFontSizeSp,
                    textPosition = lyricTextPosition,
                    blurEnabled = lyricBlurEnabled,
                    highlightAll = lyricHighlightAllEnabled,
                    onLineClick = onSeek,
                    onSwipeRight = onBack,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        BackHandler(enabled = showLyricMore) { closeLyricMore() }
        AnimatedVisibility(
            visible = showLyricMore,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            LyricMoreSheet(
                currentSong = currentSong,
                coverPath = coverPath,
                displayTranslation = displayTranslation,
                blurEnabled = lyricBlurEnabled,
                highlightAllEnabled = lyricHighlightAllEnabled,
                fontSizeSp = lyricFontSizeSp,
                textPosition = lyricTextPosition,
                onTranslationToggle = onTranslationToggle,
                onBlurEnabledChange = { enabled ->
                    lyricBlurEnabled = enabled
                    AppPreferences.UI.lyricBlurEnabled = enabled
                },
                onHighlightAllEnabledChange = { enabled ->
                    lyricHighlightAllEnabled = enabled
                    AppPreferences.UI.lyricHighlightAllEnabled = enabled
                },
                onFontSizeChange = { size ->
                    lyricFontSizeSp = size
                    AppPreferences.UI.lyricFontSizeSp = size
                },
                onTextPositionChange = { position ->
                    lyricTextPosition = position
                    AppPreferences.UI.lyricTextPosition = position.value
                },
                onModifyAlbumArt = {
                    closeLyricMore()
                    onModifyAlbumArt()
                },
                onDismiss = ::closeLyricMore
            )
        }
    }
}

@Composable
private fun LyricMoreSheet(
    currentSong: AudioFile?,
    coverPath: String?,
    displayTranslation: Boolean,
    blurEnabled: Boolean,
    highlightAllEnabled: Boolean,
    fontSizeSp: Int,
    textPosition: LyricTextPosition,
    onTranslationToggle: () -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
    onHighlightAllEnabledChange: (Boolean) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onTextPositionChange: (LyricTextPosition) -> Unit,
    onModifyAlbumArt: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val sheetColor = if (isDark) scheme.background else scheme.surface
    val cardColor = if (isDark) {
        scheme.surfaceContainerHigh.copy(alpha = 0.76f)
    } else {
        Color.White.copy(alpha = 0.92f)
    }
    val fontEntry = DropdownEntry(
        items = listOf(24, 28, 32, 36, 40).map { size ->
            DropdownItem(
                text = when (size) {
                    24 -> "小"
                    28 -> "标准"
                    32 -> "大"
                    36 -> "特大"
                    else -> "超大"
                },
                summary = "${size} sp",
                selected = size == fontSizeSp,
                onClick = { onFontSizeChange(size) }
            )
        }
    )
    val positionEntry = DropdownEntry(
        items = LyricTextPosition.entries.map { position ->
            DropdownItem(
                text = when (position) {
                    LyricTextPosition.Left -> "靠左"
                    LyricTextPosition.Center -> "居中"
                    LyricTextPosition.Right -> "靠右"
                },
                selected = position == textPosition,
                onClick = { onTextPositionChange(position) }
            )
        }
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
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(sheetColor)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardColor)
                ) {
                    if (!coverPath.isNullOrBlank()) {
                        com.rawsmusic.core.ui.widget.bitmaps.BitmapImage(
                            key = coverPath,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            targetWidth = 220,
                            targetHeight = 220,
                            surface = com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface.Playback
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = currentSong?.displayName ?: stringResource(R.string.player_no_song),
                        color = scheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentSong?.artist?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.player_unknown_artist),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
                    .clickable(onClick = onModifyAlbumArt)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(scheme.primary),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("修改此曲专辑图", color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("选择新的图片并写入当前歌曲", color = scheme.onSurfaceVariantSummary, fontSize = 13.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
                    .clickable(onClick = onTranslationToggle)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.lyric_more_translation_title),
                        color = scheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(
                            if (displayTranslation) R.string.lyric_more_translation_on
                            else R.string.lyric_more_translation_off
                        ),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = displayTranslation,
                    onCheckedChange = { onTranslationToggle() }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
                    .clickable { onHighlightAllEnabledChange(!highlightAllEnabled) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.lyric_more_highlight_all_title),
                        color = scheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(
                            if (highlightAllEnabled) R.string.lyric_more_highlight_all_on
                            else R.string.lyric_more_highlight_all_off
                        ),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = highlightAllEnabled,
                    onCheckedChange = onHighlightAllEnabledChange
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
                    .clickable { onBlurEnabledChange(!blurEnabled) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.lyric_more_blur_title),
                        color = scheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(
                            if (blurEnabled) R.string.lyric_more_blur_on
                            else R.string.lyric_more_blur_off
                        ),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = blurEnabled,
                    onCheckedChange = onBlurEnabledChange
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
            ) {
                WindowDropdownPreference(
                    entry = fontEntry,
                    title = "歌词字号",
                    summary = "${fontSizeSp} sp",
                    showValue = true,
                    maxHeight = 360.dp,
                    collapseOnSelection = true
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
            ) {
                WindowDropdownPreference(
                    entry = positionEntry,
                    title = "歌词位置",
                    summary = when (textPosition) {
                        LyricTextPosition.Left -> "靠左"
                        LyricTextPosition.Center -> "居中"
                        LyricTextPosition.Right -> "靠右"
                    },
                    showValue = true,
                    maxHeight = 300.dp,
                    collapseOnSelection = true
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
    showCover: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(
            coverPath = coverPath,
            title = currentSong?.displayName.orEmpty(),
            modifier = Modifier
                .size(104.dp)
                .graphicsLayer { alpha = if (showCover) 1f else 0f }
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
