package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import java.text.Bidi
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Keep the karaoke gradient feather width as an explicit local rendering parameter.
// archive does not contain resource values. Keep the fallback centralized; the gradient geometry
// and progress math below match C11983A.m15398q0().
private val AM_KARAOKE_FEATHER_WIDTH_FALLBACK = 12.dp
// Keep the emphasis shadow radius as an explicit local rendering parameter.
// missing from the decompiled archive. Keep the fallback in one place so it can be replaced when
// resources.arsc/dimens.xml is available.
private val AM_KARAOKE_EMPHASIS_SHADOW_RADIUS_FALLBACK = 5.dp
private const val AM_KARAOKE_EMPHASIS_MIN_DURATION_MS = 1_000L
private const val AM_KARAOKE_EMPHASIS_SCALE_MAX_DURATION_MS = 2_000L
private const val AM_KARAOKE_EMPHASIS_ANIMATION_MAX_DURATION_MS = 3_000L
private const val AM_KARAOKE_EMPHASIS_MAX_GLYPHS = 7
private const val AM_KARAOKE_EMPHASIS_MAX_SCALE_DELTA = 0.14f
private const val AM_KARAOKE_EMPHASIS_SHADOW_ALPHA = 0.5019608f // round(127.5) / 255
private const val AM_KARAOKE_EMPHASIS_STAGGER_FRACTION = 0.4f
private const val AM_KARAOKE_EMPHASIS_MAX_STAGGER_MS = 400f
private val AM_KARAOKE_EMPHASIS_EASING = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

private fun amKaraokeFeatherScaleForText(text: String): Float {
    // C11983A.m15393h0() does not use one global feather width. For ordinary karaoke words
    // whose exact Flex-line edge metadata is unavailable in Compose, its stable non-edge branch
    // scales the resource width by 0.5 for very short words and 0.25 for longer words. Keep that
    // source-backed distinction here instead of applying the full resource width to every word.
    // The remaining 1.0 edge case depends on FullWidthAlphaGradientFlexboxLayout row geometry and
    // will be added when the mask owner is moved to a whole-line layout.
    val glyphCount = splitGraphemeClusters(text.trim()).size
    return when {
        glyphCount <= 0 -> 1f
        glyphCount <= 2 -> 0.5f
        else -> 0.25f
    }
}

// These scripts stay in the shaped-word lane instead of the
// split-on-glyph emphasis lane because splitting would break script shaping. CJK/Kana are the
// exception: multi-glyph long notes are explicitly allowed through the split lane.
private val AM_KARAOKE_SHAPED_SCRIPT_BLOCKS = setOf(
    Character.UnicodeBlock.THAI,
    Character.UnicodeBlock.ARABIC,
    Character.UnicodeBlock.ARABIC_SUPPLEMENT,
    Character.UnicodeBlock.ARABIC_EXTENDED_A,
    Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
    Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B,
    Character.UnicodeBlock.DEVANAGARI,
    Character.UnicodeBlock.HANGUL_SYLLABLES,
    Character.UnicodeBlock.HANGUL_JAMO,
    Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
)

/**
 * Karaoke lyric line.
 *
 * - sweep progress 直接由前台 16 ms 歌词时钟计算，按显示帧推进羽化和高亮
 * - lift 只上抬：已开始/已完成的单词保持抬升，当前行结束后随整行退出，不再回落
 */
@Composable
fun KaraokeLyricLine(
    line: IRichLyricLine,
    positionMs: Long,
    highlightColor: Color,
    dimColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 28.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 34.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign = TextAlign.Start,
    wordLiftDp: Dp = 0.25.dp,
    wordLiftScale: Float = 0f,
    glowEnabled: Boolean = true,
    liftEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val words = line.words.orEmpty()
    val text = line.text.orEmpty()
    if (words.isEmpty() || text.isBlank()) return
    val lineEndMs = remember(line, words) { effectiveSingleLineEnd(line, words) }

    KaraokeTimedText(
        text = text,
        words = words,
        lineEndMs = lineEndMs,
        positionMs = positionMs,
        highlightColor = highlightColor,
        dimColor = dimColor,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textAlign = textAlign,
        wordLiftDp = wordLiftDp,
        wordLiftScale = wordLiftScale,
        glowEnabled = glowEnabled,
        liftEnabled = liftEnabled,
        modifier = modifier
    )
}

