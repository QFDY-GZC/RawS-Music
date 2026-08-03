package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.LyricFontManager
import io.github.proify.lyricon.lyric.model.Song
import java.io.File
import kotlin.math.roundToInt

private data class CarouselLyricLine(
    val index: Int,
    val primary: String,
    val translation: String,
    val primaryIsChinese: Boolean
)

@Composable
internal fun HorizontalCarouselCurrentLyric(
    song: Song?,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    val line = rememberCarouselLyricLine(song, positionMs, displayTranslation = false)
    val font = rememberCarouselLyricFont()
    AnimatedContent(
        targetState = line,
        transitionSpec = {
            (slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessHigh
                )
            ) + fadeIn(tween(105))) togetherWith
                (slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessHigh
                    )
                ) + fadeOut(tween(95)))
        },
        contentAlignment = Alignment.Center,
        label = "horizontal-carousel-current-lyric",
        modifier = modifier.heightIn(min = 42.dp)
    ) { target ->
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (target != null) {
                Text(
                    text = target.primary,
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = font.sizeSp.sp,
                    fontWeight = FontWeight(font.weight),
                    fontFamily = font.family,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.28f))
                        .padding(horizontal = 18.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
internal fun VerticalDialCurrentLyric(
    song: Song?,
    positionMs: Long,
    displayTranslation: Boolean,
    modifier: Modifier = Modifier
) {
    val line = rememberCarouselLyricLine(song, positionMs, displayTranslation)
    val font = rememberCarouselLyricFont()
    val leftText = when {
        line == null -> ""
        line.primaryIsChinese -> line.primary
        displayTranslation -> line.translation
        else -> ""
    }
    val rightText = when {
        line == null -> ""
        line.primaryIsChinese -> line.translation.takeIf { displayTranslation }.orEmpty()
        else -> line.primary
    }

    Row(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DialLyricSide(
            lineIndex = line?.index ?: -1,
            text = leftText,
            alignment = Alignment.CenterEnd,
            textAlign = TextAlign.End,
            font = font,
            modifier = Modifier.weight(0.23f)
        )
        Spacer(Modifier.weight(0.54f))
        DialLyricSide(
            lineIndex = line?.index ?: -1,
            text = rightText,
            alignment = Alignment.CenterStart,
            textAlign = TextAlign.Start,
            font = font,
            modifier = Modifier.weight(0.23f)
        )
    }
}

private data class DialLyricSideLine(val index: Int, val text: String)

@Composable
private fun DialLyricSide(
    lineIndex: Int,
    text: String,
    alignment: Alignment,
    textAlign: TextAlign,
    font: CarouselLyricFont,
    modifier: Modifier
) {
    AnimatedContent(
        targetState = DialLyricSideLine(lineIndex, text),
        transitionSpec = {
            (slideInVertically(
                initialOffsetY = { fullHeight -> -fullHeight },
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessHigh
                )
            ) + fadeIn(tween(105))) togetherWith
                (slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessHigh
                    )
                ) + fadeOut(tween(95)))
        },
        contentAlignment = alignment,
        label = "vertical-dial-current-lyric",
        modifier = modifier
    ) { target ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = alignment
        ) {
            Text(
                text = target.text,
                color = Color.White.copy(alpha = if (target.text.isBlank()) 0f else 0.92f),
                fontSize = (font.sizeSp - 2).coerceAtLeast(13).sp,
                fontWeight = FontWeight(font.weight),
                fontFamily = font.family,
                textAlign = textAlign,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 18.dp)
            )
        }
    }
}

@Composable
private fun rememberCarouselLyricLine(
    song: Song?,
    positionMs: Long,
    displayTranslation: Boolean
): CarouselLyricLine? {
    val lines = remember(song) { song?.lyrics.orEmpty() }
    val interludes = remember(lines) { calculateLyricInterludes(lines) }
    val playback = remember(lines, interludes, positionMs) {
        calculateLyricPlaybackState(lines, positionMs, interludes)
    }
    val index = playback.currentLineIndex.takeIf { it >= 0 }
    return remember(lines, index, displayTranslation) {
        val resolvedIndex = index ?: return@remember null
        val line = lines.getOrNull(resolvedIndex) ?: return@remember null
        val primary = line.text.cleanCarouselLyricText()
            .ifBlank { line.secondary.cleanCarouselLyricText() }
            .ifBlank { line.translation.cleanCarouselLyricText() }
        if (primary.isBlank()) return@remember null
        CarouselLyricLine(
            index = resolvedIndex,
            primary = primary,
            translation = if (displayTranslation) line.translation.cleanCarouselLyricText() else "",
            primaryIsChinese = primary.containsChineseLyricText()
        )
    }
}

private data class CarouselLyricFont(
    val family: FontFamily?,
    val weight: Int,
    val sizeSp: Int
)

@Composable
private fun rememberCarouselLyricFont(): CarouselLyricFont {
    val revision by LyricFontManager.revision.collectAsState()
    val path = AppPreferences.LyricFont.fontPath
    val weight = AppPreferences.LyricFont.fontWeight.coerceIn(100, 900)
    val scale = AppPreferences.LyricFont.fontScale.coerceIn(50, 200)
    val family = remember(revision, path, weight) {
        if (path.isBlank()) null
        else runCatching { FontFamily(Font(File(path), FontWeight(weight))) }.getOrNull()
    }
    return remember(revision, family, weight, scale) {
        CarouselLyricFont(
            family = family,
            weight = weight,
            sizeSp = (20f * scale / 100f).roundToInt().coerceIn(14, 34)
        )
    }
}

private fun String?.cleanCarouselLyricText(): String {
    if (isNullOrBlank()) return ""
    val clean = trim()
    return if (CAROUSEL_TIMESTAMP_ONLY.matches(clean.replace(',', '.'))) "" else clean
}

private fun String.containsChineseLyricText(): Boolean = any { character ->
    character.code in 0x3400..0x4DBF ||
        character.code in 0x4E00..0x9FFF ||
        character.code in 0xF900..0xFAFF
}

private val CAROUSEL_TIMESTAMP_ONLY = Regex(
    """^(?:(?:\[|<)\d{1,2}:\d{2}(?:[.:]\d{1,3})?(?:]|>))+$"""
)
