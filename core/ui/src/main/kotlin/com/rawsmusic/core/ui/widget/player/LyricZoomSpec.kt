package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class LyricZoomRowBounds(
    val topPx: Float,
    val bottomPx: Float,
) {
    val heightPx: Float get() = (bottomPx - topPx).coerceAtLeast(1f)
    val centerYPx: Float get() = (topPx + bottomPx) * 0.5f
}

internal data class LyricZoomRowTransform(
    val translationYPx: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
)

internal enum class LyricZoomEndpoint { SOURCE, TARGET }

internal enum class LyricZoomRowOwnership { SHARED, SOURCE_ONLY, TARGET_ONLY, HIDDEN }

internal data class LyricZoomSegment(
    val sourceScale: Int,
    val targetScale: Int,
    val progress: Float,
)

/** Pure geometry/release policy used by the lyric VirtualList-style pinch transition. */
internal object LyricZoomSpec {
    const val MIN_SCALE = 75
    const val MAX_SCALE = 130
    const val STEP = 5
    const val RELEASE_COMMIT_FRACTION = 0.30f
    const val EDGE_REVEAL_FRACTION = 0.58f
    const val Reference_VELOCITY_THRESHOLD_DP_PER_SECOND = 500f
    const val Reference_EDGE_INPUT_LIMIT = 3f
    const val Reference_EDGE_RESPONSE = 0.2f
    const val Reference_EDGE_SCALE_RANGE = 55f
    const val MAX_EDGE_OVERSHOOT_PERCENT = 12f
    const val DIRECTION_REVERSAL_DEAD_ZONE_PERCENT = 0.75f

    // Reference's lyric item declares three complete scenes: small, normal and zoomed. A pinch
    // transitions between adjacent scenes; it does not rebuild the scene at every preference step.
    private val Reference_SCENES = intArrayOf(MIN_SCALE, 100, MAX_SCALE)

    fun adjacentSceneScale(scale: Int, direction: Int): Int {
        val current = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        return if (direction > 0) {
            Reference_SCENES.firstOrNull { it > current } ?: MAX_SCALE
        } else {
            Reference_SCENES.lastOrNull { it < current } ?: MIN_SCALE
        }
    }

    /**
     * VirtualList drives zoom from `currentSpan / capturedSpan`, not from list height. A 30% span
     * change therefore produces transition progress 0.30 regardless of screen size or font size.
     */
    fun rawScaleFromPinchRatio(
        baseScale: Int,
        pinchRatio: Float,
    ): Float {
        val base = baseScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (!pinchRatio.isFinite() || pinchRatio <= 0f) return base.toFloat()
        val spanDelta = pinchRatio - 1f
        if (abs(spanDelta) < 0.0001f) return base.toFloat()
        val direction = if (spanDelta > 0f) 1 else -1
        val target = adjacentSceneScale(base, direction)
        val sceneDistance = abs(target - base).takeIf { it > 0 }
            ?: abs(adjacentSceneScale(base, -direction) - base).coerceAtLeast(STEP)
        val unbounded = base + direction * sceneDistance * abs(spanDelta)
        return when {
            unbounded > MAX_SCALE -> {
                val edgeInput = ((unbounded - MAX_SCALE) / sceneDistance)
                    .coerceIn(0f, Reference_EDGE_INPUT_LIMIT)
                MAX_SCALE + ReferenceEdgeResistance(edgeInput) * sceneDistance
            }
            unbounded < MIN_SCALE -> {
                val edgeInput = -((MIN_SCALE - unbounded) / sceneDistance)
                    .coerceIn(0f, 0.9f)
                MIN_SCALE + ReferenceEdgeResistance(edgeInput) * sceneDistance
            }
            else -> unbounded
        }.coerceIn(
            MIN_SCALE - MAX_EDGE_OVERSHOOT_PERCENT,
            MAX_SCALE + MAX_EDGE_OVERSHOOT_PERCENT,
        )
    }