@Composable
fun KaraokeTimedText(
    text: String,
    words: List<LyricWord>,
    lineEndMs: Long,
    positionMs: Long,
    highlightColor: Color,
    dimColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily?,
    textAlign: TextAlign,
    wordLiftDp: Dp = 0.25.dp,
    wordLiftScale: Float = 0f,
    glowEnabled: Boolean = true,
    liftEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (words.isEmpty() || text.isBlank()) return
    val segments = remember(text, words) { buildLyricSegments(text, words) }

    val style = TextStyle(
        fontSize = fontSize, lineHeight = lineHeight,
        fontWeight = fontWeight, fontFamily = fontFamily
    )

    BaselineFlowRow(
        modifier = modifier,
        textAlign = textAlign
    ) {
        segments.forEach { segment ->
            when (segment) {
                is LyricSegment.Space -> {
                    StaticLyricText(text = segment.text, color = dimColor, style = style)
                }
                is LyricSegment.Word -> {
                    val timing = wordTimingState(
                        positionMs = positionMs,
                        word = segment.word,
                        nextWordBeginMs = segment.nextWordBeginMs,
                        lineEndMs = lineEndMs
                    )
                    val isCurrentWord = timing.progress > 0f && timing.progress < 1f
                    val isCompleted = timing.progress >= 1f
                    // Use LyricsWord.getDuration() for long-note emphasis. Keep that raw word
                    // duration separate from RawSMusic's sweep end, which may be clipped to the
                    // next word begin to avoid karaoke-color overlap.
                    val emphasisEndMs = segment.word.end
                        .takeIf { it > timing.beginMs }
                        ?: timing.endMs

                    KaraokeWordGroup(
                        text = segment.text,
                        progress = timing.progress,
                        wordBeginMs = timing.beginMs,
                        emphasisEndMs = emphasisEndMs,
                        positionMs = positionMs,
                        isCurrentWord = isCurrentWord,
                        isCompleted = isCompleted,
                        highlightColor = highlightColor,
                        dimColor = dimColor,
                        style = style,
                        wordLiftDp = wordLiftDp,
                        wordLiftScale = wordLiftScale,
                        glowEnabled = glowEnabled,
                        liftEnabled = liftEnabled
                    )
                }
            }
        }
    }
}

// ========== Word Group ==========

@Composable
private fun KaraokeWordGroup(
    text: String,
    progress: Float,
    wordBeginMs: Long,
    emphasisEndMs: Long,
    positionMs: Long,
    isCurrentWord: Boolean,
    isCompleted: Boolean,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    wordLiftDp: Dp,
    wordLiftScale: Float,
    glowEnabled: Boolean,
    liftEnabled: Boolean
) {
    val density = LocalDensity.current
    val maxLiftPx = with(density) { wordLiftDp.toPx() }
    val shadowRadiusPx = with(density) { AM_KARAOKE_EMPHASIS_SHADOW_RADIUS_FALLBACK.toPx() }

    // Keep the existing word-lift spring (0.93 / 25). This is a separate axis
    // from the long-note emphasis scale; the default wordLiftScale is 0, so emphasis does not get
    // accidentally double-scaled.
    val lift = remember(text, wordBeginMs) { Animatable(0f) }
    // Drive lift from the timed word event itself, not from a partially-filled karaoke mask.
    // Once fired, the word stays lifted until line/reset ownership releases it.
    val shouldLift = liftEnabled && positionMs >= wordBeginMs

    LaunchedEffect(text, wordBeginMs, shouldLift) {
        lift.animateTo(
            targetValue = if (shouldLift) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.93f, stiffness = 25f)
        )
    }

    val coreText = remember(text) { text.trimEnd() }
    val suffix = remember(text, coreText) { text.substring(coreText.length) }
    val glyphs = remember(coreText) { splitGraphemeClusters(coreText) }
    val emphasisEligible = remember(coreText, wordBeginMs, emphasisEndMs, glyphs) {
        isAMLongNoteEmphasisEligible(
            text = coreText,
            durationMs = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L),
            glyphCount = glyphs.size,
        )
    }

    val wholeWordModifier = Modifier.graphicsLayer {
        translationY = -maxLiftPx * lift.value
        val legacyScale = 1f + wordLiftScale * lift.value
        scaleX = legacyScale
        scaleY = legacyScale
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
    val splitGlyphBaseModifier = Modifier.graphicsLayer {
        // Split-character lanes own vertical lift per glyph. Keep only the legacy whole-word
        // scale here so the outer node never double-applies translationY.
        val legacyScale = 1f + wordLiftScale * lift.value
        scaleX = legacyScale
        scaleY = legacyScale
        transformOrigin = TransformOrigin(0.5f, 1f)
    }

    val splitEmphasis = emphasisEligible && shouldSplitAMLongNoteEmphasis(coreText, glyphs)
    when {
        splitEmphasis -> {
            AMEmphasisWord(
                glyphs = glyphs,
                suffix = suffix,
                wordProgress = progress,
                wordBeginMs = wordBeginMs,
                emphasisEndMs = emphasisEndMs,
                positionMs = positionMs,
                highlightColor = highlightColor,
                dimColor = dimColor,
                style = style,
                shadowRadiusPx = shadowRadiusPx,
                glowEnabled = glowEnabled,
                wordLiftPx = maxLiftPx,
                liftEnabled = liftEnabled,
                modifier = splitGlyphBaseModifier,
            )
        }
        emphasisEligible -> {
            // Keep CJK single-glyph and shaping-sensitive scripts in one text view instead of
            // splitting them into characters.  They still need the long-note emphasis owner; our
            // previous port incorrectly treated "do not split" as "do not emphasize", which made
            // the glow disappear for the most common single-character CJK long notes.
            AMShapedEmphasisWord(
                text = text,
                wordProgress = progress,
                wordBeginMs = wordBeginMs,
                emphasisEndMs = emphasisEndMs,
                positionMs = positionMs,
                highlightColor = highlightColor,
                dimColor = dimColor,
                style = style,
                shadowRadiusPx = shadowRadiusPx,
                glowEnabled = glowEnabled,
                modifier = wholeWordModifier,
            )
        }
        else -> {
            ProgressiveLyricText(
                text = text,
                progress = progress,
                highlightColor = highlightColor,
                dimColor = dimColor,
                style = style,
                shadowAlpha = 0f,
                shadowRadiusPx = shadowRadiusPx,
                rtl = isTextRtl(text),
                modifier = wholeWordModifier,
            )
        }
    }
}

