package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.TextUnit
import com.rawsmusic.core.common.model.rubySyllables
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
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
import kotlin.math.roundToLong

// Keep the karaoke gradient feather width as an explicit local rendering parameter.
// Gradient feather width is 30dp; emphasis shadow radius is 5dp, with peak alpha 0.5.
private val KARAOKE_FEATHER_WIDTH = 30.dp
private val KARAOKE_EMPHASIS_SHADOW_RADIUS = 5.dp
private const val KARAOKE_EMPHASIS_SCALE_MAX_DELTA = 0.14f
private const val KARAOKE_EMPHASIS_GLOW_MAX = 0.5f

// Use source duration for long-note eligibility and scale. The lyric model does not
// expose its emphasis metadata, so duration remains a fallback for scale only.
private const val KARAOKE_EMPHASIS_FALLBACK_MIN_DURATION_MS = 1_000L
private const val KARAOKE_EMPHASIS_FALLBACK_SCALE_MAX_DURATION_MS = 2_000L
private const val KARAOKE_EMPHASIS_ANIMATION_MAX_DURATION_MS = 3_000L
private const val KARAOKE_EMPHASIS_MAX_GLYPHS = 7
private const val KARAOKE_EMPHASIS_STAGGER_FRACTION = 0.4f
private const val KARAOKE_EMPHASIS_MAX_STAGGER_MS = 400f
private val KARAOKE_EMPHASIS_EASING = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

private data class LyricFloatUpRenderSpec(
    val peakLiftPx: Float,
    val textScalePx: Float,
    val segmentIndex: Int,
    val lineGeometry: LyricLineFloatUpGeometry,
    val fallbackPositionMs: Long,
    val lineDispatcher: LyricLineRenderDispatcher?,
    val renderClock: LyricDirectRenderClock?,
    val positionState: State<Long>?,
    val positionFractionState: State<Float>?,
)

private data class DirectKaraokeHighlightRenderSpec(
    val lineDispatcher: LyricLineRenderDispatcher?,
    val clock: LyricDirectRenderClock?,
    val beginMs: Long,
    val endMs: Long,
)

private fun lyricElapsedMs(positionMs: Long, beginMs: Long): Float =
    if (positionMs <= beginMs) 0f else (positionMs - beginMs).toFloat()