    /** Legacy helper retained for callers outside the lyric gesture path. */
    fun rawScaleFromSpanDelta(
        baseScale: Int,
        spanDeltaPx: Float,
        viewportHeightPx: Float,
    ): Float {
        val base = baseScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (!spanDeltaPx.isFinite() || viewportHeightPx <= 0f) return base.toFloat()
        val direction = when {
            spanDeltaPx > 0f -> 1
            spanDeltaPx < 0f -> -1
            else -> 0
        }
        if (direction == 0) return base.toFloat()
        val target = adjacentSceneScale(base, direction)
        val distance = abs(target - base).coerceAtLeast(1)
        val progress = spanDeltaPx / (viewportHeightPx * 0.65f)
        val raw = base + distance * progress
        return when {
            raw > MAX_SCALE -> {
                val overshootScenes = ((raw - MAX_SCALE) / distance)
                    .coerceIn(0f, Reference_EDGE_INPUT_LIMIT)
                MAX_SCALE + ReferenceEdgeResistance(overshootScenes) * distance
            }
            raw < MIN_SCALE -> {
                val overshootScenes = -((MIN_SCALE - raw) / distance)
                    .coerceIn(0f, 0.9f)
                MIN_SCALE + ReferenceEdgeResistance(overshootScenes) * distance
            }
            else -> raw
        }.coerceIn(
            MIN_SCALE - MAX_EDGE_OVERSHOOT_PERCENT,
            MAX_SCALE + MAX_EDGE_OVERSHOOT_PERCENT,
        )
    }

    fun rawScalePercent(baseScale: Int, pinchRatio: Float): Float {
        val base = baseScale.coerceIn(MIN_SCALE, MAX_SCALE).toFloat()
        if (!pinchRatio.isFinite() || pinchRatio <= 0f) return base
        val raw = base * pinchRatio
        return when {
            raw > MAX_SCALE -> {
                val normalized = ((raw - MAX_SCALE) / Reference_EDGE_SCALE_RANGE)
                    .coerceIn(0f, Reference_EDGE_INPUT_LIMIT)
                MAX_SCALE + ReferenceEdgeResistance(normalized) * Reference_EDGE_SCALE_RANGE
            }
            raw < MIN_SCALE -> {
                val normalized = -((MIN_SCALE - raw) / Reference_EDGE_SCALE_RANGE)
                    .coerceIn(-Reference_EDGE_INPUT_LIMIT, 0f)
                MIN_SCALE + ReferenceEdgeResistance(normalized) * Reference_EDGE_SCALE_RANGE
            }
            else -> raw
        }.coerceIn(
            MIN_SCALE - MAX_EDGE_OVERSHOOT_PERCENT,
            MAX_SCALE + MAX_EDGE_OVERSHOOT_PERCENT,
        )
    }

    /** Exact equivalent of Reference's edgeResistance/scaleGestureOwner.B edge response. */
    fun ReferenceEdgeResistance(value: Float): Float {
        if (!value.isFinite() || value == 0f) return 0f
        val normalized = if (value > 0f) {
            (min(Reference_EDGE_INPUT_LIMIT, value) / Reference_EDGE_INPUT_LIMIT)
        } else {
            (1f - max(0.1f, min(1f, value + 1f))) / 0.9f
        }
        val response = sqrt(max(0f, min(1f, abs(normalized))) * Reference_EDGE_RESPONSE) *
            Reference_EDGE_RESPONSE
        return response * if (value < 0f) -1f else 1f
    }