@Composable
private fun AMShapedEmphasisWord(
    text: String,
    wordProgress: Float,
    wordBeginMs: Long,
    emphasisEndMs: Long,
    positionMs: Long,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    shadowRadiusPx: Float,
    glowEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val rawDurationMs = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L)
    val fraction = amGlyphEmphasisFraction(
        elapsedMs = (positionMs - wordBeginMs).toFloat(),
        rawDurationMs = rawDurationMs,
        glyphCount = 1,
        glyphIndex = 0,
    )
    val scale = 1f + (amEmphasisTargetScale(rawDurationMs) - 1f) * fraction
    val rtl = remember(text) { isTextRtl(text) }
    AMGappedGlyphLayout(
        modifier = modifier,
        scales = listOf(scale),
        rtl = rtl,
        suffixPresent = false,
    ) {
        ProgressiveLyricText(
            text = text,
            progress = wordProgress,
            highlightColor = highlightColor,
            dimColor = dimColor,
            style = style,
            shadowAlpha = if (glowEnabled) fraction * AM_KARAOKE_EMPHASIS_SHADOW_ALPHA else 0f,
            shadowRadiusPx = shadowRadiusPx,
            rtl = rtl,
        )
    }
}

/**
 * Long-note emphasis with timed attack, hold, and release phases.
 *
 * Each glyph has two independent ValueAnimator-equivalent phases:
 *  - grow: startDelay = index * min(0.4 * rawDuration / glyphCount, 400ms)
 *  - return: startDelay = growStart + rawDuration / (glyphCount / 2f)
 * Both phases use the same capped duration (<= 3000ms) and PathInterpolator(0.25, .1, .25, 1).
 * The return phase cancels grow and starts from the exact scale/shadow reached at that instant.
 */