private fun karaokeFeatherScaleForText(text: String): Float {
    // reference karaoke word renderer does not use one global feather width. For ordinary karaoke words
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
private val KARAOKE_SHAPED_SCRIPT_BLOCKS = setOf(
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
 * True only when this line still contains a visual owner that is composition-position-driven.
 *
 * Ordinary karaoke words are fully Choreographer/draw-owned and must not receive audio-block
 * position publications as a composable parameter. Background-vocal presence and long-note
 * emphasis still use the legacy composition timeline for now, so those rare rows keep the source
 * position until their owners are migrated separately.
 */
internal fun lyricLineNeedsCompositionPosition(line: IRichLyricLine): Boolean {
    val mainText = line.text.orEmpty()
    val backgroundText = line.secondary.orEmpty()
    val backgroundOnly = mainText.isBlank() && backgroundText.isNotBlank()
    if (!backgroundOnly && backgroundText.isNotBlank()) return true
    val timedWords = if (backgroundOnly) line.secondaryWords.orEmpty() else line.words.orEmpty()
    return timedWords.any { word ->
        val duration = (word.end - word.begin).coerceAtLeast(0L)
        duration >= KARAOKE_EMPHASIS_FALLBACK_MIN_DURATION_MS
    }
}

/**
 * Karaoke lyric line.
 *
 * - sweep progress 直接由共享渲染时钟计算，按显示帧推进羽化和高亮
 * - lift 只上抬：已开始/已完成的单词保持抬升，当前行结束后随整行退出，不再回落
 */
@Composable
internal fun KaraokeLyricLine(
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
    positionState: State<Long>? = null,
    positionFractionState: State<Float>? = null,
    renderClock: LyricDirectRenderClock? = null,
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
        positionState = positionState,
        positionFractionState = positionFractionState,
        renderClock = renderClock,
        modifier = modifier
    )
}

@Composable
internal fun KaraokeTimedText(
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
    positionState: State<Long>? = null,
    positionFractionState: State<Float>? = null,
    renderClock: LyricDirectRenderClock? = null,
    showRuby: Boolean = false,
    rubyFontSize: TextUnit = fontSize * 0.45f,
    modifier: Modifier = Modifier
) = BoxWithConstraints(modifier = modifier) {
    if (words.isEmpty() || text.isBlank()) return@BoxWithConstraints
    val style = LocalTextStyle.current.merge(TextStyle(
        // The flow owns paragraph spacing. A single token must not reserve that gap again.
        localeList = lyricGlyphLocales(text), fontSize = fontSize, lineHeight = fontSize,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        fontWeight = fontWeight, fontFamily = fontFamily,
    ))
    val textMeasurer = rememberTextMeasurer()
    val availableWidth = constraints.maxWidth
    val segments = remember(text, words, style, availableWidth, textMeasurer) {
        buildLyricSegments(text, words).flatMap { segment ->
            if (segment is LyricSegment.Word && segment.word.rubySyllables().isNotEmpty()) listOf(segment)
            else splitWideLyricText(segment.text, availableWidth) { part ->
                textMeasurer.measure(part.trimEnd('\n'), style = style, softWrap = false, maxLines = 1).size.width
            }.map { part ->
                when (segment) {
                    is LyricSegment.Space -> segment.copy(text = part)
                    is LyricSegment.Word -> segment.copy(text = part)
                }
            }
        }
    }
    val lyricLineGeometry = remember(segments, lineEndMs) {
        LyricLineFloatUpGeometry(
            segments.map { segment ->
                when (segment) {
                    is LyricSegment.Word -> {
                        val timing = wordTimingBounds(
                            word = segment.word,
                            nextWordBeginMs = segment.nextWordBeginMs,
                            lineEndMs = lineEndMs,
                        )
                        LyricTimedSlot(
                            beginMs = timing.beginMs.toFloat(),
                            endMs = timing.endMs.toFloat(),
                            timed = true,
                        )
                    }
                    is LyricSegment.Space -> LyricTimedSlot()
                }
            }
        )
    }
    val lineRenderDispatcher = remember(renderClock, lyricLineGeometry) {
        renderClock?.let { clock ->
            LyricLineRenderDispatcher(
                clock = clock,
                lineGeometry = lyricLineGeometry,
            )
        }
    }
    val rubyStyle = style.copy(fontSize = rubyFontSize,
        lineHeight = rubyFontSize, fontWeight = FontWeight.Medium)
    val hasRuby = remember(words, showRuby) { showRuby && words.any { it.rubySyllables().isNotEmpty() } }
    val rowRubyText = remember(words) {
        words.flatMap { it.rubySyllables() }.joinToString("") { it.text }.ifEmpty { "かな" }
    }
    val rubyInk = rememberLyricInkBounds(rowRubyText, rubyStyle)
    val rowBaseInk = rememberLyricInkBounds(text, style)
    val density = LocalDensity.current
    val textScalePx = with(density) { fontSize.toPx() }
    val rubyPeakLiftPx = with(density) {
        LyricFloatUpSpec.peakLiftPx(textScalePx, wordLiftDp.toPx(), LyricFloatUpSpec.ENABLED_PERCENTAGE)
    }

    BaselineFlowRow(
        modifier = Modifier,
        textAlign = textAlign,
        rowSpacing = with(density) { LyricRubySpacingSpec.flowGapPx(
            (lineHeight.toPx() - fontSize.toPx()).roundToInt(),
            if (hasRuby) rubyInk.height + 2.dp.roundToPx() else 0) },
        sequentialWrap = hasRuby,
        wrapTexts = segments.map { it.text },
        onChildPlaced = { index, leftPx, widthPx, lineIndex ->
            lyricLineGeometry.updatePlacement(index, leftPx, widthPx, lineIndex)
        },
    ) {
        segments.forEachIndexed { segmentIndex, segment ->
            when (segment) {
                is LyricSegment.Space -> {
                    StaticLyricText(text = segment.text, color = dimColor, style = style)
                }
                is LyricSegment.Word -> {
                    val timingBounds = wordTimingBounds(
                        word = segment.word,
                        nextWordBeginMs = segment.nextWordBeginMs,
                        lineEndMs = lineEndMs,
                    )
                    // Eligibility uses source duration. Ordinary words clip overlapping timing,
                    // but sustained-word color and bloom must share the original timed interval.
                    val emphasisEndMs = segment.word.end
                        .takeIf { it > timingBounds.beginMs }
                        ?: timingBounds.endMs
                    val coreText = remember(segment.text) { segment.text.trimEnd() }
                    val emphasisEligible = remember(coreText, timingBounds.beginMs, emphasisEndMs) {
                        isAMLongNoteEmphasisEligible(
                            text = coreText,
                            durationMs = (emphasisEndMs - timingBounds.beginMs).coerceAtLeast(1L),
                        )
                    }
                    val directOrdinaryWord = lineRenderDispatcher != null &&
                        !emphasisEligible && abs(wordLiftScale) <= 0.0001f
                    val sweepBounds = timingBounds.copy(endMs = lyricWordSweepEnd(
                        beginMs = timingBounds.beginMs,
                        sourceEndMs = segment.word.end,
                        nextBeginMs = segment.nextWordBeginMs,
                        lineEndMs = lineEndMs,
                        sustained = emphasisEligible,
                    ))

                    val ruby = remember(segment.word, showRuby) {
                        if (showRuby) segment.word.rubySyllables() else emptyList()
                    }
                    val baseContent: @Composable () -> Unit = {
                    if (directOrdinaryWord) {
                        // The common karaoke path is completely position-callback independent.
                        // begin/end, typography and geometry are immutable for the word; both the
                        // highlight sweep and lyric lift read the shared Choreographer clock only in
                        // draw. This lets Compose skip this subtree when PlayerController publishes
                        // another coarse position sample.
                        DirectKaraokeWordGroup(
                            text = segment.text.trimEnd('\n'),
                            wordBeginMs = timingBounds.beginMs,
                            wordEndMs = timingBounds.endMs,
                            highlightColor = highlightColor,
                            dimColor = dimColor,
                            style = style,
                            wordLiftDp = wordLiftDp,
                            liftEnabled = liftEnabled && ruby.isEmpty(),
                            segmentIndex = segmentIndex,
                            lyricLineGeometry = lyricLineGeometry,
                            lineDispatcher = requireNotNull(lineRenderDispatcher),
                        )
                    } else {
                        val timing = wordTimingState(positionMs, sweepBounds)
                        KaraokeWordGroup(
                            text = segment.text.trimEnd('\n'),
                            progress = timing.progress,
                            wordBeginMs = timing.beginMs,
                            wordEndMs = timing.endMs,
                            emphasisEndMs = emphasisEndMs,
                            positionMs = positionMs,
                            isCompleted = timing.progress >= 1f,
                            highlightColor = highlightColor,
                            dimColor = dimColor,
                            style = style,
                            wordLiftDp = wordLiftDp,
                            wordLiftScale = wordLiftScale,
                            glowEnabled = glowEnabled,
                            liftEnabled = liftEnabled && ruby.isEmpty(),
                            segmentIndex = segmentIndex,
                            lyricLineGeometry = lyricLineGeometry,
                            positionState = positionState,
                            positionFractionState = positionFractionState,
                            renderClock = renderClock,
                            lineDispatcher = lineRenderDispatcher,
                        )
                    }
                    }
                    if (ruby.isEmpty()) baseContent() else RubyWordLayout(
                        annotationInk = rubyInk,
                        baseInk = rememberLyricInkBounds(segment.text, style),
                        rowBaseInkTop = rowBaseInk.top,
                        modifier = (if (!liftEnabled) Modifier else if (lineRenderDispatcher != null || renderClock != null) {
                            Modifier.lyricFloatUpDraw(clock = renderClock, lineDispatcher = lineRenderDispatcher,
                                peakLiftPx = rubyPeakLiftPx, textScalePx = textScalePx,
                                segmentIndex = segmentIndex, lineGeometry = lyricLineGeometry)
                        } else Modifier.drawWithContent {
                            val position = positionFractionState?.value ?: (positionState?.value ?: positionMs).toFloat()
                            val lift = lyricLineGeometry.fractionForLocalCenter(segmentIndex,
                                size.width * 0.5f, position, textScalePx)
                            translate(top = -rubyPeakLiftPx * lift) { this@drawWithContent.drawContent() }
                        }).let { motion ->
                            if (liftEnabled) motion.lyricSubpixelTextLayer(paddingPx = rubyPeakLiftPx + 2f) else motion
                        },
                        annotation = {
                            BaselineFlowRow(textAlign = TextAlign.Center, sequentialWrap = true,
                                fillAvailableWidth = false,
                                wrapTexts = ruby.map { it.text }) {
                                ruby.forEach { syllable ->
                                    val begin = syllable.begin ?: timingBounds.beginMs
                                    val end = (syllable.end ?: timingBounds.endMs).coerceAtLeast(begin + 1)
                                    ProgressiveLyricText(
                                        text = syllable.text,
                                        progress = wordTimingState(positionMs,
                                            WordTimingBounds(begin, end)).progress,
                                        highlightColor = highlightColor,
                                        dimColor = dimColor,
                                        style = rubyStyle,
                                        shadowAlpha = 0f,
                                        shadowRadiusPx = 0f,
                                        rtl = isTextRtl(syllable.text),
                                        directHighlightRenderSpec = if (lineRenderDispatcher != null || renderClock != null) {
                                            DirectKaraokeHighlightRenderSpec(lineRenderDispatcher, renderClock, begin, end)
                                        } else null,
                                    )
                                }
                            }
                        },
                        base = baseContent,
                    )
                }
            }
        }
    }
}

/**
 * Geometry-only timed lyric lane used by the VirtualList zoom endpoint measurer.
 *
 * It keeps the exact BaselineFlowRow token/wrap ownership of [KaraokeTimedText], but each token is
 * represented by a single Text node.  No karaoke brush, shadow, float-up clock, or duplicated
 * dim/highlight Text tree is mounted because the layer is alpha=0 and only needs row geometry.
 */
@Composable
internal fun KaraokeTimedMeasureText(
    text: String,
    words: List<LyricWord>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily?,
    textAlign: TextAlign,
    showRuby: Boolean = false,
    rubyFontSize: TextUnit = fontSize * 0.45f,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier = modifier) {
    if (words.isEmpty() || text.isBlank()) return@BoxWithConstraints
    val style = LocalTextStyle.current.merge(TextStyle(
        localeList = lyricGlyphLocales(text),
        fontSize = fontSize,
        lineHeight = fontSize,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        fontWeight = fontWeight,
        fontFamily = fontFamily,
    ))
    val textMeasurer = rememberTextMeasurer()
    val availableWidth = constraints.maxWidth
    val segments = remember(text, words, style, availableWidth, textMeasurer) {
        buildLyricSegments(text, words).flatMap { segment ->
            if (segment is LyricSegment.Word && segment.word.rubySyllables().isNotEmpty()) listOf(segment)
            else splitWideLyricText(segment.text, availableWidth) { part ->
                textMeasurer.measure(part.trimEnd('\n'), style = style, softWrap = false, maxLines = 1).size.width
            }.map { part ->
                when (segment) {
                    is LyricSegment.Space -> segment.copy(text = part)
                    is LyricSegment.Word -> segment.copy(text = part)
                }
            }
        }
    }
    val rubyStyle = style.copy(fontSize = rubyFontSize,
        lineHeight = rubyFontSize, fontWeight = FontWeight.Medium)
    val hasRuby = remember(words, showRuby) { showRuby && words.any { it.rubySyllables().isNotEmpty() } }
    val rowRubyText = remember(words) {
        words.flatMap { it.rubySyllables() }.joinToString("") { it.text }.ifEmpty { "かな" }
    }
    val rubyInk = rememberLyricInkBounds(rowRubyText, rubyStyle)
    val rowBaseInk = rememberLyricInkBounds(text, style)
    BaselineFlowRow(
        modifier = Modifier,
        textAlign = textAlign,
        rowSpacing = with(LocalDensity.current) { LyricRubySpacingSpec.flowGapPx(
            (lineHeight.toPx() - fontSize.toPx()).roundToInt(),
            if (hasRuby) rubyInk.height + 2.dp.roundToPx() else 0) },
        sequentialWrap = hasRuby,
        wrapTexts = segments.map { it.text },
    ) {
        segments.forEach { segment ->
            val ruby = remember(segment, showRuby) {
                if (showRuby && segment is LyricSegment.Word) segment.word.rubySyllables() else emptyList()
            }
            val base: @Composable () -> Unit = {
            StaticLyricText(
                text = segment.text,
                color = Color.Transparent,
                style = style,
            )
            }
            if (ruby.isEmpty()) base() else RubyWordLayout(
                annotationInk = rubyInk,
                baseInk = rememberLyricInkBounds(segment.text, style),
                rowBaseInkTop = rowBaseInk.top,
                annotation = {
                    BaselineFlowRow(textAlign = TextAlign.Center, sequentialWrap = true,
                        fillAvailableWidth = false,
                        wrapTexts = ruby.map { it.text }) {
                        ruby.forEach { syllable -> StaticLyricText(syllable.text, Color.Transparent,
                            rubyStyle) }
                    }
                }, base = base,
            )
        }
    }
}

// ========== Word Group ==========

/** One wrapping unit; expose the base's baseline, not the pronunciation's baseline. */
@Composable
private fun RubyWordLayout(annotationInk: LyricInkBounds, baseInk: LyricInkBounds,
    rowBaseInkTop: Int = baseInk.top,
    modifier: Modifier = Modifier,
    annotation: @Composable () -> Unit, base: @Composable () -> Unit) {
    Layout(modifier = modifier, content = { annotation(); base() }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val annotationPlaceable = measurables[0].measure(loose)
        val basePlaceable = measurables[1].measure(loose)
        val gap = 2.dp.roundToPx()
        val width = maxOf(annotationPlaceable.width, basePlaceable.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val annotationBaseline = annotationPlaceable[FirstBaseline].let {
            if (it == AlignmentLine.Unspecified) annotationPlaceable.height else it
        }
        val baseline = basePlaceable[FirstBaseline].let {
            if (it == AlignmentLine.Unspecified) basePlaceable.height else it
        }
        val annotationLast = annotationPlaceable[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: annotationBaseline
        val baseLast = basePlaceable[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: baseline
        val placement = rubyInkPlacement(annotationBaseline, annotationLast, annotationInk.top, annotationInk.bottom,
            baseline, baseLast, baseInk.top, baseInk.bottom, gap, rowBaseInkTop)
        val height = placement.height
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height, mapOf(FirstBaseline to placement.firstBaseline, LastBaseline to placement.lastBaseline)) {
            annotationPlaceable.placeRelative((width - annotationPlaceable.width) / 2, placement.annotationTop)
            basePlaceable.placeRelative((width - basePlaceable.width) / 2, placement.baseTop)
        }
    }
}

@Composable
private fun DirectKaraokeWordGroup(
    text: String,
    wordBeginMs: Long,
    wordEndMs: Long,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    wordLiftDp: Dp,
    liftEnabled: Boolean,
    segmentIndex: Int,
    lyricLineGeometry: LyricLineFloatUpGeometry,
    lineDispatcher: LyricLineRenderDispatcher,
) {
    val density = LocalDensity.current
    val textScalePx = with(density) { style.fontSize.toPx() }.coerceAtLeast(1f)
    val maxLiftPx = with(density) {
        LyricFloatUpSpec.peakLiftPx(
            textScalePx = textScalePx,
            minimumLiftPx = wordLiftDp.toPx(),
            percentage = if (liftEnabled) {
                LyricFloatUpSpec.ENABLED_PERCENTAGE
            } else {
                LyricFloatUpSpec.DISABLED_PERCENTAGE
            },
        )
    }
    val shadowRadiusPx = with(density) { KARAOKE_EMPHASIS_SHADOW_RADIUS.toPx() }
    val lyricSpec = if (liftEnabled) {
        LyricFloatUpRenderSpec(
            peakLiftPx = maxLiftPx,
            textScalePx = textScalePx,
            segmentIndex = segmentIndex,
            lineGeometry = lyricLineGeometry,
            fallbackPositionMs = wordBeginMs,
            lineDispatcher = lineDispatcher,
            renderClock = null,
            positionState = null,
            positionFractionState = null,
        )
    } else {
        null
    }
    ProgressiveLyricText(
        text = text,
        // Ignored by the direct highlight owner. Keeping a constant value is intentional: the
        // player callback cannot turn into a Text recomposition input for this subtree.
        progress = 0f,
        highlightColor = highlightColor,
        dimColor = dimColor,
        style = style,
        shadowAlpha = 0f,
        shadowRadiusPx = shadowRadiusPx,
        rtl = isTextRtl(text),
        lyricFloatUpRenderSpec = lyricSpec,
        directHighlightRenderSpec = DirectKaraokeHighlightRenderSpec(
            lineDispatcher = lineDispatcher,
            clock = null,
            beginMs = wordBeginMs,
            endMs = wordEndMs.coerceAtLeast(wordBeginMs + 1L),
        ),
    )
}

@Composable
private fun KaraokeWordGroup(
    text: String,
    progress: Float,
    wordBeginMs: Long,
    wordEndMs: Long,
    emphasisEndMs: Long,
    positionMs: Long,
    isCompleted: Boolean,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    wordLiftDp: Dp,
    wordLiftScale: Float,
    glowEnabled: Boolean,
    liftEnabled: Boolean,
    segmentIndex: Int,
    lyricLineGeometry: LyricLineFloatUpGeometry,
    positionState: State<Long>?,
    positionFractionState: State<Float>?,
    renderClock: LyricDirectRenderClock?,
    lineDispatcher: LyricLineRenderDispatcher?,
) {
    val density = LocalDensity.current
    val textScalePx = with(density) { style.fontSize.toPx() }.coerceAtLeast(1f)
    val maxLiftPx = with(density) {
        LyricFloatUpSpec.peakLiftPx(
            textScalePx = textScalePx,
            minimumLiftPx = wordLiftDp.toPx(),
            percentage = if (liftEnabled) {
                LyricFloatUpSpec.ENABLED_PERCENTAGE
            } else {
                LyricFloatUpSpec.DISABLED_PERCENTAGE
            },
        )
    }
    val shadowRadiusPx = with(density) { KARAOKE_EMPHASIS_SHADOW_RADIUS.toPx() }

    val coreText = remember(text) { text.trimEnd() }
    val glyphs = remember(coreText) { splitGraphemeClusters(coreText) }
    val fallbackWordWidthPx = textScalePx * glyphs.size.coerceAtLeast(1)
    var measuredWordWidthPx by remember(
        coreText,
        style.fontSize,
        style.fontWeight,
        style.fontFamily,
        style.letterSpacing,
    ) { mutableFloatStateOf(fallbackWordWidthPx) }
    val emphasisEligible = remember(coreText, wordBeginMs, emphasisEndMs) {
        isAMLongNoteEmphasisEligible(
            text = coreText,
            durationMs = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L),
        )
    }

    // The lift has exact 0/1 plateaus outside the moving ~3x text-scale band. Do not let every
    // timed word in the active line subscribe to the vsync State: doing so invalidates all Text
    // layers every frame even though most of them cannot change visually. Keep only the slot(s)
    // touching the moving band hot. A short time pre-arm covers ordinary player callbacks which
    // may arrive after the exact next-word boundary.
    val coarseSamplingMode = if (liftEnabled) {
        lyricLineGeometry.frameSamplingMode(
            slotIndex = segmentIndex,
            positionMs = positionMs.toFloat(),
            textScalePx = textScalePx,
        )
    } else {
        LyricFrameSamplingMode.STATIC_DOWN
    }
    val preArmNextWord = liftEnabled && !isCompleted && (wordBeginMs - positionMs) in 0L..350L
    val lyricSamplingMode = when {
        coarseSamplingMode == LyricFrameSamplingMode.ANIMATED -> LyricFrameSamplingMode.ANIMATED
        coarseSamplingMode == LyricFrameSamplingMode.STATIC_DOWN && preArmNextWord -> LyricFrameSamplingMode.ANIMATED
        else -> coarseSamplingMode
    }
    val renderClockArmed = lyricSamplingMode == LyricFrameSamplingMode.ANIMATED

    // Only an ANIMATED slot subscribes to the shared direct render clock. STATIC_UP/DOWN slots are
    // fixed graphics layers and therefore generate no per-vsync draw invalidation at all.
    val directLyricOwner = liftEnabled && (lineDispatcher != null || renderClock != null)
    val lyricFloatUpRenderSpec = if (
        directLyricOwner ||
            (renderClockArmed && (positionFractionState != null || positionState != null))
    ) {
        LyricFloatUpRenderSpec(
            peakLiftPx = maxLiftPx,
            textScalePx = textScalePx,
            segmentIndex = segmentIndex,
            lineGeometry = lyricLineGeometry,
            fallbackPositionMs = positionMs,
            lineDispatcher = lineDispatcher.takeIf { directLyricOwner },
            renderClock = renderClock.takeIf { directLyricOwner && lineDispatcher == null },
            positionState = positionState.takeIf { !directLyricOwner },
            positionFractionState = positionFractionState.takeIf { !directLyricOwner },
        )
    } else {
        null
    }
    val emphasisFloatUpRenderSpec = lyricFloatUpRenderSpec ?: if (liftEnabled) {
        LyricFloatUpRenderSpec(
            peakLiftPx = maxLiftPx,
            textScalePx = textScalePx,
            segmentIndex = segmentIndex,
            lineGeometry = lyricLineGeometry,
            fallbackPositionMs = positionMs,
            lineDispatcher = lineDispatcher,
            renderClock = renderClock.takeIf { lineDispatcher == null },
            positionState = positionState,
            positionFractionState = positionFractionState,
        )
    } else null
    val directHighlightRenderSpec = when {
        lineDispatcher != null -> DirectKaraokeHighlightRenderSpec(
            lineDispatcher = lineDispatcher,
            clock = null,
            beginMs = wordBeginMs,
            endMs = wordEndMs.coerceAtLeast(wordBeginMs + 1L),
        )
        renderClock != null -> DirectKaraokeHighlightRenderSpec(
            lineDispatcher = null,
            clock = renderClock,
            beginMs = wordBeginMs,
            endMs = wordEndMs.coerceAtLeast(wordBeginMs + 1L),
        )
        else -> null
    }

    fun currentLineLiftFraction(framePositionMs: Float): Float =
        lyricLineGeometry.fractionForLocalCenter(
            slotIndex = segmentIndex,
            localCenterPx = measuredWordWidthPx.coerceAtLeast(1f) * 0.5f,
            positionMs = framePositionMs,
            textScalePx = textScalePx,
        )

    val splitGlyphBaseModifier = if (abs(wordLiftScale) > 0.0001f) {
        when (lyricSamplingMode) {
            LyricFrameSamplingMode.STATIC_UP -> Modifier.graphicsLayer {
                val legacyScale = 1f + wordLiftScale
                scaleX = legacyScale
                scaleY = legacyScale
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            LyricFrameSamplingMode.ANIMATED -> Modifier.graphicsLayer {
                val framePositionMs = positionFractionState?.value
                    ?: (positionState?.value ?: positionMs).toFloat()
                val liftFraction = currentLineLiftFraction(framePositionMs)
                val legacyScale = 1f + wordLiftScale * liftFraction
                scaleX = legacyScale
                scaleY = legacyScale
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            LyricFrameSamplingMode.STATIC_DOWN -> Modifier
        }
    } else {
        Modifier
    }
    when {
        emphasisEligible && renderClock != null -> {
            val visualParts = remember(coreText) { longNoteVisualParts(coreText) }
            val suffix = text.substring(coreText.length)
            DirectEmphasisWord(
                parts = visualParts + listOfNotNull(suffix.takeIf { it.isNotEmpty() }),
                emphasisCount = visualParts.size,
                clock = renderClock,
                wordBeginMs = wordBeginMs,
                wordEndMs = wordEndMs,
                emphasisEndMs = emphasisEndMs,
                highlightColor = highlightColor,
                dimColor = dimColor,
                style = style,
                glowEnabled = glowEnabled,
                shadowRadiusPx = shadowRadiusPx,
                peakLiftPx = if (liftEnabled) maxLiftPx else 0f,
                textScalePx = textScalePx,
                segmentIndex = segmentIndex,
                geometry = lyricLineGeometry,
            )
        }
        emphasisEligible -> {
            // Keep the source-timed unit shaped as one text run. Providers with actual syllable
            // timing already arrive as multiple LyricWord entries; providers with only word timing
            // stay a single shaped word rather than receiving invented grapheme timing.
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
                onWidthMeasured = { measuredWidth ->
                    if (kotlin.math.abs(measuredWordWidthPx - measuredWidth) > 0.5f) {
                        measuredWordWidthPx = measuredWidth
                    }
                },
                lyricFloatUpRenderSpec = emphasisFloatUpRenderSpec,
            )
        }
        else -> {
            // Keep shaped ordinary words in one text layout. Splitting their cached layer with
            // rectangular clips can shear a glyph at the moving rise boundary.
            val regularModifier = if (directLyricOwner) {
                // The direct DrawModifierNode owns STATIC_UP / ANIMATED / STATIC_DOWN itself and
                // only invalidates while the moving lyric band intersects this word. Do not stack
                // a coarse player-callback graphicsLayer on top of it.
                Modifier
            } else {
                when (lyricSamplingMode) {
                    LyricFrameSamplingMode.ANIMATED -> splitGlyphBaseModifier
                    LyricFrameSamplingMode.STATIC_UP -> Modifier.graphicsLayer {
                        translationY = -maxLiftPx
                        val legacyScale = 1f + wordLiftScale
                        scaleX = legacyScale
                        scaleY = legacyScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    LyricFrameSamplingMode.STATIC_DOWN -> Modifier
                }
            }
            ProgressiveLyricText(
                text = text,
                progress = progress,
                highlightColor = highlightColor,
                dimColor = dimColor,
                style = style,
                shadowAlpha = 0f,
                shadowRadiusPx = shadowRadiusPx,
                rtl = isTextRtl(text),
                onWidthMeasured = { measuredWidth ->
                    if (kotlin.math.abs(measuredWordWidthPx - measuredWidth) > 0.5f) {
                        measuredWordWidthPx = measuredWidth
                    }
                },
                lyricFloatUpRenderSpec = lyricFloatUpRenderSpec,
                directHighlightRenderSpec = directHighlightRenderSpec,
                modifier = regularModifier,
            )
        }
    }
}

/** Shared by all glyph draws in a word; timing and spacing are resolved only once per frame. */
internal class LyricEmphasisDrawFrame(private val count: Int) {
    val fractions = FloatArray(count)
    private val growth = FloatArray(count)
    private val previousWidths = FloatArray(count) { Float.NaN }
    var offsets = FloatArray(count)
        private set
    private var lastPosition = Float.NaN
    private var lastBegin = Long.MIN_VALUE
    private var lastDuration = Long.MIN_VALUE
    private var lastRtl = false

    fun sample(position: Float, begin: Long, duration: Long, widths: List<Float>, rtl: Boolean) {
        val widthsChanged = (0 until count).any { previousWidths[it] != widths[it] }
        if (position == lastPosition && begin == lastBegin && duration == lastDuration &&
            rtl == lastRtl && !widthsChanged) return
        lastPosition = position
        lastBegin = begin
        lastDuration = duration
        lastRtl = rtl
        val elapsed = (position - begin.toFloat()).coerceAtLeast(0f)
        val scaleDelta = lyricEmphasisTargetScale(duration) - 1f
        for (index in 0 until count) {
            previousWidths[index] = widths[index]
            fractions[index] = lyricGlyphEmphasisFraction(elapsed, duration, count, index)
            growth[index] = scaleDelta * fractions[index] * widths[index] * 0.5f
        }
        offsets = lyricGlyphSpacingTranslations(growth, rtl)
    }
}

@Composable
private fun DirectEmphasisWord(
    parts: List<String>,
    emphasisCount: Int,
    clock: LyricDirectRenderClock,
    wordBeginMs: Long,
    wordEndMs: Long,
    emphasisEndMs: Long,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    glowEnabled: Boolean,
    shadowRadiusPx: Float,
    peakLiftPx: Float,
    textScalePx: Float,
    segmentIndex: Int,
    geometry: LyricLineFloatUpGeometry,
) {
    val widths = remember(parts, style) {
        mutableStateListOf<Float>().apply { repeat(parts.size) { add(1f) } }
    }
    val rtl = remember(parts) { isTextRtl(parts.joinToString("")) }
    val duration = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L)
    val targetScale = lyricEmphasisTargetScale(duration)
    val frame = remember(emphasisCount) { LyricEmphasisDrawFrame(emphasisCount) }
    val density = LocalDensity.current
    val feather = with(density) {
        KARAOKE_FEATHER_WIDTH.toPx() * karaokeFeatherScaleForText(parts.joinToString(""))
    }
    // Layout is immutable during playback. All time-dependent transforms and paint read the
    // same display clock inside draw; neither progress callbacks nor vsync remeasure the text.
    AMGappedGlyphLayout(
        modifier = Modifier,
        scales = List(emphasisCount) { 1f },
        rtl = rtl,
        suffixPresent = parts.size > emphasisCount,
    ) {
        parts.forEachIndexed { index, part ->
            var textLayout by remember(part, style) { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = part,
                style = style,
                color = dimColor,
                onTextLayout = { result ->
                    textLayout = result
                    val width = result.size.width.toFloat().coerceAtLeast(1f)
                    if (widths[index] != width) widths[index] = width
                },
                modifier = Modifier
                    .lyricFloatUpDraw(
                        clock = clock,
                        peakLiftPx = peakLiftPx,
                        textScalePx = textScalePx,
                        segmentIndex = segmentIndex,
                        lineGeometry = geometry,
                        wholeLocalCenterPx = (0 until index).sumOf { widths[it].toDouble() }.toFloat() +
                            widths[index] * 0.5f,
                    )
                    .lyricClockDrawInvalidation(clock).drawWithCache {
                    val shaped = textLayout
                    val padding = ceil(shadowRadiusPx.coerceAtLeast(0f) * 2f + 2f).toInt()
                    val paddedSize = IntSize(ceil(size.width).toInt() + padding * 2,
                        ceil(size.height).toInt() + padding * 2)
                    val textLayer = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
                    val glowLayer = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
                    var glowRecorded = false
                    val glowMaskPaint = Paint()
                    onDrawWithContent {
                    if (shaped == null) { drawContent(); return@onDrawWithContent }
                    val position = clock.currentPosition()
                    // The emphasis envelope uses source duration rather than the clipped color sweep.
                    frame.sample(position, wordBeginMs, duration, widths, rtl)
                    val offset = frame.offsets[index.coerceAtMost(emphasisCount - 1)]
                    val fraction = if (index < emphasisCount) frame.fractions[index] else 0f
                    val start = (0 until index).sumOf { widths[it].toDouble() }.toFloat()
                    val total = widths.sum().coerceAtLeast(1f)
                    val zoom = 1f + (targetScale - 1f) * fraction
                    val progress = DirectKaraokeHighlightSpec.progress(position, wordBeginMs, wordEndMs)
                    val sweepFeather = feather.coerceIn(0f, total)
                    val boundary = progress * DirectKaraokeHighlightSpec.sweepDistance(total, sweepFeather) - start
                    val end = (boundary / widths[index]).coerceIn(0f, 1f)
                    val sweepEnd = if (rtl) widths[index] - boundary else boundary
                    val sweepStart = if (rtl) sweepEnd + sweepFeather else sweepEnd - sweepFeather
                    val brush = Brush.linearGradient(
                        colors = listOf(highlightColor, highlightColor.copy(alpha = 0f)),
                        start = Offset(sweepStart, 0f),
                        end = Offset(sweepEnd, 0f),
                    )
                    // Rasterize the blurred mask once at a stable baseline. Frames only change its
                    // opacity and transform; changing the shadow paint every frame defeats caching.
                    if (glowEnabled && fraction > 0f && !glowRecorded) {
                        glowLayer.record(size = paddedSize) {
                            translate(left = padding.toFloat(), top = padding.toFloat()) {
                                drawText(shaped, color = Color.Transparent, shadow = Shadow(
                                    Color.White,
                                    Offset.Zero, shadowRadiusPx))
                            }
                        }
                        glowRecorded = true
                    }
                    textLayer.record(size = paddedSize) {
                        translate(left = padding.toFloat(), top = padding.toFloat()) {
                            // Paragraph paint is reused by all passes. null preserves the shadow
                            // installed while recording glow; None explicitly clears it.
                            drawText(shaped, color = dimColor, shadow = Shadow.None)
                            if (progress >= 1f) drawText(shaped, color = highlightColor, shadow = Shadow.None)
                            else if (end > 0f) drawText(shaped, brush = brush, shadow = Shadow.None)
                        }
                    }
                    translate(left = offset) {
                        scale(zoom, pivot = Offset(size.width * 0.5f, shaped.firstBaseline)) {
                            translate(left = -padding.toFloat(), top = -padding.toFloat()) {
                                if (glowEnabled && fraction > 0f && end > 0f) {
                                    glowLayer.alpha = longNoteGlowAlpha(fraction)
                                    run {
                                        // The cached bloom covers the whole glyph. Mask it with the
                                        // same source-timed sweep, otherwise unread pixels look white
                                        // before the foreground highlight reaches them.
                                        val maskSize = Size(paddedSize.width.toFloat(), paddedSize.height.toFloat())
                                        drawContext.canvas.saveLayer(Rect(Offset.Zero, maskSize), glowMaskPaint)
                                        try {
                                            drawLayer(glowLayer)
                                            drawRect(
                                                brush = Brush.linearGradient(
                                                    colors = listOf(Color.White, Color.Transparent),
                                                    start = Offset(padding + sweepStart, 0f),
                                                    end = Offset(padding + sweepEnd, 0f),
                                                ),
                                                size = maskSize,
                                                blendMode = BlendMode.DstIn,
                                            )
                                        } finally {
                                            drawContext.canvas.restore()
                                        }
                                    }
                                }
                                drawLayer(textLayer)
                            }
                        }
                    }
                    }
                },
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
    lyricFloatUpRenderSpec: LyricFloatUpRenderSpec?,
    onWidthMeasured: ((Float) -> Unit)? = null,
) {
    val rawDurationMs = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L)
    val fraction = lyricGlyphEmphasisFraction(
        // positionMs uses Long.MIN_VALUE for inactive/future lines. Never subtract that sentinel:
        // it can overflow to a huge positive value and make a future emphasis word render at peak
        // glow before the line becomes active.
        elapsedMs = lyricElapsedMs(positionMs, wordBeginMs),
        rawDurationMs = rawDurationMs,
        glyphCount = 1,
        glyphIndex = 0,
    )
    val scale = 1f + (lyricEmphasisTargetScale(rawDurationMs) - 1f) * fraction
    val rtl = remember(text) { isTextRtl(text) }
    AMGappedGlyphLayout(
        modifier = Modifier,
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
            shadowAlpha = if (glowEnabled) {
                longNoteGlowAlpha(fraction)
            } else {
                0f
            },
            shadowRadiusPx = shadowRadiusPx,
            rtl = rtl,
            onWidthMeasured = onWidthMeasured,
            lyricFloatUpRenderSpec = lyricFloatUpRenderSpec,
        )
    }
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
        val translations = lyricGlyphSpacingTranslations(halfGrowth, rtl)

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
    lyricFloatUpRenderSpec: LyricFloatUpRenderSpec? = null,
    directHighlightRenderSpec: DirectKaraokeHighlightRenderSpec? = null,
    modifier: Modifier = Modifier
) {
    var widthPx by remember(
        text,
        style.fontSize,
        style.fontWeight,
        style.fontFamily,
        style.letterSpacing,
    ) { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    // In the direct playback path progress is deliberately NOT a visual input. The player may
    // publish position every 100-220ms; rebuilding a TextStyle/Brush at that cadence stalls the
    // Choreographer-driven Y motion. The solid highlight Text below is masked in draw phase by the
    // same render clock as lyric float-up. Preview/legacy callers keep the old progress brush.
    val p = if (directHighlightRenderSpec == null) progress.coerceIn(0f, 1f) else 0f
    val shadow = if (shadowAlpha > 0.0001f) {
        Shadow(
            // C12217i always uses a white shadow; it is not tinted with the lyric highlight color.
            color = Color.White.copy(alpha = shadowAlpha.coerceIn(0f, KARAOKE_EMPHASIS_GLOW_MAX)),
            offset = Offset.Zero,
            blurRadius = shadowRadiusPx,
        )
    } else {
        null
    }

    val featherPx = with(density) {
        KARAOKE_FEATHER_WIDTH.toPx() * karaokeFeatherScaleForText(text)
    }.coerceAtMost(widthPx)
    val featherFraction = (featherPx / widthPx).coerceIn(0f, 1f)
    val maskStops = DirectKaraokeHighlightSpec.maskStops(p, featherFraction)
    val fadeStart = fadeStartOverride ?: maskStops[1]
    val fadeEnd = fadeEndOverride ?: maskStops[2]
    val brush = if (directHighlightRenderSpec == null) {
        Brush.linearGradient(
            colorStops = arrayOf(
                0f to highlightColor,
                fadeStart to highlightColor,
                fadeEnd to highlightColor.copy(alpha = 0f),
                1f to highlightColor.copy(alpha = 0f),
            ),
            start = if (rtl) Offset(widthPx, 0f) else Offset.Zero,
            end = if (rtl) Offset.Zero else Offset(widthPx, 0f),
        )
    } else {
        null
    }
    val baseStyle = if (shadow != null) style.copy(shadow = shadow) else style
    val floatUpDrawModifier = when {
        lyricFloatUpRenderSpec != null &&
            (lyricFloatUpRenderSpec.lineDispatcher != null || lyricFloatUpRenderSpec.renderClock != null) -> {
            val spec = lyricFloatUpRenderSpec
            Modifier
                .lyricFloatUpDraw(
                    clock = spec.renderClock,
                    lineDispatcher = spec.lineDispatcher,
                    peakLiftPx = spec.peakLiftPx,
                    textScalePx = spec.textScalePx,
                    segmentIndex = spec.segmentIndex,
                    lineGeometry = spec.lineGeometry,
                    // An unsplit shaped run must move as a whole. A horizontal clip can pass
                    // through a glyph's painted pixels and lift only half of that character.
                    wholeLocalCenterPx = widthPx * 0.5f,
                )
                .graphicsLayer {
                    // Choreographer invalidates only the outer draw node.
                    clip = false
                }
                .lyricSubpixelTextLayer(
                    paddingPx = (shadow?.let {
                        it.blurRadius * 2f + maxOf(abs(it.offset.x), abs(it.offset.y))
                    } ?: 0f) + 2f,
                )
        }
        lyricFloatUpRenderSpec != null -> {
            val spec = lyricFloatUpRenderSpec
            Modifier.drawWithContent {
                val framePositionMs = spec.positionFractionState?.value
                    ?: (spec.positionState?.value ?: spec.fallbackPositionMs).toFloat()
                val liftFraction = spec.lineGeometry.fractionForLocalCenter(
                    slotIndex = spec.segmentIndex,
                    localCenterPx = widthPx * 0.5f,
                    positionMs = framePositionMs,
                    textScalePx = spec.textScalePx,
                )
                translate(top = -spec.peakLiftPx * liftFraction) {
                    this@drawWithContent.drawContent()
                }
            }.graphicsLayer { clip = false }
        }
        else -> Modifier
    }

    Layout(
        modifier = modifier.then(floatUpDrawModifier),
        content = {
            // Keep the dim layer as the shadow owner. The highlighted layer is drawn on top, so
            // the white bloom remains behind the final karaoke color exactly like setShadowLayer().
            Text(
                text = text,
                softWrap = true,
                color = dimColor,
                style = baseStyle,
                onTextLayout = {
                    val measuredWidth = it.size.width.toFloat().coerceAtLeast(1f)
                    widthPx = measuredWidth
                    onWidthMeasured?.invoke(measuredWidth)
                }
            )
            // Keep both layers mounted at 0%, 100% and every intermediate progress value. Only
            // the paint changes; switching between a one-Text and two-Text tree at a line boundary
            // was causing Compose to measure the karaoke row again during the lyric handoff.
            val directHighlight = directHighlightRenderSpec
            Text(
                text = text,
                softWrap = true,
                modifier = if (directHighlight != null) {
                    Modifier.directKaraokeHighlight(
                        clock = directHighlight.clock,
                        lineDispatcher = directHighlight.lineDispatcher,
                        beginMs = directHighlight.beginMs,
                        endMs = directHighlight.endMs,
                        featherPx = featherPx,
                        rtl = rtl,
                    )
                } else {
                    Modifier
                },
                color = when {
                    directHighlight != null -> highlightColor
                    p >= 1f -> highlightColor
                    else -> Color.Transparent
                },
                style = when {
                    directHighlight != null -> style
                    p >= 1f -> style
                    else -> style.copy(brush = requireNotNull(brush))
                },
            )
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

/** Small padded texture inside the retained text layer; float-up never changes text layout. */
private fun Modifier.lyricSubpixelTextLayer(paddingPx: Float): Modifier = drawWithCache {
    val padding = ceil(paddingPx.coerceAtLeast(0f)).toInt()
    val layer = obtainGraphicsLayer().apply {
        // A display list alone still rasterizes glyphs at the translated baseline. Rasterize at
        // a stable origin, then let the outer float-up node translate the texture fractionally.
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val paddedSize = IntSize(
        ceil(size.width).toInt() + padding * 2,
        ceil(size.height).toInt() + padding * 2,
    )
    onDrawWithContent {
        layer.record(size = paddedSize) {
            translate(left = padding.toFloat(), top = padding.toFloat()) {
                this@onDrawWithContent.drawContent()
            }
        }
        translate(left = -padding.toFloat(), top = -padding.toFloat()) { drawLayer(layer) }
    }
}

// ========== StaticLyricText ==========

@Composable
private fun StaticLyricText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = { Text(text = text.trimEnd('\n'), color = color, style = style, softWrap = true) }
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val baseline = placeable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
        val lastBaseline = placeable[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: baseline
        layout(placeable.width, placeable.height, alignmentLines = mapOf(FirstBaseline to baseline, LastBaseline to lastBaseline)) {
            placeable.placeRelative(0, 0)
        }
    }
}

// ========== BaselineFlowRow ==========

@Composable
private fun BaselineFlowRow(
    modifier: Modifier = Modifier,
    textAlign: TextAlign,
    wrapTexts: List<String> = emptyList(),
    sequentialWrap: Boolean = false,
    fillAvailableWidth: Boolean = true,
    rowSpacing: Int = 0,
    onChildPlaced: ((index: Int, leftPx: Float, widthPx: Float, lineIndex: Int) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val maxWidth = constraints.maxWidth

        val placeables = measurables.map { it.measure(childConstraints) }
        val wrapTokens = placeables.mapIndexed { index, placeable ->
            val text = wrapTexts.getOrNull(index).orEmpty()
            LyricWrapToken(
                text = text,
                widthPx = placeable.width,
                isSpace = text.isNotEmpty() && text.isBlank(),
            )
        }
        val breaks = if (sequentialWrap) flowLyricBreaks(wrapTokens, maxWidth)
            else balancedLyricBreaks(wrapTokens, maxWidth)
        val lines = mutableListOf<List<Pair<Int, Placeable>>>()
        var currentLine = mutableListOf<Pair<Int, Placeable>>()
        placeables.forEachIndexed { index, placeable ->
            currentLine += index to placeable
            if (index + 1 == placeables.size || index + 1 in breaks) {
                lines += currentLine
                currentLine = mutableListOf()
            }
        }

        val lineBaselines = mutableListOf<Int>()
        val lineHeights = mutableListOf<Int>()

        lines.forEach { line ->
            val baselines = line.map { (_, pl) ->
                val b = pl[FirstBaseline]
                if (b != AlignmentLine.Unspecified) b else pl.height
            }
            val lineBaseline = baselines.maxOrNull() ?: 0
            val lineDescent = line.mapIndexed { i, (_, pl) -> pl.height - baselines[i] }.maxOrNull() ?: 0
            lineBaselines += lineBaseline
            lineHeights += lineBaseline + lineDescent
        }

        val maxLineW = lines.maxOfOrNull { line -> line.sumOf { it.second.width } } ?: 0
        val layoutWidth = if (fillAvailableWidth && constraints.maxWidth != Int.MAX_VALUE) {
            constraints.maxWidth.coerceAtLeast(constraints.minWidth)
        } else {
            maxLineW.coerceIn(constraints.minWidth, constraints.maxWidth)
        }
        val layoutHeight = (lineHeights.sum() + rowSpacing * (lines.size - 1).coerceAtLeast(0))
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        val firstBaseline = lineBaselines.firstOrNull() ?: 0
        val lastBaseline = if (lines.isEmpty()) firstBaseline else
            lineHeights.dropLast(1).sum() + rowSpacing * (lines.size - 1) + lineBaselines.last()
        layout(layoutWidth, layoutHeight, mapOf(FirstBaseline to firstBaseline, LastBaseline to lastBaseline)) {
            var y = 0
            lines.forEachIndexed { lineIdx, line ->
                val baseline = lineBaselines[lineIdx]
                val lineWidth = line.sumOf { it.second.width }
                var x = when (textAlign) {
                    TextAlign.Center -> ((layoutWidth - lineWidth) / 2).coerceAtLeast(0)
                    TextAlign.End, TextAlign.Right -> (layoutWidth - lineWidth).coerceAtLeast(0)
                    else -> 0
                }
                line.forEach { (childIndex, placeable) ->
                    val childBaseline = placeable[FirstBaseline].let {
                        if (it != AlignmentLine.Unspecified) it else placeable.height
                    }
                    onChildPlaced?.invoke(
                        childIndex,
                        x.toFloat(),
                        placeable.width.toFloat(),
                        lineIdx,
                    )
                    placeable.placeRelative(x = x, y = y + baseline - childBaseline)
                    x += placeable.width
                }
                y += lineHeights[lineIdx] + rowSpacing
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

private data class WordTimingBounds(
    val beginMs: Long,
    val endMs: Long,
)

private fun wordTimingBounds(
    word: LyricWord,
    nextWordBeginMs: Long?,
    lineEndMs: Long,
): WordTimingBounds {
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

    return WordTimingBounds(beginMs = begin, endMs = end)
}

private fun wordTimingState(positionMs: Long, bounds: WordTimingBounds): WordTimingState {
    val duration = (bounds.endMs - bounds.beginMs).coerceAtLeast(1L).toInt()
    val progress = when {
        positionMs < bounds.beginMs -> 0f
        positionMs >= bounds.endMs -> 1f
        else -> ((positionMs - bounds.beginMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
    return WordTimingState(
        progress = progress,
        beginMs = bounds.beginMs,
        endMs = bounds.endMs,
    )
}

private fun wordTimingState(
    positionMs: Long,
    word: LyricWord,
    nextWordBeginMs: Long?,
    lineEndMs: Long,
): WordTimingState = wordTimingState(
    positionMs = positionMs,
    bounds = wordTimingBounds(word, nextWordBeginMs, lineEndMs),
)

private fun lyricFallbackEmphasisFactor(rawDurationMs: Long): Float =
    (
        rawDurationMs.coerceIn(
            KARAOKE_EMPHASIS_FALLBACK_MIN_DURATION_MS,
            KARAOKE_EMPHASIS_FALLBACK_SCALE_MAX_DURATION_MS,
        ) - KARAOKE_EMPHASIS_FALLBACK_MIN_DURATION_MS
    ).toFloat()
        .div(
            (KARAOKE_EMPHASIS_FALLBACK_SCALE_MAX_DURATION_MS -
                KARAOKE_EMPHASIS_FALLBACK_MIN_DURATION_MS).toFloat()
        )
        .coerceIn(0f, 1f)

private fun lyricEmphasisTargetScale(rawDurationMs: Long): Float =
    1f + KARAOKE_EMPHASIS_SCALE_MAX_DELTA * lyricFallbackEmphasisFactor(rawDurationMs)

internal fun longNoteGlowAlpha(
    emphasisFraction: Float,
): Float = KARAOKE_EMPHASIS_GLOW_MAX *
    emphasisFraction.coerceIn(0f, 1f)

private fun lyricGlyphLiftStartMs(
    rawDurationMs: Long,
    glyphCount: Int,
    glyphIndex: Int,
): Float {
    if (glyphCount <= 0 || glyphIndex <= 0) return 0f
    val rawDuration = rawDurationMs.toFloat().coerceAtLeast(1f)
    val staggerStep = ((KARAOKE_EMPHASIS_STAGGER_FRACTION * rawDuration) / glyphCount.toFloat())
        .coerceAtMost(KARAOKE_EMPHASIS_MAX_STAGGER_MS)
    return staggerStep * glyphIndex
}

private fun lyricGlyphEmphasisFraction(
    elapsedMs: Float,
    rawDurationMs: Long,
    glyphCount: Int,
    glyphIndex: Int,
): Float {
    if (elapsedMs <= 0f || glyphCount <= 0) return 0f
    val rawDuration = rawDurationMs.toFloat().coerceAtLeast(1f)
    val animatorDuration = rawDurationMs
        .coerceAtMost(KARAOKE_EMPHASIS_ANIMATION_MAX_DURATION_MS)
        .toFloat()
        .coerceAtLeast(1f)
    val growStart = lyricGlyphLiftStartMs(rawDurationMs, glyphCount, glyphIndex)
    if (elapsedMs < growStart) return 0f

    // Start the second animator after 2*duration/glyphCount, with the same duration.
    // Its update listener captures the current scale/shadow and eases them back to zero.
    val returnStart = growStart + rawDuration * 2f / glyphCount.toFloat()
    val growLinear = ((elapsedMs - growStart) / animatorDuration).coerceIn(0f, 1f)
    val grow = KARAOKE_EMPHASIS_EASING.transform(growLinear)
    if (elapsedMs < returnStart) return grow

    val growAtReturnLinear = ((returnStart - growStart) / animatorDuration).coerceIn(0f, 1f)
    val growAtReturn = KARAOKE_EMPHASIS_EASING.transform(growAtReturnLinear)
    val returnLinear = ((elapsedMs - returnStart) / animatorDuration).coerceIn(0f, 1f)
    val returnEase = KARAOKE_EMPHASIS_EASING.transform(returnLinear)
    return (growAtReturn * (1f - returnEase)).coerceIn(0f, 1f)
}

private fun lyricGlyphSpacingTranslations(halfGrowth: FloatArray, rtl: Boolean): FloatArray {
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
): Boolean {
    if (text.isBlank()) return false
    return durationMs >= KARAOKE_EMPHASIS_FALLBACK_MIN_DURATION_MS &&
        splitGraphemeClusters(text).size in 1..KARAOKE_EMPHASIS_MAX_GLYPHS
}

internal fun longNoteVisualParts(text: String): List<String> {
    val glyphs = splitGraphemeClusters(text)
    if (glyphs.size !in 2..KARAOKE_EMPHASIS_MAX_GLYPHS) return listOf(text)
    var offset = 0
    while (offset < text.length) {
        val codePoint = Character.codePointAt(text, offset)
        if (Character.UnicodeBlock.of(codePoint) in KARAOKE_SHAPED_SCRIPT_BLOCKS) {
            return listOf(text)
        }
        offset += Character.charCount(codePoint)
    }
    return glyphs
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