    /**
     * Reference's ZoomGesture owns one adjacent source/target layout pair at a time. Reaching an
     * endpoint while fingers stay down starts the next pair. Model the 5% RawSMusic font anchors the
     * same way, so arbitrary pinch distance never becomes one giant bitmap/text scale.
     */
    fun segmentForRawScale(
        baseScale: Int,
        rawScale: Float,
        direction: Int = 0,
        previousSourceScale: Int = Int.MIN_VALUE,
        previousTargetScale: Int = Int.MIN_VALUE,
    ): LyricZoomSegment {
        val base = baseScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val raw = rawScale.coerceIn(MIN_SCALE.toFloat(), MAX_SCALE.toFloat())
        val resolvedDirection = direction.takeIf { it != 0 } ?: when {
            raw > base -> 1
            raw < base -> -1
            else -> 0
        }
        // Preserve the completed pair at an exact 5% boundary. Swapping to the next pair on the
        // boundary produces the same geometry but changes SOURCE_ONLY/TARGET_ONLY ownership for
        // one frame, which is the visible alpha jump during a continuous pinch.
        if (
            resolvedDirection != 0 &&
            raw == previousTargetScale.toFloat() &&
            previousSourceScale != Int.MIN_VALUE &&
            previousTargetScale != Int.MIN_VALUE &&
            ((resolvedDirection > 0 && previousSourceScale < previousTargetScale) ||
                (resolvedDirection < 0 && previousSourceScale > previousTargetScale))
        ) {
            return LyricZoomSegment(previousSourceScale, previousTargetScale, 1f)
        }
        if (abs(raw - base) < 0.0001f) return LyricZoomSegment(base, base, 0f)

        // When the fingers reverse before release, retrace the pair that was visible at the
        // reversal. Do not snap to the next pair derived from the original gesture base: that
        // changes holder ownership at the reversal point and makes the entering group jump.
        if (resolvedDirection < 0 && previousSourceScale < previousTargetScale) {
            val source = previousSourceScale.coerceIn(MIN_SCALE + STEP, MAX_SCALE)
            val target = (source - STEP).coerceAtLeast(MIN_SCALE)
            return LyricZoomSegment(source, target, ((source - raw) / STEP).coerceIn(0f, 1f))
        }
        if (resolvedDirection > 0 && previousSourceScale > previousTargetScale) {
            val source = previousTargetScale.coerceIn(MIN_SCALE, MAX_SCALE - STEP)
            val target = (source + STEP).coerceAtMost(MAX_SCALE)
            return LyricZoomSegment(source, target, ((raw - source) / STEP).coerceIn(0f, 1f))
        }

        if (resolvedDirection > 0) {
            if (base >= MAX_SCALE) return LyricZoomSegment(MAX_SCALE, MAX_SCALE, 0f)
            if (raw >= MAX_SCALE && raw >= base) {
                return LyricZoomSegment((MAX_SCALE - STEP).coerceAtLeast(MIN_SCALE), MAX_SCALE, 1f)
            }
            val source = if (raw >= base) {
                val completed = floor((raw - base) / STEP).toInt()
                (base + completed * STEP).coerceIn(MIN_SCALE, MAX_SCALE)
            } else {
                val completed = floor((base - raw) / STEP).toInt()
                (base - completed * STEP).coerceIn(MIN_SCALE, MAX_SCALE - STEP)
            }
            if (source >= MAX_SCALE) return LyricZoomSegment(MAX_SCALE, MAX_SCALE, 0f)
            val target = (source + STEP).coerceAtMost(MAX_SCALE)
            return LyricZoomSegment(source, target, ((raw - source) / STEP).coerceIn(0f, 1f))
        }

        if (base <= MIN_SCALE) return LyricZoomSegment(MIN_SCALE, MIN_SCALE, 0f)
        if (raw <= MIN_SCALE && raw <= base) {
            return LyricZoomSegment((MIN_SCALE + STEP).coerceAtMost(MAX_SCALE), MIN_SCALE, 1f)
        }
        val source = if (raw <= base) {
            val completed = floor((base - raw) / STEP).toInt()
            (base - completed * STEP).coerceIn(MIN_SCALE + STEP, MAX_SCALE)
        } else {
            val completed = floor((raw - base) / STEP).toInt()
            (base + completed * STEP).coerceIn(MIN_SCALE + STEP, MAX_SCALE)
        }
        if (source <= MIN_SCALE) return LyricZoomSegment(MIN_SCALE, MIN_SCALE, 0f)
        val target = (source - STEP).coerceAtLeast(MIN_SCALE)
        return LyricZoomSegment(source, target, ((source - raw) / STEP).coerceIn(0f, 1f))
    }