@Composable
private fun AMEmphasisWord(
    glyphs: List<String>,
    suffix: String,
    wordProgress: Float,
    wordBeginMs: Long,
    emphasisEndMs: Long,
    positionMs: Long,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    shadowRadiusPx: Float,
    glowEnabled: Boolean,
    wordLiftPx: Float,
    liftEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val rawDurationMs = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L)
    val elapsedMs = (positionMs - wordBeginMs).toFloat()
    val glyphCount = glyphs.size.coerceAtLeast(1)
    val density = LocalDensity.current
    val featherPx = with(density) {
        AM_KARAOKE_FEATHER_WIDTH_FALLBACK.toPx() *
            amKaraokeFeatherScaleForText(glyphs.joinToString(separator = ""))
    }
    val glyphWidths = remember(glyphs) {
        mutableStateListOf<Float>().apply { repeat(glyphCount) { add(1f) } }
    }
    val glyphLiftStates = remember(glyphs, wordBeginMs) {
        List(glyphCount) { Animatable(0f) }
    }
    val targetScale = amEmphasisTargetScale(rawDurationMs)
    val emphasisFractions = remember(positionMs, wordBeginMs, emphasisEndMs, glyphs) {
        List(glyphCount) { index ->
            amGlyphEmphasisFraction(
                elapsedMs = elapsedMs,
                rawDurationMs = rawDurationMs,
                glyphCount = glyphCount,
                glyphIndex = index,
            )
        }
    }
    val scales = remember(emphasisFractions, targetScale) {
        emphasisFractions.map { fraction -> 1f + (targetScale - 1f) * fraction }
    }
    val rtl = remember(glyphs) { isTextRtl(glyphs.joinToString(separator = "")) }

    AMGappedGlyphLayout(
        modifier = modifier,
        scales = scales,
        rtl = rtl,
        suffixPresent = suffix.isNotEmpty(),
        content = {
            glyphs.forEachIndexed { index, glyph ->
                // Split these words into individual glyph lanes. Map the global karaoke
                // sweep into the same logical glyph slots while keeping the feather inside each
                // glyph. The emphasis timing itself is independent from highlight sweep timing.
                val totalWidth = glyphWidths.sum().coerceAtLeast(1f)
                val glyphStart = glyphWidths.take(index).sum()
                val glyphWidth = glyphWidths[index].coerceAtLeast(1f)
                val boundary = wordProgress.coerceIn(0f, 1f) * totalWidth
                val localProgress = ((boundary - glyphStart) / glyphWidth).coerceIn(0f, 1f)
                val localFadeStart = ((boundary - featherPx - glyphStart) / glyphWidth).coerceIn(0f, 1f)
                val localFadeEnd = ((boundary - glyphStart) / glyphWidth).coerceIn(0f, 1f)
                val liftStartMs = amGlyphLiftStartMs(rawDurationMs, glyphCount, index)
                val shouldGlyphLift = liftEnabled && elapsedMs >= liftStartMs
                val glyphLift = glyphLiftStates[index]
                LaunchedEffect(glyphs, wordBeginMs, index, shouldGlyphLift) {
                    glyphLift.animateTo(
                        targetValue = if (shouldGlyphLift) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.93f, stiffness = 25f),
                    )
                }
                ProgressiveLyricText(
                    text = glyph,
                    progress = localProgress,
                    highlightColor = highlightColor,
                    dimColor = dimColor,
                    style = style,
                    shadowAlpha = if (glowEnabled) {
                        emphasisFractions[index] * AM_KARAOKE_EMPHASIS_SHADOW_ALPHA
                    } else {
                        0f
                    },
                    shadowRadiusPx = shadowRadiusPx,
                    rtl = rtl,
                    fadeStartOverride = localFadeStart,
                    fadeEndOverride = localFadeEnd,
                    modifier = Modifier.graphicsLayer {
                        translationY = -wordLiftPx * glyphLift.value
                    },
                    onWidthMeasured = { measuredWidth ->
                        if (abs(glyphWidths[index] - measuredWidth) > 0.5f) {
                            glyphWidths[index] = measuredWidth
                        }
                    },
                )
            }
            if (suffix.isNotEmpty()) {
                ProgressiveLyricText(
                    text = suffix,
                    progress = wordProgress,
                    highlightColor = highlightColor,
                    dimColor = dimColor,
                    style = style,
                    shadowAlpha = 0f,
                    shadowRadiusPx = shadowRadiusPx,
                    rtl = rtl,
                    modifier = Modifier.graphicsLayer {
                        val suffixLift = glyphLiftStates.lastOrNull()?.value ?: 0f
                        translationY = -wordLiftPx * suffixLift
                    },
                )
            }
        },
    )
}

/**
 * Time-based spacing compensation stores half of each glyph's
 * scale-induced width growth; C11983A then walks from the visual centre outward and translates
 * neighbours so scaled glyphs do not collide. The parent width intentionally stays at the
 * unscaled width, matching View scaling + translation rather than relayout.
 */
