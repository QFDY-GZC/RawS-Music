package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
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
import kotlin.math.roundToLong

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
 * True only when this line still contains a visual owner that is composition-position-driven.
 *
 * Ordinary karaoke words are fully Choreographer/draw-owned and must not receive audio-block
 * position publications as a composable parameter. Background-vocal presence and AM long-note
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
        duration >= AM_KARAOKE_EMPHASIS_MIN_DURATION_MS
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
    modifier: Modifier = Modifier
) {
    if (words.isEmpty() || text.isBlank()) return
    val segments = remember(text, words) { buildLyricSegments(text, words) }
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
    val style = TextStyle(
        fontSize = fontSize, lineHeight = lineHeight,
        fontWeight = fontWeight, fontFamily = fontFamily
    )

    BaselineFlowRow(
        modifier = modifier,
        textAlign = textAlign,
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
                    // Use LyricsWord.getDuration() for long-note emphasis. Keep that raw word
                    // duration separate from RawSMusic's sweep end, which may be clipped to the
                    // next word begin to avoid karaoke-color overlap.
                    val emphasisEndMs = segment.word.end
                        .takeIf { it > timingBounds.beginMs }
                        ?: timingBounds.endMs
                    val coreText = remember(segment.text) { segment.text.trimEnd() }
                    val glyphCount = remember(coreText) { splitGraphemeClusters(coreText).size }
                    val emphasisEligible = remember(coreText, timingBounds.beginMs, emphasisEndMs, glyphCount) {
                        isAMLongNoteEmphasisEligible(
                            text = coreText,
                            durationMs = (emphasisEndMs - timingBounds.beginMs).coerceAtLeast(1L),
                            glyphCount = glyphCount,
                        )
                    }
                    val directOrdinaryWord = lineRenderDispatcher != null &&
                        !emphasisEligible && abs(wordLiftScale) <= 0.0001f

                    if (directOrdinaryWord) {
                        // The common karaoke path is completely position-callback independent.
                        // begin/end, typography and geometry are immutable for the word; both the
                        // highlight sweep and lyric lift read the shared Choreographer clock only in
                        // draw. This lets Compose skip this subtree when PlayerController publishes
                        // another coarse position sample.
                        DirectKaraokeWordGroup(
                            text = segment.text,
                            wordBeginMs = timingBounds.beginMs,
                            wordEndMs = timingBounds.endMs,
                            highlightColor = highlightColor,
                            dimColor = dimColor,
                            style = style,
                            wordLiftDp = wordLiftDp,
                            liftEnabled = liftEnabled,
                            segmentIndex = segmentIndex,
                            lyricLineGeometry = lyricLineGeometry,
                            lineDispatcher = requireNotNull(lineRenderDispatcher),
                        )
                    } else {
                        val timing = wordTimingState(positionMs, timingBounds)
                        KaraokeWordGroup(
                            text = segment.text,
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
                            liftEnabled = liftEnabled,
                            segmentIndex = segmentIndex,
                            lyricLineGeometry = lyricLineGeometry,
                            positionState = positionState,
                            positionFractionState = positionFractionState,
                            renderClock = renderClock,
                            lineDispatcher = lineRenderDispatcher,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Geometry-only timed lyric lane used by the PowerList zoom endpoint measurer.
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
    modifier: Modifier = Modifier,
) {
    if (words.isEmpty() || text.isBlank()) return
    val segments = remember(text, words) { buildLyricSegments(text, words) }
    val style = TextStyle(
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
    )
    BaselineFlowRow(
        modifier = modifier,
        textAlign = textAlign,
        wrapTexts = segments.map { it.text },
    ) {
        segments.forEach { segment ->
            StaticLyricText(
                text = segment.text,
                color = Color.Transparent,
                style = style,
            )
        }
    }
}

// ========== Word Group ==========

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
    val shadowRadiusPx = with(density) { AM_KARAOKE_EMPHASIS_SHADOW_RADIUS_FALLBACK.toPx() }
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
    val shadowRadiusPx = with(density) { AM_KARAOKE_EMPHASIS_SHADOW_RADIUS_FALLBACK.toPx() }

    val coreText = remember(text) { text.trimEnd() }
    val suffix = remember(text, coreText) { text.substring(coreText.length) }
    val glyphs = remember(coreText) { splitGraphemeClusters(coreText) }
    val fallbackWordWidthPx = textScalePx * glyphs.size.coerceAtLeast(1)
    var measuredWordWidthPx by remember(
        coreText,
        style.fontSize,
        style.fontWeight,
        style.fontFamily,
        style.letterSpacing,
    ) { mutableFloatStateOf(fallbackWordWidthPx) }
    val emphasisEligible = remember(coreText, wordBeginMs, emphasisEndMs, glyphs) {
        isAMLongNoteEmphasisEligible(
            text = coreText,
            durationMs = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L),
            glyphCount = glyphs.size,
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
    val shapedEmphasisModifier = when (lyricSamplingMode) {
        LyricFrameSamplingMode.STATIC_UP -> Modifier.graphicsLayer {
            translationY = -maxLiftPx
            val legacyScale = 1f + wordLiftScale
            scaleX = legacyScale
            scaleY = legacyScale
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
        LyricFrameSamplingMode.ANIMATED -> if (
            (lineDispatcher != null || renderClock != null) && abs(wordLiftScale) <= 0.0001f
        ) {
            // Direct draw invalidation. Ordinary lines use one line dispatcher; legacy/emphasis
            // callers may still fall back to the surface clock.
            Modifier.lyricFloatUpDraw(
                clock = renderClock.takeIf { lineDispatcher == null },
                lineDispatcher = lineDispatcher,
                peakLiftPx = maxLiftPx,
                textScalePx = textScalePx,
                segmentIndex = segmentIndex,
                lineGeometry = lyricLineGeometry,
                wholeLocalCenterPx = measuredWordWidthPx.coerceAtLeast(1f) * 0.5f,
            )
        } else {
            Modifier.graphicsLayer {
                val framePositionMs = positionFractionState?.value
                    ?: (positionState?.value ?: positionMs).toFloat()
                val liftFraction = currentLineLiftFraction(framePositionMs)
                translationY = -maxLiftPx * liftFraction
                val legacyScale = 1f + wordLiftScale * liftFraction
                scaleX = legacyScale
                scaleY = legacyScale
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
        }
        LyricFrameSamplingMode.STATIC_DOWN -> Modifier
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
                textScalePx = textScalePx,
                liftEnabled = liftEnabled,
                lyricSamplingMode = lyricSamplingMode,
                segmentIndex = segmentIndex,
                lyricLineGeometry = lyricLineGeometry,
                positionState = positionState.takeIf {
                    renderClockArmed && renderClock == null && lineDispatcher == null
                },
                positionFractionState = positionFractionState.takeIf {
                    renderClockArmed && renderClock == null && lineDispatcher == null
                },
                renderClock = renderClock.takeIf { renderClockArmed && lineDispatcher == null },
                lineDispatcher = lineDispatcher.takeIf { renderClockArmed },
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
                onWidthMeasured = { measuredWidth ->
                    if (kotlin.math.abs(measuredWordWidthPx - measuredWidth) > 0.5f) {
                        measuredWordWidthPx = measuredWidth
                    }
                },
                modifier = shapedEmphasisModifier,
            )
        }
        else -> {
            // The lyric lift keeps one shaped text layout and applies a float-up value to each glyph centre.
            // Do the same in draw phase: no Animatable/coroutine and no per-glyph Compose Text
            // nodes. Completed/future words stay on the cheap single-layer path; only the active
            // word samples the moving spatial cursor.
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
    onWidthMeasured: ((Float) -> Unit)? = null,
) {
    val rawDurationMs = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L)
    val fraction = amGlyphEmphasisFraction(
        elapsedMs = lyricElapsedMs(positionMs, wordBeginMs),
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
            onWidthMeasured = onWidthMeasured,
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
    textScalePx: Float,
    liftEnabled: Boolean,
    lyricSamplingMode: LyricFrameSamplingMode,
    segmentIndex: Int,
    lyricLineGeometry: LyricLineFloatUpGeometry,
    modifier: Modifier = Modifier,
    positionState: State<Long>? = null,
    positionFractionState: State<Float>? = null,
    renderClock: LyricDirectRenderClock? = null,
    lineDispatcher: LyricLineRenderDispatcher? = null,
) {
    val rawDurationMs = (emphasisEndMs - wordBeginMs).coerceAtLeast(1L)
    val elapsedMs = lyricElapsedMs(positionMs, wordBeginMs)
    val glyphCount = glyphs.size.coerceAtLeast(1)
    val density = LocalDensity.current
    val featherPx = with(density) {
        AM_KARAOKE_FEATHER_WIDTH_FALLBACK.toPx() *
            amKaraokeFeatherScaleForText(glyphs.joinToString(separator = ""))
    }
    val glyphWidths = remember(glyphs) {
        mutableStateListOf<Float>().apply { repeat(glyphCount) { add(1f) } }
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
    val cumulativeGlyphWidths = FloatArray(glyphCount + 1)
    for (index in 0 until glyphCount) {
        cumulativeGlyphWidths[index + 1] = cumulativeGlyphWidths[index] + glyphWidths[index]
    }
    val totalGlyphWidth = cumulativeGlyphWidths[glyphCount].coerceAtLeast(1f)
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
                val glyphStart = cumulativeGlyphWidths[index]
                val glyphWidth = glyphWidths[index].coerceAtLeast(1f)
                val boundary = wordProgress.coerceIn(0f, 1f) * totalGlyphWidth
                val localProgress = ((boundary - glyphStart) / glyphWidth).coerceIn(0f, 1f)
                val localFadeStart = ((boundary - featherPx - glyphStart) / glyphWidth).coerceIn(0f, 1f)
                val localFadeEnd = ((boundary - glyphStart) / glyphWidth).coerceIn(0f, 1f)
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
                     modifier = when (lyricSamplingMode) {
                         LyricFrameSamplingMode.STATIC_UP -> Modifier.graphicsLayer {
                             translationY = -wordLiftPx
                         }
                         LyricFrameSamplingMode.ANIMATED -> if ((lineDispatcher != null || renderClock != null) && liftEnabled) {
                             Modifier.lyricFloatUpDraw(
                                 clock = renderClock.takeIf { lineDispatcher == null },
                                 lineDispatcher = lineDispatcher,
                                 peakLiftPx = wordLiftPx,
                                 textScalePx = textScalePx,
                                 segmentIndex = segmentIndex,
                                 lineGeometry = lyricLineGeometry,
                                 wholeLocalCenterPx = glyphStart + glyphWidth * 0.5f,
                             )
                         } else {
                             Modifier.graphicsLayer {
                                 val framePositionMs = positionFractionState?.value
                                     ?: (positionState?.value ?: positionMs).toFloat()
                                 val glyphCenterPx = glyphStart + glyphWidth * 0.5f
                                 val glyphLiftFraction = if (liftEnabled) {
                                     lyricLineGeometry.fractionForLocalCenter(
                                         slotIndex = segmentIndex,
                                         localCenterPx = glyphCenterPx,
                                         positionMs = framePositionMs,
                                         textScalePx = textScalePx,
                                     )
                                 } else {
                                     0f
                                 }
                                 translationY = -wordLiftPx * glyphLiftFraction
                             }
                         }
                         LyricFrameSamplingMode.STATIC_DOWN -> Modifier
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
                    modifier = when (lyricSamplingMode) {
                        LyricFrameSamplingMode.STATIC_UP -> Modifier.graphicsLayer {
                            translationY = -wordLiftPx
                        }
                        LyricFrameSamplingMode.ANIMATED -> if ((lineDispatcher != null || renderClock != null) && liftEnabled) {
                            Modifier.lyricFloatUpDraw(
                                clock = renderClock.takeIf { lineDispatcher == null },
                                lineDispatcher = lineDispatcher,
                                peakLiftPx = wordLiftPx,
                                textScalePx = textScalePx,
                                segmentIndex = segmentIndex,
                                lineGeometry = lyricLineGeometry,
                                wholeLocalCenterPx = totalGlyphWidth,
                            )
                        } else {
                            Modifier.graphicsLayer {
                                val framePositionMs = positionFractionState?.value
                                    ?: (positionState?.value ?: positionMs).toFloat()
                                val suffixLiftFraction = if (liftEnabled) {
                                    lyricLineGeometry.fractionForLocalCenter(
                                        slotIndex = segmentIndex,
                                        localCenterPx = totalGlyphWidth,
                                        positionMs = framePositionMs,
                                        textScalePx = textScalePx,
                                    )
                                } else {
                                    0f
                                }
                                translationY = -wordLiftPx * suffixLiftFraction
                            }
                        }
                        LyricFrameSamplingMode.STATIC_DOWN -> Modifier
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
    var lyricGlyphSlices by remember(
        text,
        style.fontSize,
        style.fontWeight,
        style.fontFamily,
        style.letterSpacing,
        rtl,
    ) { mutableStateOf(emptyList<LyricGlyphSliceSpec>()) }
    val density = LocalDensity.current

    // In the direct playback path progress is deliberately NOT a visual input. The player may
    // publish position every 100-220ms; rebuilding a TextStyle/Brush at that cadence stalls the
    // Choreographer-driven Y motion. The solid highlight Text below is masked in draw phase by the
    // same render clock as lyric float-up. Preview/legacy callers keep the old progress brush.
    val p = if (directHighlightRenderSpec == null) progress.coerceIn(0f, 1f) else 0f
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

    val featherPx = with(density) {
        AM_KARAOKE_FEATHER_WIDTH_FALLBACK.toPx() * amKaraokeFeatherScaleForText(text)
    }.coerceAtMost(widthPx)
    val featherFraction = (featherPx / widthPx).coerceIn(0f, 1f)
    val fadeStart = fadeStartOverride ?: (p - featherFraction).coerceIn(0f, 1f)
    val fadeEnd = fadeEndOverride ?: p
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
            val singleGlyphCenter = lyricGlyphSlices.singleOrNull()?.logicalCenterPx
            Modifier
                .lyricFloatUpDraw(
                    clock = spec.renderClock,
                    lineDispatcher = spec.lineDispatcher,
                    peakLiftPx = spec.peakLiftPx,
                    textScalePx = spec.textScalePx,
                    segmentIndex = spec.segmentIndex,
                    lineGeometry = spec.lineGeometry,
                    // A one-grapheme timed token is already one shaped draw unit. Replaying it
                    // through a clip rect buys nothing and is the common CJK per-character path.
                    slices = if (singleGlyphCenter == null) lyricGlyphSlices else emptyList(),
                    wholeLocalCenterPx = singleGlyphCenter,
                )
                .graphicsLayer {
                    // The DrawModifierNode replays this cached shaped-text layer through the small
                    // number of lyric clip runs. Choreographer invalidates only the outer draw node.
                    clip = false
                }
        }
        lyricFloatUpRenderSpec != null -> {
            val spec = lyricFloatUpRenderSpec
            Modifier.drawWithContent {
                val slices = lyricGlyphSlices
                val framePositionMs = spec.positionFractionState?.value
                    ?: (spec.positionState?.value ?: spec.fallbackPositionMs).toFloat()
                if (slices.isEmpty()) {
                    this@drawWithContent.drawContent()
                } else {
                    var runLeft = slices.first().leftPx
                    var runRight = slices.first().rightPx
                    var runLiftFraction = spec.lineGeometry.fractionForLocalCenter(
                        slotIndex = spec.segmentIndex,
                        localCenterPx = slices.first().logicalCenterPx,
                        positionMs = framePositionMs,
                        textScalePx = spec.textScalePx,
                    )

                    fun drawRun(left: Float, right: Float, liftFraction: Float) {
                        clipRect(
                            left = left,
                            top = -spec.peakLiftPx - 1f,
                            right = right,
                            bottom = size.height + 1f,
                        ) {
                            translate(top = -spec.peakLiftPx * liftFraction) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    }

                    for (index in 1 until slices.size) {
                        val slice = slices[index]
                        val liftFraction = spec.lineGeometry.fractionForLocalCenter(
                            slotIndex = spec.segmentIndex,
                            localCenterPx = slice.logicalCenterPx,
                            positionMs = framePositionMs,
                            textScalePx = spec.textScalePx,
                        )
                        if (liftFraction == runLiftFraction) {
                            runRight = slice.rightPx
                        } else {
                            drawRun(runLeft, runRight, runLiftFraction)
                            runLeft = slice.leftPx
                            runRight = slice.rightPx
                            runLiftFraction = liftFraction
                        }
                    }
                    drawRun(runLeft, runRight, runLiftFraction)
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
                color = dimColor,
                style = baseStyle,
                onTextLayout = {
                    val measuredWidth = it.size.width.toFloat().coerceAtLeast(1f)
                    widthPx = measuredWidth
                    onWidthMeasured?.invoke(measuredWidth)
                    if (lyricFloatUpRenderSpec != null) {
                        val slices = resolveLyricGlyphSlices(text, it, rtl)
                        if (lyricGlyphSlices != slices) lyricGlyphSlices = slices
                    }
                }
            )
            // Keep both layers mounted at 0%, 100% and every intermediate progress value. Only
            // the paint changes; switching between a one-Text and two-Text tree at a line boundary
            // was causing Compose to measure the karaoke row again during the lyric handoff.
            val directHighlight = directHighlightRenderSpec
            Text(
                text = text,
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
                    p >= 0.999f -> highlightColor
                    else -> Color.Transparent
                },
                style = when {
                    directHighlight != null -> style
                    p >= 0.999f -> style
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

/**
 * Build physical clip partitions from the original shaped Text layout, then expose centres in
 * logical reading order. Re-drawing the same shaped layout through these partitions preserves
 * ligatures/Arabic/Thai/Hangul shaping while still matching the per-glyph floatUps[] owner.
 */
private fun resolveLyricGlyphSlices(
    text: String,
    layout: TextLayoutResult,
    rtl: Boolean,
): List<LyricGlyphSliceSpec> {
    if (text.isEmpty() || layout.size.width <= 0) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val centers = ArrayList<Float>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var offset = start
        while (offset < end && offset < text.length) {
            val box = layout.getBoundingBox(offset)
            left = minOf(left, box.left)
            right = maxOf(right, box.right)
            offset++
        }
        if (left.isFinite() && right.isFinite()) {
            centers += ((left + right) * 0.5f).coerceIn(0f, layout.size.width.toFloat())
        }
        start = end
        end = iterator.next()
    }
    if (centers.isEmpty()) return emptyList()

    // Clip partitions must be in physical left-to-right order so the original shaped Text is
    // neither overlapped nor punched with gaps. The curve itself receives logical centres.
    return LyricFloatUpSpec.buildGlyphSlices(
        physicalCentersPx = centers,
        widthPx = layout.size.width.toFloat(),
        rtl = rtl,
    )
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
    wrapTexts: List<String> = emptyList(),
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
        val breaks = balancedLyricBreaks(wrapTokens, maxWidth)
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
    if (glyphCount !in 1..AM_KARAOKE_EMPHASIS_MAX_GLYPHS) return false

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