    /**
     * Resolves the next adjacent scene from the pair that is already under the fingers.
     *
     * VirtualList does not re-derive a transition from the original gesture base on every motion
     * event. The current pair remains the owner while the pointer retraces it; only crossing its
     * source/target edge creates the next pair. Keeping this stateful part separate also preserves
     * the old pure helper above for compatibility with geometry tests and callers that only have a
     * base scale.
     */
    fun continuousSegment(
        rawScale: Float,
        sourceScale: Int,
        targetScale: Int,
        direction: Int,
    ): LyricZoomSegment {
        val raw = rawScale.coerceIn(MIN_SCALE.toFloat(), MAX_SCALE.toFloat())
        val source = sourceScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val target = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (source == target) {
            return when {
                direction > 0 && raw >= source && source < MAX_SCALE -> {
                    val completed = floor((raw - source) / STEP).toInt().coerceAtLeast(0)
                    val nextSource = (source + completed * STEP).coerceAtMost(MAX_SCALE)
                    if (nextSource >= MAX_SCALE) {
                        LyricZoomSegment(MAX_SCALE, MAX_SCALE, 0f)
                    } else {
                        val nextTarget = (nextSource + STEP).coerceAtMost(MAX_SCALE)
                        LyricZoomSegment(
                            nextSource,
                            nextTarget,
                            ((raw - nextSource) / STEP).coerceIn(0f, 1f),
                        )
                    }
                }
                direction < 0 && raw <= source && source > MIN_SCALE -> {
                    val completed = floor((source - raw) / STEP).toInt().coerceAtLeast(0)
                    val nextSource = (source - completed * STEP).coerceAtLeast(MIN_SCALE)
                    if (nextSource <= MIN_SCALE) {
                        LyricZoomSegment(MIN_SCALE, MIN_SCALE, 0f)
                    } else {
                        val nextTarget = (nextSource - STEP).coerceAtLeast(MIN_SCALE)
                        LyricZoomSegment(
                            nextSource,
                            nextTarget,
                            ((nextSource - raw) / STEP).coerceIn(0f, 1f),
                        )
                    }
                }
                else -> LyricZoomSegment(source, target, 0f)
            }
        }

        if (target > source) {
            return when {
                direction < 0 && raw < source -> {
                    LyricZoomSegment(
                        source,
                        (source - STEP).coerceAtLeast(MIN_SCALE),
                        ((source - raw) / STEP).coerceIn(0f, 1f),
                    )
                }
                direction > 0 && raw < source -> {
                    val previousSource = (source - STEP).coerceAtLeast(MIN_SCALE)
                    LyricZoomSegment(
                        previousSource,
                        source,
                        ((raw - previousSource) / STEP).coerceIn(0f, 1f),
                    )
                }
                raw <= target -> {
                    LyricZoomSegment(source, target, ((raw - source) / STEP).coerceIn(0f, 1f))
                }
                direction < 0 -> LyricZoomSegment(source, target, 1f)
                else -> {
                    val completed = floor((raw - source) / STEP).toInt().coerceAtLeast(1)
                    val nextSource = (source + completed * STEP).coerceAtMost(MAX_SCALE)
                    if (nextSource >= MAX_SCALE) {
                        LyricZoomSegment(MAX_SCALE, MAX_SCALE, 0f)
                    } else {
                        val nextTarget = (nextSource + STEP).coerceAtMost(MAX_SCALE)
                        LyricZoomSegment(
                            nextSource,
                            nextTarget,
                            ((raw - nextSource) / STEP).coerceIn(0f, 1f),
                        )
                    }
                }
            }
        }

        return when {
            direction > 0 && raw > source -> {
                LyricZoomSegment(
                    source,
                    (source + STEP).coerceAtMost(MAX_SCALE),
                    ((raw - source) / STEP).coerceIn(0f, 1f),
                )
            }
            direction < 0 && raw > source -> {
                val previousTarget = (source + STEP).coerceAtMost(MAX_SCALE)
                LyricZoomSegment(
                    source,
                    previousTarget,
                    ((previousTarget - raw) / STEP).coerceIn(0f, 1f),
                )
            }
            raw >= target -> {
                LyricZoomSegment(source, target, ((source - raw) / STEP).coerceIn(0f, 1f))
            }
            direction > 0 -> LyricZoomSegment(source, target, 1f)
            else -> {
                val completed = floor((source - raw) / STEP).toInt().coerceAtLeast(1)
                val nextSource = (source - completed * STEP).coerceAtLeast(MIN_SCALE)
                if (nextSource <= MIN_SCALE) {
                    LyricZoomSegment(MIN_SCALE, MIN_SCALE, 0f)
                } else {
                    val nextTarget = (nextSource - STEP).coerceAtLeast(MIN_SCALE)
                    LyricZoomSegment(
                        nextSource,
                        nextTarget,
                        ((nextSource - raw) / STEP).coerceIn(0f, 1f),
                    )
                }
            }
        }
    }