@Composable
private fun AMGappedGlyphLayout(
    modifier: Modifier,
    scales: List<Float>,
    rtl: Boolean,
    suffixPresent: Boolean,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val emphasisCount = scales.size.coerceAtMost(placeables.size)
        val baselines = placeables.map { placeable ->
            placeable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
        }
        val baseline = baselines.maxOrNull() ?: 0
        val descent = placeables.mapIndexed { index, placeable ->
            placeable.height - baselines[index]
        }.maxOrNull() ?: 0
        val height = baseline + descent
        val originalWidth = placeables.sumOf { it.width }

        val halfGrowth = FloatArray(emphasisCount) { index ->
            ((scales[index] - 1f) * placeables[index].width) / 2f
        }
        val translations = amGlyphSpacingTranslations(halfGrowth, rtl)

        layout(
            width = originalWidth.coerceIn(constraints.minWidth, constraints.maxWidth),
            height = height.coerceIn(constraints.minHeight, constraints.maxHeight),
            alignmentLines = mapOf(FirstBaseline to baseline, LastBaseline to baseline),
        ) {
            var logicalX = 0
            placeables.forEachIndexed { index, placeable ->
                val baseX = if (!rtl) {
                    logicalX
                } else {
                    originalWidth - logicalX - placeable.width
                }
                val translationX = if (index < emphasisCount) translations[index].roundToInt() else {
                    // Trailing separators stay attached to the final visual glyph rather than
                    // becoming a second animated glyph.
                    if (suffixPresent && emphasisCount > 0) translations[emphasisCount - 1].roundToInt() else 0
                }
                val childBaseline = baselines[index]
                val pivotYFraction = if (placeable.height > 0) {
                    (childBaseline.toFloat() / placeable.height.toFloat()).coerceIn(0f, 1f)
                } else {
                    1f
                }
                if (index < emphasisCount) {
                    placeable.placeWithLayer(
                        x = baseX + translationX,
                        y = baseline - childBaseline,
                    ) {
                        scaleX = scales[index]
                        scaleY = scales[index]
                        // C11983A: height - lastBaselineToBottomHeight == last baseline.
                        transformOrigin = TransformOrigin(0.5f, pivotYFraction)
                    }
                } else {
                    placeable.place(
                        x = baseX + translationX,
                        y = baseline - childBaseline,
                    )
                }
                logicalX += placeable.width
            }
        }
    }
}

// ========== Shared progressive text ==========

