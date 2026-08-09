package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine

private val FullCoverLyricMotionEasing = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)

private sealed interface FullCoverLyricRow {
    val stableKey: String

    data class Timed(
        val index: Int,
        val line: IRichLyricLine,
        val primary: String,
        val active: Boolean,
    ) : FullCoverLyricRow {
        override val stableKey: String = "line:$index:$active"
    }

    data class Translation(
        val text: String,
        val sourceIndex: Int,
    ) : FullCoverLyricRow {
        override val stableKey: String = "translation:$sourceIndex:$text"
    }
}

private data class FullCoverLyricPresentation(
    val anchorIndex: Int,
    val rows: List<FullCoverLyricRow>,
)

/**
 * Compact live lyric window for the full-cover carousel.
 *
 * Chinese lyrics keep a three-line timed window (previous/current/next). For non-Chinese lyrics,
 * the active rich line and its translation form two rows; when no translation exists, the next
 * timed line becomes the second row. The active row still renders through [KaraokeLyricLine], so
 * word timing, glow and lift stay connected to the ordinary player lyric engine.
 */
@Composable
internal fun FullCoverLyricPreview(
    song: Song?,
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val smoothPositionMs = rememberLyricTimelinePosition(
        positionMs = positionMs,
        isPlaying = isPlaying,
        durationMs = durationMs,
    )
    val lines = remember(song) { song?.lyrics.orEmpty() }
    val interludes = remember(lines) { calculateLyricInterludes(lines) }
    val visibleIndices = remember(lines) { visibleLyricLineIndices(lines) }
    val playback = remember(lines, interludes, smoothPositionMs) {
        calculateLyricPlaybackState(lines, smoothPositionMs, interludes)
    }
    // Do not keep the previous line alive during a real timing gap. The next
    // line becomes active only when the player position reaches its timestamp.
    val currentIndex = playback.currentLineIndex.takeIf { it >= 0 } ?: -1
    val presentation = remember(lines, visibleIndices, currentIndex) {
        buildFullCoverLyricPresentation(lines, visibleIndices, currentIndex)
    }

    AnimatedContent(
        targetState = presentation,
        transitionSpec = {
            (slideInVertically(
                initialOffsetY = { height -> height / 5 },
                animationSpec = tween(280, easing = FullCoverLyricMotionEasing),
            ) + fadeIn(tween(220, easing = FullCoverLyricMotionEasing))) togetherWith
                (slideOutVertically(
                    targetOffsetY = { height -> -height / 6 },
                    animationSpec = tween(220, easing = FullCoverLyricMotionEasing),
                ) + fadeOut(tween(180, easing = FullCoverLyricMotionEasing)))
        },
        label = "full-cover-live-lyric",
        modifier = modifier,
    ) { state ->
        Column(modifier = Modifier.fillMaxWidth()) {
            state.rows.forEach { row ->
                when (row) {
                    is FullCoverLyricRow.Timed -> {
                        val rowModifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeek(row.line.begin) }
                            .padding(vertical = if (row.active) 3.dp else 2.dp)
                        if (row.active && !row.line.words.isNullOrEmpty()) {
                            KaraokeLyricLine(
                                line = row.line,
                                positionMs = smoothPositionMs,
                                highlightColor = Color.White,
                                dimColor = Color.White.copy(alpha = 0.38f),
                                fontSize = 18.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End,
                                wordLiftDp = 0.45.dp,
                                wordLiftScale = 0.015f,
                                glowEnabled = true,
                                liftEnabled = true,
                                modifier = rowModifier,
                            )
                        } else {
                            Text(
                                text = row.primary,
                                color = Color.White.copy(alpha = if (row.active) 0.96f else 0.48f),
                                fontSize = if (row.active) 18.sp else 14.sp,
                                lineHeight = if (row.active) 23.sp else 19.sp,
                                fontWeight = if (row.active) FontWeight.SemiBold else FontWeight.Medium,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = FullCoverLyricShadowStyle,
                                modifier = rowModifier,
                            )
                        }
                    }

                    is FullCoverLyricRow.Translation -> Text(
                        text = row.text,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = FullCoverLyricShadowStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

private fun buildFullCoverLyricPresentation(
    lines: List<IRichLyricLine>,
    visibleIndices: List<Int>,
    currentIndex: Int,
): FullCoverLyricPresentation {
    val current = lines.getOrNull(currentIndex)
        ?: return FullCoverLyricPresentation(anchorIndex = -1, rows = emptyList())
    val primary = current.fullCoverPrimaryText()
    if (primary.isBlank()) {
        return FullCoverLyricPresentation(anchorIndex = currentIndex, rows = emptyList())
    }

    val visiblePosition = visibleIndices.indexOf(currentIndex).takeIf { it >= 0 } ?: 0
    val rowLimit = fullCoverLyricVisibleRowLimit(primary)
    val rows = if (rowLimit == 3) {
        val indices = buildList {
            visibleIndices.getOrNull(visiblePosition - 1)?.let(::add)
            add(currentIndex)
            visibleIndices.getOrNull(visiblePosition + 1)?.let(::add)
        }.distinct().take(rowLimit)
        indices.mapNotNull { index ->
            val line = lines.getOrNull(index) ?: return@mapNotNull null
            val text = line.fullCoverPrimaryText()
            text.takeIf(String::isNotBlank)?.let {
                FullCoverLyricRow.Timed(index, line, it, index == currentIndex)
            }
        }
    } else {
        val translation = current.translation.cleanFullCoverLyricText()
            .takeIf { it.isNotBlank() && it != primary }
        buildList {
            add(FullCoverLyricRow.Timed(currentIndex, current, primary, active = true))
            if (translation != null) {
                add(FullCoverLyricRow.Translation(translation, currentIndex))
            } else {
                visibleIndices.getOrNull(visiblePosition + 1)
                    ?.let(lines::getOrNull)
                    ?.let { next ->
                        val nextText = next.fullCoverPrimaryText()
                        if (nextText.isNotBlank()) {
                            add(FullCoverLyricRow.Timed(
                                index = visibleIndices.getOrNull(visiblePosition + 1) ?: currentIndex,
                                line = next,
                                primary = nextText,
                                active = false,
                            ))
                        }
                    }
            }
        }.take(rowLimit)
    }

    return FullCoverLyricPresentation(anchorIndex = currentIndex, rows = rows)
}

private fun IRichLyricLine.fullCoverPrimaryText(): String =
    text.cleanFullCoverLyricText()
        .ifBlank { secondary.cleanFullCoverLyricText() }
        .ifBlank { translation.cleanFullCoverLyricText() }

private fun String?.cleanFullCoverLyricText(): String {
    if (isNullOrBlank()) return ""
    val clean = trim()
    return if (FULL_COVER_TIMESTAMP_ONLY.matches(clean.replace(',', '.'))) "" else clean
}

private val FullCoverLyricShadowStyle = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.74f),
        offset = Offset(0f, 2f),
        blurRadius = 9f,
    )
)

private val FULL_COVER_TIMESTAMP_ONLY = Regex(
    """^(?:(?:\[|<)\d{1,2}:\d{2}(?:[.:]\d{1,3})?(?:]|>))+$"""
)