    fun visualFontRatio(
        baseFontSizeSp: Int,
        primaryFontSizeRange: IntRange,
        baseScale: Float,
        targetScale: Float,
    ): Float {
        val minSize = primaryFontSizeRange.first.toFloat()
        val maxSize = primaryFontSizeRange.last.toFloat()
        val baseSize = (baseFontSizeSp * baseScale / 100f).coerceIn(minSize, maxSize).coerceAtLeast(1f)
        val targetSize = (baseFontSizeSp * targetScale / 100f).coerceIn(minSize, maxSize).coerceAtLeast(1f)
        return targetSize / baseSize
    }

    /**
     * VirtualList commits a transition after progress > 0.30 when release velocity does not decide it.
     * The lyric font exposes 5% anchors, so completed steps are retained and the final partial step
     * uses the same 30% threshold.
     */
    fun releaseTargetScale(
        baseScale: Int,
        rawScale: Float,
        rawScaleVelocityPercentPerSecond: Float = 0f,
        direction: Int = 0,
    ): Int {
        val base = baseScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val raw = rawScale.coerceIn(MIN_SCALE.toFloat(), MAX_SCALE.toFloat())
        val delta = raw - base
        if (abs(delta) < 0.0001f) return base

        val resolvedDirection = direction.takeIf { it != 0 } ?: if (delta > 0f) 1 else -1
        val segment = segmentForRawScale(base, raw, resolvedDirection)
        if (segment.sourceScale == segment.targetScale) return segment.sourceScale
        val absoluteDelta = abs(delta)
        val fullSteps = floor(absoluteDelta / STEP).toInt()
        val remainder = absoluteDelta - fullSteps * STEP
        // VirtualListTransitionBase uses a strict progress > 0.30 commit threshold.
        val currentProgress = segment.progress
        val velocityCommits = abs(rawScaleVelocityPercentPerSecond) >=
            Reference_VELOCITY_THRESHOLD_DP_PER_SECOND &&
            ((resolvedDirection > 0 && rawScaleVelocityPercentPerSecond > 0f) ||
                (resolvedDirection < 0 && rawScaleVelocityPercentPerSecond < 0f)) &&
            currentProgress > 0.8f
        val committedPartial = remainder > STEP * RELEASE_COMMIT_FRACTION || velocityCommits
        val stepCount = fullSteps + if (committedPartial) 1 else 0
        // For a reversed continuous pinch, the current adjacent pair is authoritative. Use the
        // pair's endpoint so release follows the same path that was visible under the fingers.
        return if (abs(delta) < STEP) {
            if (committedPartial) segment.targetScale else segment.sourceScale
        } else {
            (base + resolvedDirection * stepCount * STEP).coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }

    /**
     * Mirror LazyColumn's spacedBy geometry between two lyric line items. Hidden interlude items still
     * contribute the two surrounding spacings but have zero body height; only the currently active
     * interlude owns its expanded 48dp body during a zoom transition.
     */
    fun displayGapPx(
        spacingPx: Float,
        displayItemDistance: Int,
        activeInterludeCount: Int,
        interludeHeightPx: Float,
    ): Float {
        val distance = displayItemDistance.coerceAtLeast(1)
        val interludes = activeInterludeCount.coerceAtLeast(0)
        return (spacingPx * distance + interludeHeightPx * interludes).coerceAtLeast(spacingPx)
    }

    fun scaleBoundsAroundAnchor(
        bounds: LyricZoomRowBounds,
        anchorCenterYPx: Float,
        ratio: Float,
    ): LyricZoomRowBounds {
        val safe = ratio.coerceIn(0.5f, 1.8f)
        val center = anchorCenterYPx + (bounds.centerYPx - anchorCenterYPx) * safe
        val halfHeight = bounds.heightPx * safe * 0.5f
        return LyricZoomRowBounds(center - halfHeight, center + halfHeight)
    }

    /**
     * Legacy endpoint transform kept for geometry tests and compatibility with older callers.
     * The runtime pinch renderer no longer uses two endpoint holders. Reference keeps one holder per
     * visible row and changes that holder's geometry in place; see [singleHolderTransform].
     */
    fun endpointTransform(
        sourceBounds: LyricZoomRowBounds,
        targetBounds: LyricZoomRowBounds,
        endpointBounds: LyricZoomRowBounds,
        progress: Float,
        endpoint: LyricZoomEndpoint,
        ownership: LyricZoomRowOwnership,
        fontScaleRatio: Float,
    ): LyricZoomRowTransform {
        val p = progress.coerceIn(0f, 1f)
        val currentCenter = lerp(sourceBounds.centerYPx, targetBounds.centerYPx, p)
        val alpha = when (endpoint) {
            // A shared row has one visual owner for the whole gesture. Keeping the source
            // holder opaque avoids the one-frame crossfade/overlap that appears when Compose
            // commits the target measurement while the source row is still mounted.
            LyricZoomEndpoint.SOURCE -> when (ownership) {
                LyricZoomRowOwnership.SHARED -> 1f
                LyricZoomRowOwnership.SOURCE_ONLY -> 1f - p
                LyricZoomRowOwnership.TARGET_ONLY, LyricZoomRowOwnership.HIDDEN -> 0f
            }
            LyricZoomEndpoint.TARGET -> when (ownership) {
                LyricZoomRowOwnership.TARGET_ONLY -> p
                LyricZoomRowOwnership.SHARED,
                LyricZoomRowOwnership.SOURCE_ONLY,
                LyricZoomRowOwnership.HIDDEN -> 0f
            }
        }
        return LyricZoomRowTransform(
            translationYPx = currentCenter - endpointBounds.centerYPx,
            scale = fontScaleRatio.coerceIn(0.5f, 1.8f),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }

    /**
     * Reference-style transform for one visual lyric holder.
     *
     * A row is never represented by a source and target composable at the same time. Its center and
     * height follow the two measured scene bounds. This is the important distinction from a
     * crossfade: there is no second glyph tree underneath the first one, so a wrap change cannot
     * produce a doubled line.
     */
    fun singleHolderTransform(
        sourceBounds: LyricZoomRowBounds,
        targetBounds: LyricZoomRowBounds,
        currentBounds: LyricZoomRowBounds,
        viewportTopPx: Float,
        viewportBottomPx: Float,
        progress: Float,
        ownership: LyricZoomRowOwnership,
    ): LyricZoomRowTransform {
        val p = progress.coerceIn(0f, 1f)
        val currentCenter = currentBounds.centerYPx
        val currentHeight = currentBounds.heightPx

        // Ownership is selected once from the captured source scene and the fully measured target
        // scene. Do not infer it again from the geometry arguments here: SOURCE_ONLY/TARGET_ONLY
        // intentionally reuse one endpoint's full-size bounds on the missing side so the visual
        // holder never collapses to 0px. Re-inferring visibility from those duplicated bounds turns
        // both cases back into SHARED and silently removes every edge fade.
        val scale = if (ownership == LyricZoomRowOwnership.SHARED) {
            (currentHeight / sourceBounds.heightPx).coerceIn(0.5f, 1.8f)
        } else {
            1f
        }
        val ownershipAlpha = when (ownership) {
            LyricZoomRowOwnership.SHARED -> 1f
            LyricZoomRowOwnership.SOURCE_ONLY -> 1f - p
            LyricZoomRowOwnership.TARGET_ONLY -> p
            LyricZoomRowOwnership.HIDDEN -> 0f
        }
        return LyricZoomRowTransform(
            translationYPx = currentCenter - sourceBounds.centerYPx,
            scale = scale,
            alpha = ownershipAlpha.coerceIn(0f, 1f),
        )
    }

    /**
     * Legacy single-layout helper retained for non-transition callers/tests. The active lyric pinch
     * path now uses [endpointTransform] with two real wrapped endpoint layouts.
     */
    fun rowTransform(
        bounds: LyricZoomRowBounds,
        anchorCenterYPx: Float,
        viewportTopPx: Float,
        viewportBottomPx: Float,
        ratio: Float,
        sourceOwned: Boolean = true,
    ): LyricZoomRowTransform {
        val safeRatio = ratio.coerceIn(0.5f, 1.8f)
        val translation = (bounds.centerYPx - anchorCenterYPx) * (safeRatio - 1f)
        val transformedCenter = bounds.centerYPx + translation
        val transformedHalfHeight = bounds.heightPx * safeRatio * 0.5f
        val transformedTop = transformedCenter - transformedHalfHeight
        val transformedBottom = transformedCenter + transformedHalfHeight
        val transformedVisibleFraction = viewportVisibleFraction(
            topPx = transformedTop,
            bottomPx = transformedBottom,
            viewportTopPx = viewportTopPx,
            viewportBottomPx = viewportBottomPx,
        )
        val alpha = if (sourceOwned) {
            val sourceVisibleFraction = viewportVisibleFraction(
                topPx = bounds.topPx,
                bottomPx = bounds.bottomPx,
                viewportTopPx = viewportTopPx,
                viewportBottomPx = viewportBottomPx,
            )
            if (transformedVisibleFraction >= sourceVisibleFraction || sourceVisibleFraction <= 0f) {
                1f
            } else {
                smoothStep(0f, sourceVisibleFraction, transformedVisibleFraction)
            }
        } else {
            smoothStep(0f, EDGE_REVEAL_FRACTION, transformedVisibleFraction)
        }
        return LyricZoomRowTransform(
            translationYPx = translation,
            scale = safeRatio,
            alpha = alpha,
        )
    }

    fun interpolateToTargetBounds(
        sourceBounds: LyricZoomRowBounds,
        startTransform: LyricZoomRowTransform,
        targetBounds: LyricZoomRowBounds,
        viewportTopPx: Float,
        viewportBottomPx: Float,
        fraction: Float,
    ): LyricZoomRowTransform {
        val p = fraction.coerceIn(0f, 1f)
        val startCenter = sourceBounds.centerYPx + startTransform.translationYPx
        val startHeight = sourceBounds.heightPx * startTransform.scale
        val currentCenter = lerp(startCenter, targetBounds.centerYPx, p)
        val currentHeight = lerp(startHeight, targetBounds.heightPx, p).coerceAtLeast(1f)
        val targetVisibleFraction = viewportVisibleFraction(
            topPx = targetBounds.topPx,
            bottomPx = targetBounds.bottomPx,
            viewportTopPx = viewportTopPx,
            viewportBottomPx = viewportBottomPx,
        )
        // Target-layout ownership is binary. A row that exists in the target visible set must end
        // at full item alpha even when the viewport clips part of its bounds; clipping is the
        // viewport's job. Only rows that are completely outside the target viewport fade to zero.
        val targetAlpha = if (targetVisibleFraction > 0f) 1f else 0f
        return LyricZoomRowTransform(
            translationYPx = currentCenter - sourceBounds.centerYPx,
            scale = currentHeight / sourceBounds.heightPx,
            alpha = lerp(startTransform.alpha, targetAlpha, p).coerceIn(0f, 1f),
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction.coerceIn(0f, 1f)

    private fun viewportVisibleFraction(
        topPx: Float,
        bottomPx: Float,
        viewportTopPx: Float,
        viewportBottomPx: Float,
    ): Float {
        if (viewportBottomPx <= viewportTopPx || bottomPx <= topPx) return 1f
        val overlap = min(bottomPx, viewportBottomPx) - max(topPx, viewportTopPx)
        if (overlap <= 0f) return 0f
        return (overlap / (bottomPx - topPx)).coerceIn(0f, 1f)
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