@Composable
private fun ProgressiveLyricText(
    text: String,
    progress: Float,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    shadowAlpha: Float,
    shadowRadiusPx: Float,
    rtl: Boolean,
    fadeStartOverride: Float? = null,
    fadeEndOverride: Float? = null,
    onWidthMeasured: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var widthPx by remember(text) { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    // 逐字歌词可见时 positionMs 按 16 ms 分发，直接使用权威进度，避免短 tween
    // 对连续目标反复重启造成拖尾。Compose 会按设备 vsync（通常 60/90/120 Hz）重绘。
    val p = progress.coerceIn(0f, 1f)
    val featherPx = with(density) {
        AM_KARAOKE_FEATHER_WIDTH_FALLBACK.toPx() * amKaraokeFeatherScaleForText(text)
    }.coerceAtMost(widthPx)
    val featherFraction = (featherPx / widthPx).coerceIn(0f, 1f)
    val fadeStart = fadeStartOverride ?: (p - featherFraction).coerceIn(0f, 1f)
    val fadeEnd = fadeEndOverride ?: p
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to highlightColor,
            fadeStart to highlightColor,
            fadeEnd to highlightColor.copy(alpha = 0f),
            1f to highlightColor.copy(alpha = 0f),
        ),
        start = if (rtl) Offset(widthPx, 0f) else Offset.Zero,
        end = if (rtl) Offset.Zero else Offset(widthPx, 0f),
    )
    val shadow = if (shadowAlpha > 0.0001f) {
        Shadow(
            // C12217i always uses a white shadow; it is not tinted with the lyric highlight color.
            color = Color.White.copy(alpha = shadowAlpha.coerceIn(0f, AM_KARAOKE_EMPHASIS_SHADOW_ALPHA)),
            offset = Offset.Zero,
            blurRadius = shadowRadiusPx,
        )
    } else {
        null
    }
    val baseStyle = if (shadow != null) style.copy(shadow = shadow) else style

    Layout(
        modifier = modifier,
        content = {
            // Keep the dim layer as the shadow owner. The highlighted layer is drawn on top, so
            // the white bloom remains behind the final karaoke color exactly like setShadowLayer().
            Text(
                text = text,
                color = dimColor,
                style = baseStyle,
                onTextLayout = {
                    val measuredWidth = it.size.width.toFloat().coerceAtLeast(1f)
                    widthPx = measuredWidth
                    onWidthMeasured?.invoke(measuredWidth)
                }
            )
            when {
                p <= 0.001f -> Unit
                p >= 0.999f -> Text(text = text, color = highlightColor, style = style)
                else -> Text(text = text, style = style.copy(brush = brush))
            }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val firstBaseline = placeables.firstOrNull()
            ?.get(FirstBaseline)?.takeIf { it != AlignmentLine.Unspecified } ?: height
        val lastBaseline = placeables.firstOrNull()
            ?.get(LastBaseline)?.takeIf { it != AlignmentLine.Unspecified } ?: firstBaseline
        layout(
            width = width.coerceIn(constraints.minWidth, constraints.maxWidth),
            height = height.coerceIn(constraints.minHeight, constraints.maxHeight),
            alignmentLines = mapOf(FirstBaseline to firstBaseline, LastBaseline to lastBaseline)
        ) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}

// ========== StaticLyricText ==========

@Composable
private fun StaticLyricText(text: String, color: Color, style: TextStyle) {
    Layout(
        content = { Text(text = text, color = color, style = style) }
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val baseline = placeable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
        layout(placeable.width, placeable.height, alignmentLines = mapOf(FirstBaseline to baseline, LastBaseline to baseline)) {
            placeable.placeRelative(0, 0)
        }
    }
}

// ========== BaselineFlowRow ==========

@Composable
private fun BaselineFlowRow(
    modifier: Modifier = Modifier,
    textAlign: TextAlign,
    content: @Composable () -> Unit
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val maxWidth = constraints.maxWidth

        val lines = mutableListOf<List<Placeable>>()
        var currentLine = mutableListOf<Placeable>()
        var currentWidth = 0

        measurables.forEach { measurable ->
            val placeable = measurable.measure(childConstraints)
            if (currentLine.isNotEmpty() && currentWidth + placeable.width > maxWidth) {
                lines += currentLine
                currentLine = mutableListOf()
                currentWidth = 0
            }
            currentLine += placeable
            currentWidth += placeable.width
        }
        if (currentLine.isNotEmpty()) lines += currentLine

        val lineBaselines = mutableListOf<Int>()
        val lineHeights = mutableListOf<Int>()

        lines.forEach { line ->
            val baselines = line.map { pl ->
                val b = pl[FirstBaseline]
                if (b != AlignmentLine.Unspecified) b else pl.height
            }
            val lineBaseline = baselines.maxOrNull() ?: 0
            val lineDescent = line.mapIndexed { i, pl -> pl.height - baselines[i] }.maxOrNull() ?: 0
            lineBaselines += lineBaseline
            lineHeights += lineBaseline + lineDescent
        }

        val maxLineW = lines.maxOfOrNull { line -> line.sumOf { it.width } } ?: 0
        val layoutWidth = if (constraints.maxWidth != Int.MAX_VALUE) {
            constraints.maxWidth.coerceAtLeast(constraints.minWidth)
        } else {
            maxLineW.coerceAtLeast(constraints.minWidth)
        }
        val layoutHeight = lineHeights.sum().coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            var y = 0
            lines.forEachIndexed { lineIdx, line ->
                val baseline = lineBaselines[lineIdx]
                val lineWidth = line.sumOf { it.width }
                var x = when (textAlign) {
                    TextAlign.Center -> ((layoutWidth - lineWidth) / 2).coerceAtLeast(0)
                    TextAlign.End, TextAlign.Right -> (layoutWidth - lineWidth).coerceAtLeast(0)
                    else -> 0
                }
                line.forEach { placeable ->
                    val childBaseline = placeable[FirstBaseline].let {
                        if (it != AlignmentLine.Unspecified) it else placeable.height
                    }
                    placeable.placeRelative(x = x, y = y + baseline - childBaseline)
                    x += placeable.width
                }
                y += lineHeights[lineIdx]
            }
        }
    }
}

// ========== Segment ==========

private sealed interface LyricSegment {
    val text: String
    data class Space(override val text: String) : LyricSegment
    data class Word(override val text: String, val word: LyricWord, val nextWordBeginMs: Long?) : LyricSegment
}

private fun buildLyricSegments(fullText: String, words: List<LyricWord>): List<LyricSegment> {
    return buildTimedLyricSlices(fullText, words).map { slice ->
        val wordIndex = slice.wordIndex
        if (wordIndex == null) {
            LyricSegment.Space(slice.text)
        } else {
            LyricSegment.Word(
                text = slice.text,
                word = words[wordIndex],
                nextWordBeginMs = words.getOrNull(slice.nextWordIndex ?: -1)?.begin
            )
        }
    }
}

internal data class TimedLyricSlice(
    val text: String,
    val wordIndex: Int?,
    val nextWordIndex: Int? = null
)

