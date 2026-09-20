package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.widget.player.KaraokeLyricLine
import com.rawsmusic.core.ui.widget.player.LyricColorSurface
import com.rawsmusic.core.ui.widget.player.LyricTimelineIndex
import com.rawsmusic.core.ui.widget.player.calculateLyricInterludes
import com.rawsmusic.core.ui.widget.player.calculateLyricPlaybackState
import com.rawsmusic.core.ui.widget.player.lyricRainbowMask
import com.rawsmusic.core.ui.widget.player.rememberResolvedLyricColor
import com.rawsmusic.core.ui.widget.player.visibleLyricLineIndices
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine

private val HomeLyricMotionEasing = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)

private data class HomeCarouselLyricLine(
    val index: Int,
    val line: IRichLyricLine?,
    val primary: String,
    val translation: String
)

@Composable
internal fun HomeHorizontalCarouselLyric(
    renderClock: com.rawsmusic.core.ui.widget.player.LyricDirectRenderClock,
    song: Song?,
    positionMs: Long,
    fallbackPrimary: String,
    fallbackTranslation: String,
    albumAccent: Color,
    modifier: Modifier = Modifier
) {
    val current = rememberHomeCarouselLyricLine(
        song = song,
        positionMs = positionMs,
        fallbackPrimary = fallbackPrimary,
        fallbackTranslation = fallbackTranslation
    )
    val lyricColors = rememberResolvedLyricColor(
        surface = LyricColorSurface.HOME,
        fallbackPrimary = Color.White,
        albumAccent = albumAccent,
    )
    val fontScale = LocalDensity.current.fontScale
    val lyricLaneHeight = 88.dp + (24.dp * (fontScale - 1f).coerceIn(0f, 0.5f))
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            ((slideInHorizontally(
                initialOffsetX = { width -> width / 4 },
                animationSpec = tween(280, easing = HomeLyricMotionEasing)
            ) + fadeIn(tween(220, easing = HomeLyricMotionEasing))) togetherWith
                (slideOutHorizontally(
                    targetOffsetX = { width -> -width / 5 },
                    animationSpec = tween(220, easing = HomeLyricMotionEasing)
                ) + fadeOut(tween(180, easing = HomeLyricMotionEasing))))
        },
        contentAlignment = Alignment.TopCenter,
        label = "home-horizontal-carousel-lyric",
        modifier = modifier.height(lyricLaneHeight)
    ) { lyric ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 2.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            if (lyric != null && lyric.primary.isNotBlank()) {
                Column(
                    modifier = Modifier.widthIn(max = 390.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HomePrimaryLyric(
                        renderClock = renderClock,
                        lyric = lyric,
                        positionMs = positionMs,
                        primaryColor = lyricColors.primary,
                        dimColor = lyricColors.dim,
                        rainbow = lyricColors.rainbow,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (lyric.translation.isNotBlank()) {
                        HomeTranslationLyric(
                            text = lyric.translation,
                            color = lyricColors.secondary,
                            rainbow = lyricColors.rainbow,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            fontSizeSp = 13,
                            lineHeightSp = 17,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeVerticalDialLyric(
    renderClock: com.rawsmusic.core.ui.widget.player.LyricDirectRenderClock,
    song: Song?,
    positionMs: Long,
    fallbackPrimary: String,
    fallbackTranslation: String,
    albumAccent: Color,
    modifier: Modifier = Modifier
) {
    val current = rememberHomeCarouselLyricLine(
        song = song,
        positionMs = positionMs,
        fallbackPrimary = fallbackPrimary,
        fallbackTranslation = fallbackTranslation
    )
    val lyricColors = rememberResolvedLyricColor(
        surface = LyricColorSurface.HOME,
        fallbackPrimary = Color.White,
        albumAccent = albumAccent,
    )
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            ((slideInVertically(
                initialOffsetY = { height -> -height / 5 },
                animationSpec = tween(300, easing = HomeLyricMotionEasing)
            ) + fadeIn(tween(230, easing = HomeLyricMotionEasing))) togetherWith
                (slideOutVertically(
                    targetOffsetY = { height -> height / 6 },
                    animationSpec = tween(230, easing = HomeLyricMotionEasing)
                ) + fadeOut(tween(180, easing = HomeLyricMotionEasing))))
        },
        contentAlignment = Alignment.Center,
        label = "home-dial-lyric",
        modifier = modifier
    ) { lyric ->
        val primaryIsChinese = lyric?.primary?.containsChineseHomeLyricText() == true
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(0.245f),
                contentAlignment = Alignment.CenterEnd
            ) {
                when {
                    lyric == null -> Unit
                    primaryIsChinese -> HomePrimaryLyric(
                        renderClock = renderClock,
                        lyric = lyric,
                        positionMs = positionMs,
                        primaryColor = lyricColors.primary,
                        dimColor = lyricColors.dim,
                        rainbow = lyricColors.rainbow,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                    lyric.translation.isNotBlank() -> HomeTranslationLyric(
                        text = lyric.translation,
                        color = lyricColors.secondary,
                        rainbow = lyricColors.rainbow,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.weight(0.51f))
            Box(
                modifier = Modifier.weight(0.245f),
                contentAlignment = Alignment.CenterStart
            ) {
                when {
                    lyric == null -> Unit
                    primaryIsChinese && lyric.translation.isNotBlank() -> HomeTranslationLyric(
                        text = lyric.translation,
                        color = lyricColors.secondary,
                        rainbow = lyricColors.rainbow,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    !primaryIsChinese -> HomePrimaryLyric(
                        renderClock = renderClock,
                        lyric = lyric,
                        positionMs = positionMs,
                        primaryColor = lyricColors.primary,
                        dimColor = lyricColors.dim,
                        rainbow = lyricColors.rainbow,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePrimaryLyric(
    renderClock: com.rawsmusic.core.ui.widget.player.LyricDirectRenderClock,
    lyric: HomeCarouselLyricLine,
    positionMs: Long,
    primaryColor: Color,
    dimColor: Color,
    rainbow: Boolean,
    textAlign: TextAlign,
    modifier: Modifier
) {
    val timedLine = lyric.line
    if (timedLine != null && !timedLine.words.isNullOrEmpty()) {
        KaraokeLyricLine(
            renderClock = renderClock,
            line = timedLine,
            positionMs = positionMs,
            highlightColor = primaryColor,
            dimColor = dimColor,
            fontSize = 18.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = textAlign,
            wordLiftDp = 0.45.dp,
            wordLiftScale = 0.015f,
            glowEnabled = true,
            liftEnabled = true,
            modifier = modifier.lyricRainbowMask(rainbow)
        )
    } else {
        Text(
            text = lyric.primary,
            color = primaryColor.copy(alpha = primaryColor.alpha.coerceAtMost(0.96f)),
            fontSize = 18.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = textAlign,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            style = HomeLyricShadowStyle,
            modifier = modifier.lyricRainbowMask(rainbow)
        )
    }
}

@Composable
private fun HomeTranslationLyric(
    text: String,
    color: Color,
    rainbow: Boolean,
    textAlign: TextAlign,
    modifier: Modifier,
    maxLines: Int = 6,
    fontSizeSp: Int = 14,
    lineHeightSp: Int = 19,
) {
    val readableColor = color.copy(alpha = color.alpha.coerceAtLeast(0.88f))
    Box(modifier = modifier) {
        Text(
            text = text,
            color = Color.Black.copy(alpha = 0.28f),
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = HomeTranslationUnderlayStyle,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = text,
            color = readableColor,
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = if (rainbow) TextStyle.Default else HomeLyricShadowStyle,
            modifier = Modifier
                .fillMaxWidth()
                .lyricRainbowMask(rainbow),
        )
    }
}

@Composable
private fun rememberHomeCarouselLyricLine(
    song: Song?,
    positionMs: Long,
    fallbackPrimary: String,
    fallbackTranslation: String
): HomeCarouselLyricLine? {
    val lines = remember(song) { song?.lyrics.orEmpty() }
    val interludes = remember(lines) { calculateLyricInterludes(lines) }
    val lyricTimeline = remember(lines, interludes) { LyricTimelineIndex(lines, interludes) }
    val playbackState = remember(lyricTimeline, positionMs) {
        calculateLyricPlaybackState(lyricTimeline, positionMs)
    }
    val visibleIndices = remember(lines) { visibleLyricLineIndices(lines) }
    val resolvedIndex = when {
        playbackState.currentLineIndex >= 0 -> playbackState.currentLineIndex
        else -> visibleIndices.lastOrNull { lines[it].begin <= positionMs }
            ?: playbackState.anchorLineIndex.takeIf { it >= 0 }
            ?: -1
    }
    val timed = lines.getOrNull(resolvedIndex)
    if (timed != null) {
        val primary = timed.text.cleanHomeCarouselLyricText()
            .ifBlank { timed.secondary.cleanHomeCarouselLyricText() }
            .ifBlank { timed.translation.cleanHomeCarouselLyricText() }
        if (primary.isNotBlank()) {
            // The home carousel intentionally exposes an available translation. The global
            // preference still controls the ordinary player lyric layout; this compact surface
            // would otherwise look broken when only the primary string is forwarded.
            val translation = timed.translation.cleanHomeCarouselLyricText()
                .takeIf { it != primary }
                .orEmpty()
            return HomeCarouselLyricLine(
                index = resolvedIndex,
                line = timed,
                primary = primary,
                translation = translation
            )
        }
    }

    val primary = fallbackPrimary.cleanHomeCarouselLyricText()
    if (primary.isBlank()) return null
    val translation = fallbackTranslation.cleanHomeCarouselLyricText()
        .takeIf { it != primary }
        .orEmpty()
    return HomeCarouselLyricLine(
        index = -1,
        line = null,
        primary = primary,
        translation = translation
    )
}

private val HomeLyricShadowStyle = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.74f),
        offset = Offset(0f, 2f),
        blurRadius = 9f
    )
)

private val HomeTranslationUnderlayStyle = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.92f),
        offset = Offset(0f, 1.5f),
        blurRadius = 7f,
    )
)

private fun String?.cleanHomeCarouselLyricText(): String {
    if (isNullOrBlank()) return ""
    val clean = trim()
    return if (HOME_CAROUSEL_TIMESTAMP_ONLY.matches(clean.replace(',', '.'))) "" else clean
}

private fun String.containsChineseHomeLyricText(): Boolean = any { character ->
    character.code in 0x3400..0x4DBF ||
        character.code in 0x4E00..0x9FFF ||
        character.code in 0xF900..0xFAFF
}

private val HOME_CAROUSEL_TIMESTAMP_ONLY = Regex(
    """^(?:(?:\[|<)\d{1,2}:\d{2}(?:[.:]\d{1,3})?(?:]|>))+$"""
)