/**
 * Assign separators and trailing punctuation to the preceding timed word. Providers commonly leave
 * these characters out of word timing, so rendering them as static text makes a few glyphs remain
 * permanently dim even after the line has completed.
 */
internal fun buildTimedLyricSlices(fullText: String, words: List<LyricWord>): List<TimedLyricSlice> {
    if (fullText.isEmpty()) return emptyList()

    data class Match(val wordIndex: Int, val start: Int, val end: Int)

    val matches = mutableListOf<Match>()
    var cursor = 0
    words.forEachIndexed { index, word ->
        val raw = word.text.orEmpty().cleanLyricSegmentText()
        if (raw.isEmpty() || cursor >= fullText.length) return@forEachIndexed

        val exactStart = fullText.indexOf(raw, startIndex = cursor)
        val start = if (exactStart >= 0) exactStart else cursor
        val end = (start + raw.length).coerceAtMost(fullText.length)
        if (end > start) {
            matches += Match(index, start, end)
            cursor = end
        }
    }

    if (matches.isEmpty()) return listOf(TimedLyricSlice(fullText, null))

    return buildList {
        val firstStart = matches.first().start
        if (firstStart > 0) add(TimedLyricSlice(fullText.substring(0, firstStart), null))
        matches.forEachIndexed { matchIndex, match ->
            val next = matches.getOrNull(matchIndex + 1)
            val displayEnd = (next?.start ?: fullText.length).coerceAtLeast(match.end)
            add(
                TimedLyricSlice(
                    text = fullText.substring(match.start, displayEnd),
                    wordIndex = match.wordIndex,
                    nextWordIndex = next?.wordIndex
                )
            )
        }
    }
}

private fun String.cleanLyricSegmentText(): String = filter { ch ->
    ch == '\n' ||
        ch == '\t' ||
        (ch.code >= 0x20 && ch != '\uFFFC' && ch != '\uFFFD' && ch.code !in 0xE000..0xF8FF)
}

// ========== 时间计算 ==========

private data class WordTimingState(
    val progress: Float,
    val beginMs: Long,
    val endMs: Long
)

private fun wordTimingState(
    positionMs: Long, word: LyricWord,
    nextWordBeginMs: Long?, lineEndMs: Long
): WordTimingState {
    val begin = word.begin
    val fallbackEnd = when {
        nextWordBeginMs != null && nextWordBeginMs > begin -> nextWordBeginMs
        lineEndMs > begin -> lineEndMs
        else -> begin + 240L
    }
    val end = when {
        word.end > begin -> word.end
        else -> fallbackEnd
    }.let { candidate ->
        if (nextWordBeginMs != null && nextWordBeginMs > begin) minOf(candidate, nextWordBeginMs)
        else minOf(candidate, lineEndMs)
    }.coerceAtLeast(begin + 1L)

    val duration = (end - begin).coerceAtLeast(1L).toInt()
    val progress = when {
        positionMs < begin -> 0f
        positionMs >= end -> 1f
        else -> ((positionMs - begin).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
    return WordTimingState(progress = progress, beginMs = begin, endMs = end)
}

private fun amEmphasisTargetScale(rawDurationMs: Long): Float {
    val normalized = (
        rawDurationMs.coerceIn(
            AM_KARAOKE_EMPHASIS_MIN_DURATION_MS,
            AM_KARAOKE_EMPHASIS_SCALE_MAX_DURATION_MS,
        ) - AM_KARAOKE_EMPHASIS_MIN_DURATION_MS
        ).toFloat() /
        (AM_KARAOKE_EMPHASIS_SCALE_MAX_DURATION_MS - AM_KARAOKE_EMPHASIS_MIN_DURATION_MS).toFloat()
    return 1f + AM_KARAOKE_EMPHASIS_MAX_SCALE_DELTA * normalized.coerceIn(0f, 1f)
}

private fun amGlyphLiftStartMs(
    rawDurationMs: Long,
    glyphCount: Int,
    glyphIndex: Int,
): Float {
    if (glyphCount <= 0 || glyphIndex <= 0) return 0f
    val rawDuration = rawDurationMs.toFloat().coerceAtLeast(1f)
    val staggerStep = ((AM_KARAOKE_EMPHASIS_STAGGER_FRACTION * rawDuration) / glyphCount.toFloat())
        .coerceAtMost(AM_KARAOKE_EMPHASIS_MAX_STAGGER_MS)
    return staggerStep * glyphIndex
}

private fun amGlyphEmphasisFraction(
    elapsedMs: Float,
    rawDurationMs: Long,
    glyphCount: Int,
    glyphIndex: Int,
): Float {
    if (elapsedMs <= 0f || glyphCount <= 0) return 0f
    val rawDuration = rawDurationMs.toFloat().coerceAtLeast(1f)
    val animatorDuration = rawDurationMs
        .coerceAtMost(AM_KARAOKE_EMPHASIS_ANIMATION_MAX_DURATION_MS)
        .toFloat()
        .coerceAtLeast(1f)
    val growStart = amGlyphLiftStartMs(rawDurationMs, glyphCount, glyphIndex)
    if (elapsedMs < growStart) return 0f

    // C11983A: size6 = rawDuration / (glyphCount / 2f), and return animator starts at
    // currentGlyphStart + size6. Its onAnimationStart cancels the grow animator.
    val returnOffset = rawDuration / (glyphCount.toFloat() / 2f)
    val returnStart = growStart + returnOffset
    val growLinear = ((elapsedMs - growStart) / animatorDuration).coerceIn(0f, 1f)
    val grow = AM_KARAOKE_EMPHASIS_EASING.transform(growLinear)
    if (elapsedMs < returnStart) return grow

    val growAtReturnLinear = ((returnStart - growStart) / animatorDuration).coerceIn(0f, 1f)
    val growAtReturn = AM_KARAOKE_EMPHASIS_EASING.transform(growAtReturnLinear)
    val returnLinear = ((elapsedMs - returnStart) / animatorDuration).coerceIn(0f, 1f)
    val returnEase = AM_KARAOKE_EMPHASIS_EASING.transform(returnLinear)
    return (growAtReturn * (1f - returnEase)).coerceIn(0f, 1f)
}

private fun amGlyphSpacingTranslations(halfGrowth: FloatArray, rtl: Boolean): FloatArray {
    val count = halfGrowth.size
    if (count <= 1) return FloatArray(count)
    val translations = FloatArray(count)
    // Java source uses integer division for odd counts: 5 / 2 -> 2.0f.
    val center = if (count % 2 == 0) (count / 2f) - 0.5f else (count / 2).toFloat()
    val leftStart = if (floor(center) == center) floor(center).toInt() - 1 else floor(center).toInt()
    val rightStart = if (ceil(center) == center) ceil(center).toInt() + 1 else ceil(center).toInt()

    var index = leftStart
    while (index >= 0) {
        var amount = halfGrowth[index]
        val next = index + 1
        if (next.toFloat() <= center && next < count) amount += halfGrowth[next]
        if (next.toFloat() < center && next < count) amount += abs(translations[next])
        translations[index] = (if (rtl) amount else -amount) * 0.5f
        index--
    }

    index = rightStart
    while (index < count) {
        var amount = halfGrowth[index]
        val previous = index - 1
        if (previous.toFloat() >= center && previous >= 0) amount += halfGrowth[previous]
        if (previous.toFloat() > center && previous >= 0) amount += abs(translations[previous])
        translations[index] = (if (rtl) -amount else amount) * 0.5f
        index++
    }
    return translations
}

private fun isAMLongNoteEmphasisEligible(
    text: String,
    durationMs: Long,
    glyphCount: Int,
): Boolean {
    if (text.isBlank()) return false
    if (durationMs < AM_KARAOKE_EMPHASIS_MIN_DURATION_MS) return false
    if (glyphCount <= 0) return false
    // Store UTF-16 String.length so surrogate pairs keep stable timing boundaries.
    if (text.length !in 1..AM_KARAOKE_EMPHASIS_MAX_GLYPHS) return false

    return true
}

private fun shouldSplitAMLongNoteEmphasis(
    text: String,
    glyphs: List<String>,
): Boolean {
    if (glyphs.size <= 1) return false
    val blocks = unicodeBlocksOf(text)
    if (blocks.any { it in AM_KARAOKE_SHAPED_SCRIPT_BLOCKS }) return false
    // CJK/Kana can use a per-character lane once there is more than one visible glyph.
    return true
}

private fun unicodeBlocksOf(text: String): Set<Character.UnicodeBlock> {
    val result = LinkedHashSet<Character.UnicodeBlock>()
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        Character.UnicodeBlock.of(codePoint)?.let(result::add)
        index += Character.charCount(codePoint)
    }
    return result
}

private fun splitGraphemeClusters(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result += text.substring(start, end)
        start = end
        end = iterator.next()
    }
    return result
}

private fun isTextRtl(text: String): Boolean {
    if (text.isBlank()) return false
    return !Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
}
