package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * VirtualList-style lyric pinch owner.
 *
 * The visible holder scene is frozen when the gesture is captured. Pointer frames only update
 * layer properties on that scene; target typography is measured offscreen and is used only for
 * the final handoff. This mirrors VirtualList's attached-view ownership and avoids remeasuring text
 * while fingers are moving.
 */
@Stable
internal class LyricZoomTransitionState {
    private val liveRows = mutableMapOf<Int, LyricZoomRowBounds>()
    private var frozenVisibleIndices: List<Int> = emptyList()
    private var frozenViewportTopPx: Float = 0f
    private var frozenViewportBottomPx: Float = 0f
    private var frozenAnchorIndex: Int = -1
    private var frozenAnchorCenterYPx: Float = 0f

    private var baseScalePercent: Int = 100
    private var rawScalePercent: Float = 100f
    private var rawScaleVelocityPercentPerSecond: Float = 0f
    private var pinchDirection: Int = 0
    private var reversalAccumulatedPercent: Float = 0f
    private var edgeOvershootPercent: Float = 0f
    private var settleStartEdgeOvershootPercent: Float = 0f
    private var segmentProgress: Float = 0f
    private var settleStartProgress: Float = 0f
    private var settleTargetProgress: Float = 0f
    private var settleStartScalePercent: Float = 100f
    private var settleTargetScalePercent: Float = 100f

    private var liveViewportTopPx: Float = 0f
    private var liveViewportBottomPx: Float = 0f

    /** Actual measured endpoint geometry, keyed by the discrete lyric font scale. */
    private val rowsByScale = mutableMapOf<Int, MutableMap<Int, LyricZoomRowBounds>>()
    private val expectedByScale = mutableMapOf<Int, Set<Int>>()
    private val readyScales = mutableSetOf<Int>()

    private data class PrewarmedScene(
        val anchorIndex: Int,
        val anchorCenterYPx: Float,
        val boundsByIndex: Map<Int, LyricZoomRowBounds>,
    )

    /**
     * VirtualList has its adjacent layout ready before scaleGestureOwner starts mutating holders. Keep the two
     * neighbouring lyric scenes measured while idle so a pinch never has to create a hidden Text
     * tree or change visual ownership after the fingers have already moved.
     */
    private val prewarmedScenes = mutableMapOf<Int, PrewarmedScene>()

    var active: Boolean by mutableStateOf(false)
        private set
    var settling: Boolean by mutableStateOf(false)
        private set
    var handoffPrepared: Boolean by mutableStateOf(false)
        private set

    var segmentSourceScalePercent: Int by mutableIntStateOf(100)
        private set
    var segmentTargetScalePercent: Int by mutableIntStateOf(100)
        private set

    /** Changes only when the adjacent source/target endpoint pair changes. */
    var layoutGeneration: Int by mutableIntStateOf(0)
        private set

    /** One invalidation token for pointer/settle frames and newly-complete endpoint geometry. */
    var frameRevision: Int by mutableIntStateOf(0)
        private set

    /** Endpoint geometry/composition invalidation; never changes on ordinary pointer frames. */
    var geometryRevision: Int by mutableIntStateOf(0)
        private set

    /** Increments only when the real LazyColumn reports a changed row window. */
    var liveLayoutRevision: Int by mutableIntStateOf(0)
        private set

    /** Whole-gesture generation; useful for stable keys and frozen snapshots. */
    var sessionGeneration: Int by mutableIntStateOf(0)
        private set

    // The overlay, its Layout placer, and each graphics layer read the same frame. Keep one
    // computed stack for that frame so a pinch does not turn an O(rows^2) layout calculation into
    // several repeated passes on the UI thread.
    private var interpolatedBoundsCacheRevision = Int.MIN_VALUE
    private var interpolatedBoundsCacheIndices: List<Int> = emptyList()
    private var interpolatedBoundsCache: Map<Int, LyricZoomRowBounds> = emptyMap()
    // Pointer/settle frames arrive at display cadence. Reuse the stack scratch containers instead
    // of allocating multiple LinkedHashMap/filter-list objects for every frame. Reference mutates
    // holder structs in place; this keeps the Compose implementation closer to that cost model.
    private val interpolatedRowHeightsScratch = linkedMapOf<Int, Float>()
    private val interpolatedAttachedIndicesScratch = arrayListOf<Int>()
    private val interpolatedBoundsScratch = linkedMapOf<Int, LyricZoomRowBounds>()

    // Keep ownership stable for one source/target scene pair. Re-evaluating target visibility
    // from every pointer frame makes adjacent rows fade one after another as their projected
    // rectangles cross the viewport instead of entering as one holder group.
    private var targetVisibleCacheScale = Int.MIN_VALUE
    private var targetVisibleCacheIndices: List<Int> = emptyList()
    private var targetVisibleCache: Set<Int> = emptySet()

    // Endpoint bounds are a transaction snapshot. A hidden Compose layout reports its children
    // one by one; using those callbacks immediately would move an entering row several times in
    // the same pinch frame (projection -> partial measure -> final measure).
    private var targetSnapshotScale = Int.MIN_VALUE
    private var targetSnapshotIndices: List<Int> = emptyList()
    private var targetSnapshot: Map<Int, LyricZoomRowBounds> = emptyMap()

    // A scene snapshot is immutable for the whole zoom transaction.  Endpoint measurement may
    // finish later, but it must never rewrite a scene which is already participating in the
    // current source -> target interpolation.
    private val sceneSnapshots = mutableMapOf<Int, Map<Int, LyricZoomRowBounds>>()
    private val sceneVisibleIndices = mutableMapOf<Int, Set<Int>>()

    // Freeze the holder runway before the first endpoint snapshot is built. Late Compose
    // measurement callbacks must not add a row halfway through a pinch.
    private var frozenRunwayIndices: List<Int> = emptyList()
    private var frozenEndpointScales: List<Int> = emptyList()

    // Ownership is fixed for the complete gesture. Re-selecting visible rows at every 5% scene
    // boundary made the fade group twitch and swap members while the fingers were still moving.
    private var gestureOwnershipInitialized = false
    private var gestureSourceIndices: Set<Int> = emptySet()
    private var gestureTargetIndices: Set<Int> = emptySet()
    private var ownershipSourceScale = Int.MIN_VALUE
    private var ownershipTargetScale = Int.MIN_VALUE
    private var ownershipSourceIndices: Set<Int> = emptySet()
    private var ownershipTargetIndices: Set<Int> = emptySet()

    // A Compose graphics layer is evaluated independently for each child. Keep the complete
    // scene pair in one immutable render snapshot so a pointer/measurement update cannot make
    // one row use the old owner while its neighbour uses the new owner in the same frame.
    private var renderSnapshotRevision = Int.MIN_VALUE
    private var renderSourceScale = Int.MIN_VALUE
    private var renderTargetScale = Int.MIN_VALUE
    private var renderProgress = 0f
    private var renderLayoutProgress = 0f
    private var renderEdgeOvershootPercent = 0f
    private var renderSourceIndices: Set<Int> = emptySet()
    private var renderTargetIndices: Set<Int> = emptySet()
    private var renderSourceBounds: Map<Int, LyricZoomRowBounds> = emptyMap()
    private var renderTargetBounds: Map<Int, LyricZoomRowBounds> = emptyMap()

    fun updateViewportWindowBounds(topPx: Float, bottomPx: Float) {
        if (!topPx.isFinite() || !bottomPx.isFinite() || bottomPx <= topPx) return
        liveViewportTopPx = topPx
        liveViewportBottomPx = bottomPx
    }

    fun updateRowWindowBounds(index: Int, topPx: Float, bottomPx: Float) {
        if (index < 0 || !topPx.isFinite() || !bottomPx.isFinite() || bottomPx <= topPx) return
        val next = LyricZoomRowBounds(topPx, bottomPx)
        if (liveRows[index] == next) return
        liveRows[index] = next
        liveLayoutRevision++
    }

    fun livePrewarmRunwayIndices(
        orderedLineIndices: List<Int>,
        anchorIndex: Int,
        perEdge: Int = 12,
    ): List<Int> {
        if (orderedLineIndices.isEmpty()) return emptyList()
        val anchorPosition = orderedLineIndices.indexOf(anchorIndex)
        if (anchorPosition < 0) return emptyList()
        // Target row heights/wrapping are independent of the current scroll offset. Prewarm a
        // fixed anchor-centred runway once per semantic anchor instead of observing live placement
        // revisions during the 550 ms natural-follow scroll. At gesture capture the whole cached
        // scene is translated to the exact frozen anchor center.
        val start = (anchorPosition - perEdge).coerceAtLeast(0)
        val end = (anchorPosition + perEdge).coerceAtMost(orderedLineIndices.lastIndex)
        return (start..end).map(orderedLineIndices::get)
    }

    fun liveViewportTopPx(): Float = liveViewportTopPx

    fun liveAnchorCenterYPx(anchorIndex: Int): Float? = liveRows[anchorIndex]?.centerYPx

    fun updatePrewarmedScene(
        scalePercent: Int,
        anchorIndex: Int,
        anchorCenterYPx: Float,
        boundsByIndex: Map<Int, LyricZoomRowBounds>,
    ) {
        if (active || boundsByIndex.isEmpty() || !anchorCenterYPx.isFinite()) return
        val scale = scalePercent.coerceIn(LyricZoomSpec.MIN_SCALE, LyricZoomSpec.MAX_SCALE)
        val next = linkedMapOf<Int, LyricZoomRowBounds>()
        boundsByIndex.forEach { (index, bounds) ->
            if (bounds.topPx.isFinite() && bounds.bottomPx.isFinite() && bounds.bottomPx > bounds.topPx) {
                next[index] = bounds
            }
        }
        if (next.isEmpty()) return
        val scene = PrewarmedScene(anchorIndex, anchorCenterYPx, next)
        if (prewarmedScenes[scale] != scene) prewarmedScenes[scale] = scene
    }

    fun prewarmedSceneReady(scalePercent: Int, anchorIndex: Int): Boolean {
        val scene = prewarmedScenes[scalePercent] ?: return false
        return scene.anchorIndex == anchorIndex && scene.boundsByIndex.isNotEmpty()
    }

    fun begin(baseScale: Int, anchorIndex: Int): Boolean {
        if (active) return true
        if (liveViewportBottomPx <= liveViewportTopPx) return false

        // Reference starts ZoomGesture from the VirtualList layout that is on screen *now*.  Do not
        // gate gesture capture on a separately published visible-index snapshot: that snapshot is
        // asynchronous in Compose and can lag one frame behind LazyColumn/follow motion, which made
        // the whole pinch fail to start.  onGloballyPositioned already gives us the authoritative
        // current holder geometry, so derive the visible source transaction directly from it.
        val sourceRows = liveRows.entries
            .asSequence()
            .filter { (_, bounds) ->
                bounds.bottomPx > liveViewportTopPx && bounds.topPx < liveViewportBottomPx
            }
            .sortedBy { (_, bounds) -> bounds.topPx }
            .associateTo(linkedMapOf()) { it.key to it.value }
        if (sourceRows.isEmpty()) return false
        val visible = sourceRows.keys.toList()

        val resolvedAnchor = sourceRows.entries
            .firstOrNull { it.key == anchorIndex }
            ?: sourceRows.minByOrNull { abs(it.key - anchorIndex) }
            ?: return false

        baseScalePercent = baseScale.coerceIn(LyricZoomSpec.MIN_SCALE, LyricZoomSpec.MAX_SCALE)
        rawScalePercent = baseScalePercent.toFloat()
        rawScaleVelocityPercentPerSecond = 0f
        pinchDirection = 0
        reversalAccumulatedPercent = 0f
        edgeOvershootPercent = 0f
        settleStartEdgeOvershootPercent = 0f
        segmentSourceScalePercent = baseScalePercent
        segmentTargetScalePercent = baseScalePercent
        segmentProgress = 0f
        settleStartProgress = 0f
        settleTargetProgress = 0f
        settleStartScalePercent = baseScalePercent.toFloat()
        settleTargetScalePercent = baseScalePercent.toFloat()

        frozenVisibleIndices = visible
        frozenViewportTopPx = liveViewportTopPx
        frozenViewportBottomPx = liveViewportBottomPx
        frozenAnchorIndex = resolvedAnchor.key
        frozenAnchorCenterYPx = resolvedAnchor.value.centerYPx

        rowsByScale.clear()
        expectedByScale.clear()
        readyScales.clear()
        sceneSnapshots.clear()
        sceneVisibleIndices.clear()
        frozenRunwayIndices = emptyList()
        frozenEndpointScales = listOf(
            baseScalePercent,
            LyricZoomSpec.adjacentSceneScale(baseScalePercent, -1),
            LyricZoomSpec.adjacentSceneScale(baseScalePercent, 1),
        ).distinct()
        gestureOwnershipInitialized = false
        gestureSourceIndices = emptySet()
        gestureTargetIndices = emptySet()
        ownershipSourceScale = Int.MIN_VALUE
        ownershipTargetScale = Int.MIN_VALUE
        ownershipSourceIndices = emptySet()
        ownershipTargetIndices = emptySet()
        renderSnapshotRevision = Int.MIN_VALUE
        renderSourceScale = Int.MIN_VALUE
        renderTargetScale = Int.MIN_VALUE
        renderProgress = 0f
        renderLayoutProgress = 0f
        renderEdgeOvershootPercent = 0f
        renderSourceIndices = emptySet()
        renderTargetIndices = emptySet()
        renderSourceBounds = emptyMap()
        renderTargetBounds = emptyMap()
        // The real visible LazyColumn is already a fully measured source endpoint on the capture
        // frame. Treat that snapshot as immediately ready instead of waiting for a hidden source
        // layout to re-measure the same text on the next frame. This is the critical ownership rule:
        // as soon as the first pinch delta selects an adjacent target, the transition renderer can
        // hide the stable rows and draw the captured source without a one-frame/whole-gesture gap.
        rowsByScale.getOrPut(baseScalePercent) { linkedMapOf() }.putAll(sourceRows)
        expectedByScale[baseScalePercent] = sourceRows.keys.toSet()
        readyScales.add(baseScalePercent)
        sceneSnapshots[baseScalePercent] = sourceRows.toMap()
        sceneVisibleIndices[baseScalePercent] = sourceRows.keys

        // Import already measured adjacent VirtualList scenes before the first pointer delta. Align
        // them to the exact capture anchor because natural lyric follow may have moved the list a
        // few pixels since the idle prewarm pass completed.
        frozenEndpointScales.filter { it != baseScalePercent }.forEach { scale ->
            val prewarmed = prewarmedScenes[scale]
            if (prewarmed != null && prewarmed.anchorIndex == frozenAnchorIndex) {
                val shift = frozenAnchorCenterYPx - prewarmed.anchorCenterYPx
                val aligned = linkedMapOf<Int, LyricZoomRowBounds>()
                prewarmed.boundsByIndex.forEach { (index, bounds) ->
                    aligned[index] = LyricZoomRowBounds(
                        topPx = bounds.topPx + shift,
                        bottomPx = bounds.bottomPx + shift,
                    )
                }
                if (aligned.isNotEmpty()) {
                    rowsByScale[scale] = aligned
                    expectedByScale[scale] = aligned.keys.toSet()
                    readyScales.add(scale)
                    sceneSnapshots[scale] = aligned.toMap()
                    sceneVisibleIndices[scale] = viewportHolderIndices(aligned)
                }
            }
        }

        targetSnapshotScale = Int.MIN_VALUE
        targetSnapshotIndices = emptyList()
        targetSnapshot = emptyMap()

        settling = false
        handoffPrepared = false
        active = true
        sessionGeneration++
        layoutGeneration++
        geometryRevision++
        frameRevision++
        return true
    }

    fun updatePinch(rawScale: Float, velocityDpPerSecond: Float = 0f) {
        if (!active || settling || handoffPrepared || !rawScale.isFinite()) return
        val nextRaw = rawScale.coerceIn(
            LyricZoomSpec.MIN_SCALE.toFloat() - LyricZoomSpec.MAX_EDGE_OVERSHOOT_PERCENT,
            LyricZoomSpec.MAX_SCALE.toFloat() + LyricZoomSpec.MAX_EDGE_OVERSHOOT_PERCENT,
        )
        val logicalRaw = nextRaw.coerceIn(
            LyricZoomSpec.MIN_SCALE.toFloat(),
            LyricZoomSpec.MAX_SCALE.toFloat(),
        )
        val deltaFromCapture = logicalRaw - baseScalePercent
        if (pinchDirection == 0 && abs(deltaFromCapture) > 0.0001f) {
            pinchDirection = if (deltaFromCapture > 0f) 1 else -1
            segmentSourceScalePercent = baseScalePercent
            segmentTargetScalePercent = LyricZoomSpec.adjacentSceneScale(
                baseScalePercent,
                pinchDirection,
            )
            layoutGeneration++
        }
        rawScalePercent = logicalRaw
        rawScaleVelocityPercentPerSecond = velocityDpPerSecond
        val nextEdgeOvershoot = nextRaw - logicalRaw
        val edgeChanged = edgeOvershootPercent != nextEdgeOvershoot
        edgeOvershootPercent = nextEdgeOvershoot
        if (pinchDirection == 0 || segmentSourceScalePercent == segmentTargetScalePercent) {
            // At MIN/MAX the logical scene cannot advance, but scaleGestureOwner.B still animates the attached
            // holders through its square-root edge resistance. Keep those pointer frames alive.
            if (edgeChanged) frameRevision++
            return
        }

        // Keep one immutable scene transaction under the fingers. Reversing the pinch simply
        // retraces this progress; it never swaps source/target ownership halfway through a frame.
        val sceneDistance = abs(segmentTargetScalePercent - baseScalePercent).coerceAtLeast(1).toFloat()
        val directedDistance = (logicalRaw - baseScalePercent) * pinchDirection
        val nextProgress = (directedDistance / sceneDistance).coerceIn(0f, 1f)
        if (nextProgress != segmentProgress || edgeChanged) {
            segmentProgress = nextProgress
            frameRevision++
        }
    }

    fun releaseTargetScale(): Int {
        if (!active || segmentSourceScalePercent == segmentTargetScalePercent) {
            return segmentSourceScalePercent
        }
        val progress = segmentProgress.coerceIn(0f, 1f)
        val segmentDirection = if (segmentTargetScalePercent > segmentSourceScalePercent) 1 else -1
        val velocityCommits = abs(rawScaleVelocityPercentPerSecond) >=
            LyricZoomSpec.Reference_VELOCITY_THRESHOLD_DP_PER_SECOND &&
            ((segmentDirection > 0 && rawScaleVelocityPercentPerSecond > 0f) ||
                (segmentDirection < 0 && rawScaleVelocityPercentPerSecond < 0f))
        return if (progress > LyricZoomSpec.RELEASE_COMMIT_FRACTION || velocityCommits) {
            segmentTargetScalePercent
        } else {
            segmentSourceScalePercent
        }
    }

    fun beginSettling(targetScale: Int) {
        if (!active) return
        val resolved = targetScale.coerceIn(LyricZoomSpec.MIN_SCALE, LyricZoomSpec.MAX_SCALE)
        settleStartProgress = segmentProgress
        settleStartScalePercent = rawScalePercent
        settleTargetScalePercent = resolved.toFloat()
        settleStartEdgeOvershootPercent = edgeOvershootPercent
        settleTargetProgress = when {
            segmentSourceScalePercent == segmentTargetScalePercent -> 0f
            resolved == segmentTargetScalePercent -> 1f
            resolved == segmentSourceScalePercent -> 0f
            else -> 0f
        }
        settling = true
        handoffPrepared = false
        frameRevision++
    }

    fun setSettleFraction(fraction: Float) {
        if (!active || !settling) return
        val p = fraction.coerceIn(0f, 1f)
        rawScalePercent = settleStartScalePercent +
            (settleTargetScalePercent - settleStartScalePercent) * p
        val next = settleStartProgress + (settleTargetProgress - settleStartProgress) * p
        val edgeChanged = edgeOvershootPercent != settleStartEdgeOvershootPercent * (1f - p)
        edgeOvershootPercent = settleStartEdgeOvershootPercent * (1f - p)
        if (next == segmentProgress && !edgeChanged) return
        segmentProgress = next
        frameRevision++
    }

    /** Direct release-settle progress. Used by the renderer latch so the first Animatable value is
     * exactly the frame where the fingers were released, with no synthetic 0 -> fraction remap. */
    fun setSettledProgress(progress: Float, settleFraction: Float? = null) {
        if (!active || !settling) return
        val next = progress.coerceIn(0f, 1f)
        settleFraction?.let {
            edgeOvershootPercent = settleStartEdgeOvershootPercent * (1f - it.coerceIn(0f, 1f))
        }
        if (next == segmentProgress) return
        segmentProgress = next
        frameRevision++
    }

    fun currentSegmentProgress(): Float = segmentProgress

    fun settleTargetProgress(): Float = settleTargetProgress

    fun currentRawScalePercent(): Float = rawScalePercent

    fun currentEdgeOvershootPercent(): Float = edgeOvershootPercent

    /**
     * The visible lyric holder is measured at the scale of the current adjacent scene.  Unlike
     * scaling a bitmap-sized source row, this value is used to measure the actual text tree, so a
     * wrapped line changes its real height before the stack is placed.
     */
    fun currentVisualScalePercent(): Float {
        if (!active || handoffPrepared) return baseScalePercent.toFloat()
        val segmentScale = segmentSourceScalePercent +
            (segmentTargetScalePercent - segmentSourceScalePercent) * segmentProgress
        return (segmentScale + edgeOvershootPercent).coerceIn(
            LyricZoomSpec.MIN_SCALE.toFloat(),
            LyricZoomSpec.MAX_SCALE.toFloat(),
        )
    }

    /** Alpha is only an edge-ownership fade. Shared lyric holders stay fully opaque. */
    fun singleHolderAlpha(index: Int): Float {
        if (!active || handoffPrepared) return 0f
        return when (rowOwnership(index)) {
            LyricZoomRowOwnership.SHARED -> 1f
            LyricZoomRowOwnership.SOURCE_ONLY -> 1f - segmentProgress
            LyricZoomRowOwnership.TARGET_ONLY -> segmentProgress
            LyricZoomRowOwnership.HIDDEN -> 0f
        }
    }

    /** One shared alpha transaction for all source-only and target-only holders. */
    fun transitionHolderAlpha(index: Int, orderedIndices: List<Int>): Float {
        if (!active || handoffPrepared) return 0f
        ensureRenderSnapshot(orderedIndices)
        val sourceVisible = index in renderSourceIndices
        val targetVisible = index in renderTargetIndices
        // One transaction-wide fade curve is shared by the complete entering/leaving group.
        // Keep geometry linear and finger-locked, but soften only opacity so edge holders do not
        // pop while the two complete VirtualList scenes exchange ownership.
        val fadeProgress = renderProgress * renderProgress * (3f - 2f * renderProgress)
        return when {
            sourceVisible && targetVisible -> 1f
            sourceVisible -> 1f - fadeProgress
            targetVisible -> fadeProgress
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    /**
     * Locks the current segment before any holder is composed. Calling this once from the overlay
     * prevents per-row draw callbacks from racing target snapshot creation and changing the fade
     * group halfway through one frame.
     */
    fun prepareTransitionFrame(orderedIndices: List<Int>) {
        if (!active || handoffPrepared || orderedIndices.isEmpty()) return
        if (frozenRunwayIndices.isEmpty()) {
            frozenRunwayIndices = orderedIndices.toList()
            freezeSourceRunway()
        }
        // Do not freeze entering/leaving membership from projected target rectangles. The hidden
        // target scene must report the complete runway first; otherwise a row can be permanently
        // misclassified as SHARED and never receive Reference's edge fade. While this one-shot
        // measurement is pending the real source holders stay visible and follow direct scale.
        if (segmentSourceScalePercent != segmentTargetScalePercent &&
            !layoutReady(segmentTargetScalePercent)
        ) {
            return
        }
        ensureTargetSnapshot(frozenRunwayIndices)
        ensureSegmentOwnership(frozenRunwayIndices)
        ensureRenderSnapshot(frozenRunwayIndices)
    }

    private fun ensureRenderSnapshot(orderedIndices: List<Int>) {
        if (!active || handoffPrepared || orderedIndices.isEmpty()) return
        if (renderSnapshotRevision == frameRevision) return
        ensureTargetSnapshot(frozenRunwayIndices.ifEmpty { orderedIndices })
        ensureSegmentOwnership(frozenRunwayIndices.ifEmpty { orderedIndices })
        renderSnapshotRevision = frameRevision
        renderSourceScale = segmentSourceScalePercent
        renderTargetScale = segmentTargetScalePercent
        renderProgress = segmentProgress.coerceIn(0f, 1f)
        renderEdgeOvershootPercent = edgeOvershootPercent
        val sceneDistance = (renderTargetScale - renderSourceScale).toFloat()
        renderLayoutProgress = if (abs(sceneDistance) < 0.0001f) {
            renderProgress
        } else {
            // VirtualList keeps clamped ownership and damped raw layout progress separately.
            // The latter continues the same holder motion beyond an endpoint.
            renderProgress + renderEdgeOvershootPercent / sceneDistance
        }
        // Ownership and endpoint snapshots are immutable for the transaction. Reuse their actual
        // instances on pointer frames instead of allocating four collection copies at display rate.
        renderSourceIndices = ownershipSourceIndices
        renderTargetIndices = ownershipTargetIndices
        renderSourceBounds = sceneSnapshots[renderSourceScale].orEmpty()
        renderTargetBounds = sceneSnapshots[renderTargetScale].orEmpty()
    }

    /**
     * Keeps the live source layout visibly moving until the measured target holder is ready.
     * Reference moves the current holder during this short preparation window too; Compose must not
     * consume the pinch and leave the screen visually unchanged.
     */
    fun directVisualScaleRatio(
        baseFontSizeSp: Int,
        primaryFontSizeRange: IntRange,
    ): Float {
        if (!active || handoffPrepared) return 1f
        val ratio = LyricZoomSpec.visualFontRatio(
            baseFontSizeSp = baseFontSizeSp,
            primaryFontSizeRange = primaryFontSizeRange,
            baseScale = baseScalePercent.toFloat(),
            targetScale = rawScalePercent,
        )
        val edgeScale = 1f + (edgeOvershootPercent / 100f).coerceIn(-0.12f, 0.12f)
        return (ratio * edgeScale).coerceIn(0.5f, 1.8f)
    }

    fun setLayoutExpectedIndices(scalePercent: Int, indices: Collection<Int>) {
        if (!active) return
        val scale = scalePercent.coerceIn(LyricZoomSpec.MIN_SCALE, LyricZoomSpec.MAX_SCALE)
        val next = indices.toSet()
        if (expectedByScale[scale] == next) return
        expectedByScale[scale] = next
        updateScaleReady(scale)
    }

    fun updateLayoutRowWindowBounds(scalePercent: Int, index: Int, topPx: Float, bottomPx: Float) {
        if (!active || index < 0 || !topPx.isFinite() || !bottomPx.isFinite() || bottomPx <= topPx) return
        val scale = scalePercent.coerceIn(LyricZoomSpec.MIN_SCALE, LyricZoomSpec.MAX_SCALE)
        val next = LyricZoomRowBounds(topPx, bottomPx)
        val rows = rowsByScale.getOrPut(scale) { linkedMapOf() }
        if (rows[index] == next) return
        rows[index] = next
        // Endpoint callbacks complete asynchronously. The visible transition keeps its captured
        // target snapshot stable; the new geometry is consumed by the next scene transaction.
        updateScaleReady(scale)
    }

    /**
     * Publish one complete hidden endpoint measurement as a single transaction.  The old per-row
     * callback path caused N map mutations and let partially measured geometry exist between
     * callbacks.  VirtualList prepares one target layout before its holder transition consumes it,
     * so the zoom measurer batches the whole runway and flips layoutReady only once.
     */
    fun updateLayoutSceneWindowBounds(
        scalePercent: Int,
        boundsByIndex: Map<Int, LyricZoomRowBounds>,
    ) {
        if (!active || boundsByIndex.isEmpty()) return
        val scale = scalePercent.coerceIn(LyricZoomSpec.MIN_SCALE, LyricZoomSpec.MAX_SCALE)
        val expected = expectedByScale[scale].orEmpty()
        if (expected.isEmpty() || !expected.all(boundsByIndex::containsKey)) return
        val nextRows = linkedMapOf<Int, LyricZoomRowBounds>()
        expected.forEach { index ->
            val bounds = boundsByIndex[index] ?: return
            if (!bounds.topPx.isFinite() || !bounds.bottomPx.isFinite() || bounds.bottomPx <= bounds.topPx) return
            nextRows[index] = bounds
        }
        if (rowsByScale[scale] == nextRows && readyScales.contains(scale)) return
        rowsByScale[scale] = nextRows
        updateScaleReady(scale)
    }

    fun layoutReady(scalePercent: Int): Boolean = readyScales.contains(scalePercent)

    fun segmentGeometryReady(): Boolean =
        layoutReady(segmentSourceScalePercent) && layoutReady(segmentTargetScalePercent)

    /**
     * The zoom transaction has frozen source/target geometry and may render target-only entrants.
     * This no longer means a full overlay owns all text: captured source/shared/source-only holders
     * remain the real LazyColumn holders for the entire pinch, matching Reference's attached-holder
     * ownership.
     */
    fun overlayOwnsText(): Boolean =
        active &&
            !handoffPrepared &&
            frozenVisibleIndices.isNotEmpty() &&
            segmentSourceScalePercent != segmentTargetScalePercent &&
            gestureOwnershipInitialized

    /** True only while an adjacent scale scene is being interpolated. */
    fun transitionOwnsText(): Boolean =
        overlayOwnsText() && segmentSourceScalePercent != segmentTargetScalePercent

    fun layoutRowBounds(scalePercent: Int, index: Int): LyricZoomRowBounds? =
        rowsByScale[scalePercent]?.get(index)

    /**
     * VirtualList never drops a scene while its next layout is being prepared.  Use the captured
     * source row as a temporary projection when the wrapped target has not reported bounds yet;
     * the real endpoint replaces this projection through geometryRevision without changing scene
     * ownership or alpha.
     */
    fun layoutRowBoundsOrProjected(scalePercent: Int, index: Int): LyricZoomRowBounds? {
        layoutRowBounds(scalePercent, index)?.let { return it }

        val reference = rowsByScale.entries.asSequence()
            .mapNotNull { (knownScale, rows) ->
                rows[index]?.let { knownScale to it }
            }
            .minByOrNull { (knownScale, _) -> abs(knownScale - scalePercent) }
            ?: return liveRows[index]

        val (knownScale, bounds) = reference
        return LyricZoomSpec.scaleBoundsAroundAnchor(
            bounds = bounds,
            anchorCenterYPx = frozenAnchorCenterYPx,
            ratio = (scalePercent.toFloat() / knownScale.coerceAtLeast(1).toFloat())
                .coerceIn(0.5f, 1.8f),
        )
    }

    fun frozenAnchorIndex(): Int = frozenAnchorIndex

    /** Source holders are exactly the rows attached when the pinch captured the list. */
    fun isFrozenSourceRow(index: Int): Boolean = index in frozenVisibleIndices

    fun frozenAnchorCenterYPx(): Float = frozenAnchorCenterYPx

    fun frozenViewportTopPx(): Float = frozenViewportTopPx

    fun frozenViewportBottomPx(): Float = frozenViewportBottomPx

    fun frozenViewportHeightPx(): Float =
        (frozenViewportBottomPx - frozenViewportTopPx).coerceAtLeast(1f)

    fun liveRowBounds(index: Int): LyricZoomRowBounds? = liveRows[index]

    fun targetSceneScalePercent(): Int = segmentTargetScalePercent

    /**
     * VirtualList keeps the holders that were already attached at gesture capture as the visible
     * source scene for the complete pinch. Do not replace them with a second full Compose tree.
     * Shared/source-only rows therefore ask this owner for their real measured source->target
     * transform directly; only target-only rows need an overlay holder.
     */
    fun sourceHolderTransform(index: Int): LyricZoomRowTransform {
        if (!active || handoffPrepared || index !in frozenVisibleIndices) {
            return LyricZoomRowTransform(alpha = 0f)
        }
        val runway = frozenRunwayIndices
        if (runway.isEmpty()) return LyricZoomRowTransform(alpha = 1f)
        return singleHolderTransform(index, runway)
    }

    /** Target holders that did not exist in the captured viewport. Evaluated only on the
     * geometry/session handoff, never on pointer frames. */
    fun targetOnlyLineIndices(orderedIndices: List<Int>): List<Int> {
        if (!active || handoffPrepared || orderedIndices.isEmpty()) return emptyList()
        ensureRenderSnapshot(orderedIndices)
        return orderedIndices.filter { index ->
            index !in renderSourceIndices && index in renderTargetIndices
        }
    }

    fun isTargetOnly(index: Int, orderedIndices: List<Int>): Boolean {
        ensureRenderSnapshot(orderedIndices)
        return index !in renderSourceIndices && index in renderTargetIndices
    }

    fun transitionLineIndices(orderedLineIndices: List<Int>, perEdge: Int = 0): List<Int> {
        if (!active || frozenVisibleIndices.isEmpty() || orderedLineIndices.isEmpty()) return emptyList()
        val visiblePositions = frozenVisibleIndices.mapNotNull { index ->
            orderedLineIndices.indexOf(index).takeIf { it >= 0 }
        }
        if (visiblePositions.isEmpty()) return emptyList()
        val first = visiblePositions.minOrNull() ?: return emptyList()
        val last = visiblePositions.maxOrNull() ?: return emptyList()
        val start = (first - perEdge).coerceAtLeast(0)
        val end = (last + perEdge).coerceAtMost(orderedLineIndices.lastIndex)
        if (frozenRunwayIndices.isEmpty()) {
            frozenRunwayIndices = (start..end).map(orderedLineIndices::get)
            freezeSourceRunway()
        }
        return frozenRunwayIndices
    }

    /** Keep endpoint subtrees mounted for the complete pinch instead of replacing them at 5% boundaries. */
    fun endpointScalesForGesture(): List<Int> = frozenEndpointScales

    /**
     * VirtualList keeps the source holder measured at the capture scene for the complete gesture.
     * It does not swap the visible holder's font size whenever a 5% preference boundary is crossed.
     */
    fun capturedHolderFontScalePercent(): Int = baseScalePercent

    fun capturedHolderTransform(
        index: Int,
        baseFontSizeSp: Int,
        primaryFontSizeRange: IntRange,
    ): LyricZoomRowTransform {
        if (!active || handoffPrepared) return LyricZoomRowTransform(alpha = 0f)
        frameRevision
        val source = sceneSnapshots[baseScalePercent]?.get(index)
            ?: rowsByScale[baseScalePercent]?.get(index)
            ?: liveRows[index]
            ?: return LyricZoomRowTransform(alpha = 0f)
        val ratio = LyricZoomSpec.visualFontRatio(
            baseFontSizeSp = baseFontSizeSp,
            primaryFontSizeRange = primaryFontSizeRange,
            baseScale = baseScalePercent.toFloat(),
            targetScale = rawScalePercent + edgeOvershootPercent,
        )
        val geometry = LyricZoomSpec.rowTransform(
            bounds = source,
            anchorCenterYPx = frozenAnchorCenterYPx,
            viewportTopPx = frozenViewportTopPx,
            viewportBottomPx = frozenViewportBottomPx,
            ratio = ratio,
        )
        // Target measurement is asynchronous in Compose. Reference already fades the captured
        // holder field while the target layout is being prepared, so do not leave this phase as
        // "scale only". Project the exact adjacent scene from the frozen source and apply the same
        // source-only group fade until the measured single-holder overlay takes ownership.
        val targetScale = if (segmentTargetScalePercent != baseScalePercent) {
            segmentTargetScalePercent
        } else {
            val direction = when {
                rawScalePercent > baseScalePercent -> 1
                rawScalePercent < baseScalePercent -> -1
                else -> 0
            }
            if (direction == 0) baseScalePercent else LyricZoomSpec.adjacentSceneScale(baseScalePercent, direction)
        }
        val endpointRatio = LyricZoomSpec.visualFontRatio(
            baseFontSizeSp = baseFontSizeSp,
            primaryFontSizeRange = primaryFontSizeRange,
            baseScale = baseScalePercent.toFloat(),
            targetScale = targetScale.toFloat(),
        )
        val endpoint = LyricZoomSpec.scaleBoundsAroundAnchor(
            bounds = source,
            anchorCenterYPx = frozenAnchorCenterYPx,
            ratio = endpointRatio,
        )
        val sourceVisible = index in frozenVisibleIndices
        val targetVisible = isViewportVisible(endpoint)
        val groupAlpha = when {
            sourceVisible && targetVisible -> 1f
            sourceVisible -> 1f - segmentProgress.coerceIn(0f, 1f)
            targetVisible -> segmentProgress.coerceIn(0f, 1f)
            else -> 0f
        }
        return geometry.copy(alpha = minOf(geometry.alpha, groupAlpha))
    }

    /**
     * Source-only and target-only rows share one transaction fraction. Membership is calculated
     * from the captured scene and the adjacent endpoint, so no row can join/leave the fade group
     * halfway through the same pinch.
     */
    fun capturedHolderAlpha(
        index: Int,
        baseFontSizeSp: Int,
        primaryFontSizeRange: IntRange,
    ): Float {
        if (!active || handoffPrepared) return 0f
        frameRevision
        val source = sceneSnapshots[baseScalePercent]?.get(index)
            ?: rowsByScale[baseScalePercent]?.get(index)
            ?: liveRows[index]
            ?: return 0f
        val direction = when {
            rawScalePercent > baseScalePercent -> 1
            rawScalePercent < baseScalePercent -> -1
            else -> 0
        }
        if (direction == 0) return if (index in frozenVisibleIndices) 1f else 0f
        val endpointScale = LyricZoomSpec.adjacentSceneScale(baseScalePercent, direction)
        val endpointRatio = LyricZoomSpec.visualFontRatio(
            baseFontSizeSp = baseFontSizeSp,
            primaryFontSizeRange = primaryFontSizeRange,
            baseScale = baseScalePercent.toFloat(),
            targetScale = endpointScale.toFloat(),
        )
        val endpoint = LyricZoomSpec.scaleBoundsAroundAnchor(
            bounds = source,
            anchorCenterYPx = frozenAnchorCenterYPx,
            ratio = endpointRatio,
        )
        val sourceVisible = index in frozenVisibleIndices
        val targetVisible = isViewportVisible(endpoint)
        val endpointDistance = abs(endpointScale - baseScalePercent).coerceAtLeast(1).toFloat()
        val progress = (abs(rawScalePercent - baseScalePercent) / endpointDistance).coerceIn(0f, 1f)
        return when {
            sourceVisible && targetVisible -> 1f
            sourceVisible -> 1f - progress
            targetVisible -> progress
            else -> 0f
        }
    }

    fun rowOwnership(index: Int): LyricZoomRowOwnership {
        // The captured source holder set is authoritative. Do not infer source ownership from a
        // projected endpoint rectangle: a projected off-screen row may overlap the viewport after
        // it is scaled around the anchor, but it was not present in the source scene. Reference's
        // holder owner is selected before the zoom starts and remains stable for this transaction.
        ensureSegmentOwnership(frozenRunwayIndices)
        val sourceVisible = index in ownershipSourceIndices
        val targetVisible = index in ownershipTargetIndices
        return when {
            sourceVisible && targetVisible -> LyricZoomRowOwnership.SHARED
            sourceVisible -> LyricZoomRowOwnership.SOURCE_ONLY
            targetVisible -> LyricZoomRowOwnership.TARGET_ONLY
            else -> LyricZoomRowOwnership.HIDDEN
        }
    }

    fun singleHolderTransform(
        index: Int,
        orderedIndices: List<Int>,
    ): LyricZoomRowTransform {
        if (!active || handoffPrepared) return LyricZoomRowTransform(alpha = 0f)
        frameRevision // establish draw-layer dependency
        ensureRenderSnapshot(orderedIndices)
        val sourceVisible = index in renderSourceIndices
        val targetVisible = index in renderTargetIndices
        val measuredSource = renderSourceBounds[index]
        val measuredTarget = renderTargetBounds[index]
        // Entering holders already own their final target rectangle from the first frame and only
        // fade in there. Leaving holders retain their source rectangle while fading out. Reference
        // does not grow either group from a 1px viewport-edge rectangle; shared holders alone move
        // between scenes and make/give back the required space.
        val source = when {
            sourceVisible -> measuredSource
            targetVisible -> measuredTarget
            else -> null
        } ?: return LyricZoomRowTransform(alpha = 0f)
        val target = when {
            targetVisible -> measuredTarget
            sourceVisible -> measuredSource
            else -> null
        } ?: return LyricZoomRowTransform(alpha = 0f)
        val current = interpolatedRowBounds(index, orderedIndices) ?: return LyricZoomRowTransform(alpha = 0f)
        val transform = LyricZoomSpec.singleHolderTransform(
            sourceBounds = source,
            targetBounds = target,
            currentBounds = current,
            viewportTopPx = frozenViewportTopPx,
            viewportBottomPx = frozenViewportBottomPx,
            progress = renderProgress,
            ownership = when {
                sourceVisible && targetVisible -> LyricZoomRowOwnership.SHARED
                sourceVisible -> LyricZoomRowOwnership.SOURCE_ONLY
                targetVisible -> LyricZoomRowOwnership.TARGET_ONLY
                else -> LyricZoomRowOwnership.HIDDEN
            },
        )
        // Place the holder once at the source scene position. Move it on the render layer to avoid
        // a full Compose remeasure on every pinch sample, matching Reference's per-frame View move.
        //
        // IMPORTANT: renderProgress is clamped at the endpoint, while scaleGestureOwner keeps moving attached
        // holders through its damped edge response. 9A38 stored renderEdgeOvershootPercent but the
        // single-holder path never consumed it, so the UI became visually frozen as soon as the
        // fingers reached 130/75 even though the touch span kept changing. Apply the residual edge
        // scale around the frozen anchor to every surviving holder.
        val edgeScale = (1f + renderEdgeOvershootPercent / 100f).coerceIn(0.88f, 1.12f)
        val edgeCenter = frozenAnchorCenterYPx +
            (current.centerYPx - frozenAnchorCenterYPx) * edgeScale
        return transform.copy(
            translationYPx = edgeCenter - source.centerYPx,
            scale = transform.scale * edgeScale,
        )
    }

    /** Static placement for the transition Layout's one-time measure pass. */
    fun transitionSourceRowBounds(index: Int): LyricZoomRowBounds? {
        val source = renderSourceBounds[index]
            ?: sceneSnapshots[baseScalePercent]?.get(index)
            ?: rowsByScale[baseScalePercent]?.get(index)
            ?: liveRows[index]
        return if (index !in renderSourceIndices && index in renderTargetIndices) {
            renderTargetBounds[index] ?: source
        } else {
            source
        }
    }

    /**
     * Current full-stack bounds for one zoom frame. Every row, including an alpha-zero edge row,
     * participates in this geometry so entering rows reserve space before they become visible and
     * leaving rows give that space back in the reverse direction.
     */
    fun transitionRowBounds(
        index: Int,
        orderedIndices: List<Int>,
    ): LyricZoomRowBounds? {
        if (!active || handoffPrepared) return null
        frameRevision // establish the same draw/layout dependency as the holder transform
        return interpolatedBoundsMap(orderedIndices)[index]
    }

    /**
     * Geometry of the child scene currently used to draw this holder. Reference keeps one lyric
     * TextView and changes its SceneParams once at recalcChildren=50%; it never grows an entering
     * row from a one-pixel rectangle.
     */
    fun transitionSceneRowBounds(
        index: Int,
        orderedIndices: List<Int>,
        targetScene: Boolean,
    ): LyricZoomRowBounds? {
        if (!active || handoffPrepared) return null
        ensureRenderSnapshot(orderedIndices)
        return if (targetScene) {
            renderTargetBounds[index] ?: renderSourceBounds[index]
        } else {
            renderSourceBounds[index] ?: renderTargetBounds[index]
        }
    }

    /**
     * Builds one continuous stack for the current frame. Interpolating each row's absolute bounds
     * independently lets a wrapped row overtake its neighbour; Reference keeps one list geometry and
     * moves holders inside that stack. We mirror that by interpolating the row heights and the gaps,
     * then laying out from the frozen anchor in both directions.
     */
    private fun interpolatedRowBounds(
        index: Int,
        orderedIndices: List<Int>,
    ): LyricZoomRowBounds? = interpolatedBoundsMap(orderedIndices)[index]

    private fun interpolatedBoundsMap(
        orderedIndices: List<Int>,
    ): Map<Int, LyricZoomRowBounds> {
        if (orderedIndices.isEmpty()) return emptyMap()
        ensureRenderSnapshot(orderedIndices)
        val revision = frameRevision
        if (
            interpolatedBoundsCacheRevision == revision &&
            interpolatedBoundsCacheIndices == orderedIndices
        ) {
            return interpolatedBoundsCache
        }

        val p = renderLayoutProgress
        val geometryProgress = p.coerceIn(0f, 1f)
        val rowHeights = interpolatedRowHeightsScratch
        rowHeights.clear()
        orderedIndices.forEach { index ->
            val sourceVisible = index in renderSourceIndices
            val targetVisible = index in renderTargetIndices
            val source = renderSourceBounds[index]
            val target = renderTargetBounds[index]
            val sourceHeight = source?.heightPx ?: target?.heightPx ?: return@forEach
            val targetHeight = target?.heightPx ?: sourceHeight
            rowHeights[index] = when {
                sourceVisible && targetVisible -> lerp(sourceHeight, targetHeight, geometryProgress)
                sourceVisible -> sourceHeight * (1f - geometryProgress)
                targetVisible -> targetHeight * geometryProgress
                else -> lerp(sourceHeight, targetHeight, geometryProgress)
            }.coerceAtLeast(0f)
        }
        if (rowHeights.isEmpty()) return emptyMap()

        fun endpointGap(
            bounds: Map<Int, LyricZoomRowBounds>,
            fromIndex: Int,
            toIndex: Int,
        ): Float? {
            val from = bounds[fromIndex] ?: return null
            val to = bounds[toIndex] ?: return null
            return (to.topPx - from.bottomPx).coerceAtLeast(0f)
        }

        fun currentGap(fromIndex: Int, toIndex: Int): Float {
            val sourceGap = endpointGap(renderSourceBounds, fromIndex, toIndex)
            val targetGap = endpointGap(renderTargetBounds, fromIndex, toIndex)
            val sourcePairAttached = fromIndex in renderSourceIndices && toIndex in renderSourceIndices
            val targetPairAttached = fromIndex in renderTargetIndices && toIndex in renderTargetIndices
            return when {
                sourcePairAttached && targetPairAttached -> lerp(
                    sourceGap ?: targetGap ?: 0f,
                    targetGap ?: sourceGap ?: 0f,
                    geometryProgress,
                )
                sourcePairAttached -> (sourceGap ?: 0f) * (1f - geometryProgress)
                targetPairAttached -> (targetGap ?: 0f) * geometryProgress
                else -> lerp(
                    sourceGap ?: targetGap ?: 0f,
                    targetGap ?: sourceGap ?: 0f,
                    geometryProgress,
                )
            }
        }

        // Rebuild one continuous holder stack around the frozen active row. Interpolating every
        // absolute rectangle independently allows a wrapped row to overtake its neighbour. The
        // list engine instead lets leaving row heights/gaps contract while entering row
        // heights/gaps expand, so all affected rows make room as one group.
        val attachedIndices = interpolatedAttachedIndicesScratch
        attachedIndices.clear()
        orderedIndices.forEach { index -> if (rowHeights.containsKey(index)) attachedIndices += index }
        val anchorPosition = attachedIndices.indexOf(frozenAnchorIndex).takeIf { it >= 0 }
            ?: attachedIndices.indices.minByOrNull { position ->
                abs(attachedIndices[position] - frozenAnchorIndex)
            }
            ?: return emptyMap()
        val anchorIndex = attachedIndices[anchorPosition]
        val sourceAnchor = renderSourceBounds[anchorIndex]
        val targetAnchor = renderTargetBounds[anchorIndex]
        val anchorCenter = when {
            sourceAnchor != null && targetAnchor != null ->
                lerp(sourceAnchor.centerYPx, targetAnchor.centerYPx, geometryProgress)
            sourceAnchor != null -> sourceAnchor.centerYPx
            targetAnchor != null -> targetAnchor.centerYPx
            else -> frozenAnchorCenterYPx
        }
        val current = interpolatedBoundsScratch
        current.clear()
        val anchorHeight = rowHeights.getValue(anchorIndex)
        current[anchorIndex] = LyricZoomRowBounds(
            topPx = anchorCenter - anchorHeight * 0.5f,
            bottomPx = anchorCenter + anchorHeight * 0.5f,
        )
        for (position in anchorPosition + 1 until attachedIndices.size) {
            val previousIndex = attachedIndices[position - 1]
            val index = attachedIndices[position]
            val previous = current.getValue(previousIndex)
            val top = previous.bottomPx + currentGap(previousIndex, index)
            current[index] = LyricZoomRowBounds(top, top + rowHeights.getValue(index))
        }
        for (position in anchorPosition - 1 downTo 0) {
            val index = attachedIndices[position]
            val nextIndex = attachedIndices[position + 1]
            val next = current.getValue(nextIndex)
            val bottom = next.topPx - currentGap(index, nextIndex)
            current[index] = LyricZoomRowBounds(bottom - rowHeights.getValue(index), bottom)
        }
        interpolatedBoundsCacheRevision = revision
        interpolatedBoundsCacheIndices = orderedIndices
        interpolatedBoundsCache = current
        return current
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction.coerceIn(-0.2f, 1.2f)

    fun endpointTransform(
        index: Int,
        endpoint: LyricZoomEndpoint,
        baseFontSizeSp: Int,
        primaryFontSizeRange: IntRange,
    ): LyricZoomRowTransform {
        if (!active || handoffPrepared) return LyricZoomRowTransform(alpha = 0f)
        frameRevision // establish draw-layer dependency
        val sourceScale = segmentSourceScalePercent
        val targetScale = segmentTargetScalePercent
        val sourceBounds = sourceBoundsSnapshot(sourceScale, index, emptyList())
        val targetBounds = targetBoundsSnapshot(index, emptyList())
        val source = sourceBounds ?: targetBounds?.let {
            LyricZoomSpec.scaleBoundsAroundAnchor(
                bounds = it,
                anchorCenterYPx = frozenAnchorCenterYPx,
                ratio = LyricZoomSpec.visualFontRatio(
                    baseFontSizeSp,
                    primaryFontSizeRange,
                    targetScale.toFloat(),
                    sourceScale.toFloat(),
                ),
            )
        } ?: return LyricZoomRowTransform(alpha = 0f)
        val target = targetBounds ?: LyricZoomSpec.scaleBoundsAroundAnchor(
            bounds = source,
            anchorCenterYPx = frozenAnchorCenterYPx,
            ratio = LyricZoomSpec.visualFontRatio(
                baseFontSizeSp,
                primaryFontSizeRange,
                sourceScale.toFloat(),
                targetScale.toFloat(),
            ),
        )
        val progress = segmentProgress.coerceIn(0f, 1f)
        val ownership = rowOwnership(index)
        val endpointScale = if (endpoint == LyricZoomEndpoint.SOURCE) sourceScale else targetScale
        val currentFontScale = sourceScale + (targetScale - sourceScale) * progress
        val fontRatio = LyricZoomSpec.visualFontRatio(
            baseFontSizeSp = baseFontSizeSp,
            primaryFontSizeRange = primaryFontSizeRange,
            baseScale = endpointScale.toFloat(),
            targetScale = currentFontScale,
        )
        val transform = LyricZoomSpec.endpointTransform(
            sourceBounds = source,
            targetBounds = target,
            endpointBounds = if (endpoint == LyricZoomEndpoint.SOURCE) source else target,
            progress = progress,
            endpoint = endpoint,
            ownership = ownership,
            fontScaleRatio = fontRatio,
        )
        // scaleGestureOwner.B applies the edge response to both scene holders. Applying the same small residual
        // scale here keeps the two endpoint layouts locked together while the fingers are beyond
        // MIN/MAX; the logical scene progress itself remains clamped to the endpoint.
        val edgeScale = 1f + (edgeOvershootPercent / 100f).coerceIn(-0.12f, 0.12f)
        val edgeAdjusted = transform.copy(scale = transform.scale * edgeScale)
        return edgeAdjusted
    }

    /** Hide the temporary pair before the real target typography/layout takes ownership. */
    fun prepareTargetLayoutHandoff(finalScale: Int) {
        if (!active) return
        rawScalePercent = finalScale.coerceIn(LyricZoomSpec.MIN_SCALE, LyricZoomSpec.MAX_SCALE).toFloat()
        rawScaleVelocityPercentPerSecond = 0f
        pinchDirection = 0
        reversalAccumulatedPercent = 0f
        edgeOvershootPercent = 0f
        settleStartEdgeOvershootPercent = 0f
        settling = false
        handoffPrepared = true
        renderSnapshotRevision = Int.MIN_VALUE
        frameRevision++
    }

    fun cancel() {
        active = false
        settling = false
        handoffPrepared = false
        rawScalePercent = baseScalePercent.toFloat()
        rawScaleVelocityPercentPerSecond = 0f
        pinchDirection = 0
        reversalAccumulatedPercent = 0f
        edgeOvershootPercent = 0f
        settleStartEdgeOvershootPercent = 0f
        segmentSourceScalePercent = baseScalePercent
        segmentTargetScalePercent = baseScalePercent
        segmentProgress = 0f
        settleStartProgress = 0f
        settleTargetProgress = 0f
        frozenVisibleIndices = emptyList()
        frozenAnchorIndex = -1
        rowsByScale.clear()
        expectedByScale.clear()
        readyScales.clear()
        frameRevision++
        geometryRevision++
        layoutGeneration++
        sessionGeneration++
        targetVisibleCacheScale = Int.MIN_VALUE
        targetVisibleCacheIndices = emptyList()
        targetVisibleCache = emptySet()
        targetSnapshotScale = Int.MIN_VALUE
        targetSnapshotIndices = emptyList()
        targetSnapshot = emptyMap()
        sceneSnapshots.clear()
        sceneVisibleIndices.clear()
        frozenRunwayIndices = emptyList()
        frozenEndpointScales = emptyList()
        gestureOwnershipInitialized = false
        gestureSourceIndices = emptySet()
        gestureTargetIndices = emptySet()
        ownershipSourceScale = Int.MIN_VALUE
        ownershipTargetScale = Int.MIN_VALUE
        ownershipSourceIndices = emptySet()
        ownershipTargetIndices = emptySet()
        renderSnapshotRevision = Int.MIN_VALUE
        renderSourceScale = Int.MIN_VALUE
        renderTargetScale = Int.MIN_VALUE
        renderProgress = 0f
        renderLayoutProgress = 0f
        renderEdgeOvershootPercent = 0f
        renderSourceIndices = emptySet()
        renderTargetIndices = emptySet()
        renderSourceBounds = emptyMap()
        renderTargetBounds = emptyMap()
    }

    private fun updateScaleReady(scale: Int) {
        val expected = expectedByScale[scale].orEmpty()
        val rows = rowsByScale[scale].orEmpty()
        val ready = expected.isNotEmpty() && expected.all(rows::containsKey)
        val changed = if (ready) readyScales.add(scale) else readyScales.remove(scale)
        if (changed) {
            geometryRevision++
            frameRevision++
        }
    }

    private fun isViewportVisible(bounds: LyricZoomRowBounds): Boolean =
        bounds.bottomPx > frozenViewportTopPx && bounds.topPx < frozenViewportBottomPx

    private fun targetVisibleIndices(orderedIndices: List<Int>): Set<Int> {
        if (orderedIndices.isEmpty()) return emptySet()
        ensureTargetSnapshot(orderedIndices)
        if (
            targetVisibleCacheScale == segmentTargetScalePercent &&
            targetVisibleCacheIndices == orderedIndices
        ) {
            return targetVisibleCache
        }
        val visible = sceneVisibleIndices[segmentTargetScalePercent]
            ?: targetSnapshot.keys.filter { index ->
                targetSnapshot[index]?.let(::isViewportVisible) == true
            }.toSet().also { sceneVisibleIndices[segmentTargetScalePercent] = it }
        targetVisibleCacheScale = segmentTargetScalePercent
        targetVisibleCacheIndices = orderedIndices.toList()
        targetVisibleCache = visible
        return visible
    }

    private fun targetBoundsSnapshot(
        index: Int,
        orderedIndices: List<Int>,
    ): LyricZoomRowBounds? {
        ensureTargetSnapshot(orderedIndices)
        // Once an endpoint participates in the scene, its geometry is immutable. A late live
        // measurement must not move one holder while the rest of the group follows the old
        // snapshot, otherwise the entering row visibly jumps.
        return targetSnapshot[index]
    }

    private fun ensureTargetSnapshot(orderedIndices: List<Int>) {
        val scale = segmentTargetScalePercent
        sceneSnapshots[scale]?.let { cached ->
            targetSnapshotScale = scale
            targetSnapshotIndices = if (targetSnapshotIndices.isEmpty()) orderedIndices.toList() else targetSnapshotIndices
            targetSnapshot = cached
            return
        }

        targetSnapshotScale = scale
        targetSnapshotIndices = frozenRunwayIndices.ifEmpty { orderedIndices }.toList()
        val sourceScale = segmentSourceScalePercent
        val sourceScene = sceneSnapshots[sourceScale].orEmpty()
        // Phase 9A37 waited for the hidden target layout to become ready, then accidentally threw
        // those measurements away and rebuilt the target from a scaled source projection. That
        // made source/target visibility sets nearly identical (no enter/leave alpha group) and
        // caused the final stable scene to jump to geometry the gesture never rendered.
        //
        // Use the real target endpoint first. Projection is only a missing-row fallback for the
        // widened runway; when layoutReady(scale) is true all expected visible holders come from
        // rowsByScale[scale].
        val measuredTarget = rowsByScale[scale].orEmpty()
        val targetSeed = buildMap {
            targetSnapshotIndices.forEach { index ->
                measuredTarget[index]?.let {
                    put(index, it)
                    return@forEach
                }
                val source = sourceScene[index]
                    ?: rowsByScale[sourceScale]?.get(index)
                    ?: liveRows[index]
                    ?: return@forEach
                put(
                    index,
                    LyricZoomSpec.scaleBoundsAroundAnchor(
                        bounds = source,
                        anchorCenterYPx = frozenAnchorCenterYPx,
                        ratio = sceneLayoutScale(scale) / sceneLayoutScale(sourceScale),
                    ),
                )
            }
        }
        targetSnapshot = completeSceneBounds(
            scalePercent = scale,
            indices = targetSnapshotIndices,
            seed = targetSeed,
        )
        sceneSnapshots[scale] = targetSnapshot
        sceneVisibleIndices[scale] = viewportHolderIndices(targetSnapshot)
    }

    private fun sceneLayoutScale(scalePercent: Int): Float {
        val scale = scalePercent.coerceIn(LyricZoomSpec.MIN_SCALE, LyricZoomSpec.MAX_SCALE)
        return if (scale <= 100) {
            0.65f + 0.35f * ((scale - 75) / 25f)
        } else {
            1f + 0.2f * ((scale - 100) / 30f)
        }
    }

    private fun sourceBoundsSnapshot(
        scalePercent: Int,
        index: Int,
        orderedIndices: List<Int>,
    ): LyricZoomRowBounds? {
        val cached = sceneSnapshots[scalePercent]
        if (cached != null) {
            return cached[index]
        }

        val snapshot = buildMap {
            orderedIndices.forEach { rowIndex ->
                val bounds = rowsByScale[scalePercent]?.get(rowIndex)
                    ?: projectedBoundsFromKnownScale(scalePercent, rowIndex)
                if (bounds != null) put(rowIndex, bounds)
            }
        }
        if (snapshot.isNotEmpty()) sceneSnapshots[scalePercent] = snapshot
        if (snapshot.isNotEmpty()) {
            sceneVisibleIndices[scalePercent] = viewportHolderIndices(snapshot)
        }
        return snapshot[index]
    }

    private fun freezeSourceRunway() {
        if (frozenRunwayIndices.isEmpty()) return
        val sourceScale = segmentSourceScalePercent
        val existing = buildMap {
            putAll(sceneSnapshots[sourceScale].orEmpty())
            putAll(rowsByScale[sourceScale].orEmpty())
        }
        if (existing.isEmpty()) return
        sceneSnapshots[sourceScale] = completeSceneBounds(
            scalePercent = sourceScale,
            indices = frozenRunwayIndices,
            seed = existing,
        )
        sceneVisibleIndices[sourceScale] = viewportHolderIndices(sceneSnapshots[sourceScale].orEmpty())
    }

    private fun ensureSegmentOwnership(orderedIndices: List<Int>) {
        if (!active || handoffPrepared) return
        val sourceScale = segmentSourceScalePercent
        val targetScale = segmentTargetScalePercent
        if (gestureOwnershipInitialized) {
            ownershipSourceScale = sourceScale
            ownershipTargetScale = targetScale
            ownershipSourceIndices = gestureSourceIndices
            ownershipTargetIndices = gestureTargetIndices
            return
        }
        if (sourceScale == targetScale) {
            ensureTargetSnapshot(orderedIndices)
            ownershipSourceScale = sourceScale
            ownershipTargetScale = targetScale
            ownershipSourceIndices = sceneVisibleIndices[sourceScale].orEmpty().toSet()
            ownershipTargetIndices = ownershipSourceIndices
            return
        }
        ensureTargetSnapshot(orderedIndices)
        gestureSourceIndices = sceneVisibleIndices[sourceScale].orEmpty().toSet()
        gestureTargetIndices = sceneVisibleIndices[targetScale].orEmpty().toSet()
        // An empty target is not a valid measured scene. Treating the whole prefetch runway as
        // target-owned made ownership change while callbacks arrived and erased the group fade.
        if (gestureSourceIndices.isEmpty() || gestureTargetIndices.isEmpty()) return
        gestureOwnershipInitialized = true
        ownershipSourceScale = sourceScale
        ownershipTargetScale = targetScale
        ownershipSourceIndices = gestureSourceIndices
        ownershipTargetIndices = gestureTargetIndices
    }

    /**
     * Visibility owns alpha, while the wider frozen runway owns geometry. Including prefetched
     * edge holders in both visibility sets turns the rows that should enter/leave into SHARED
     * rows, which removes the group fade entirely.
     */
    private fun viewportHolderIndices(boundsByIndex: Map<Int, LyricZoomRowBounds>): Set<Int> {
        if (boundsByIndex.isEmpty()) return emptySet()
        return boundsByIndex.entries
            .asSequence()
            .filter { (_, bounds) -> isViewportVisible(bounds) }
            .sortedBy { (_, bounds) -> bounds.topPx }
            .mapTo(linkedSetOf()) { it.key }
    }

    /** Keep every holder in one ordered, non-overlapping stack while endpoint callbacks arrive. */
    private fun completeSceneBounds(
        scalePercent: Int,
        indices: List<Int>,
        seed: Map<Int, LyricZoomRowBounds>,
    ): Map<Int, LyricZoomRowBounds> {
        if (indices.isEmpty()) return emptyMap()
        val result = linkedMapOf<Int, LyricZoomRowBounds>()
        result.putAll(seed)
        indices.forEach { index ->
            if (index !in result) {
                projectedBoundsFromKnownScale(scalePercent, index)?.let { result[index] = it }
            }
        }
        if (result.isEmpty()) return emptyMap()

        val fallbackHeight = result.values.map { it.heightPx }.average().toFloat().coerceAtLeast(1f)
        val gaps = indices.zipWithNext().mapNotNull { (from, to) ->
            val fromBounds = result[from] ?: return@mapNotNull null
            val toBounds = result[to] ?: return@mapNotNull null
            (toBounds.topPx - fromBounds.bottomPx).coerceAtLeast(2f)
        }
        val fallbackGap = gaps.takeIf { it.isNotEmpty() }?.average()?.toFloat()?.coerceAtLeast(2f) ?: 16f

        // Fill missing callbacks using the nearest known row, then rebuild both sides from the
        // anchor. No missing row is allowed to make later rows collapse into it.
        indices.forEachIndexed { position, index ->
            if (index in result) return@forEachIndexed
            val nearest = indices.mapIndexedNotNull { candidatePosition, candidate ->
                result[candidate]?.let { candidatePosition to it }
            }.minByOrNull { abs(it.first - position) } ?: return@forEachIndexed
            val distance = position - nearest.first
            val step = nearest.second.heightPx + fallbackGap
            val center = nearest.second.centerYPx + distance * step
            result[index] = LyricZoomRowBounds(
                topPx = center - fallbackHeight * 0.5f,
                bottomPx = center + fallbackHeight * 0.5f,
            )
        }

        val anchorPosition = indices.indexOf(frozenAnchorIndex).takeIf { it >= 0 } ?: 0
        val normalized = result.toMutableMap()
        for (position in anchorPosition + 1 until indices.size) {
            val previous = normalized[indices[position - 1]] ?: continue
            val current = normalized[indices[position]] ?: continue
            val gap = (current.topPx - previous.bottomPx).coerceAtLeast(fallbackGap)
            normalized[indices[position]] = LyricZoomRowBounds(
                topPx = previous.bottomPx + gap,
                bottomPx = previous.bottomPx + gap + current.heightPx,
            )
        }
        for (position in anchorPosition - 1 downTo 0) {
            val next = normalized[indices[position + 1]] ?: continue
            val current = normalized[indices[position]] ?: continue
            val gap = (next.topPx - current.bottomPx).coerceAtLeast(fallbackGap)
            normalized[indices[position]] = LyricZoomRowBounds(
                topPx = next.topPx - gap - current.heightPx,
                bottomPx = next.topPx - gap,
            )
        }
        return normalized
    }

    private fun projectedBoundsFromKnownScale(
        scalePercent: Int,
        index: Int,
    ): LyricZoomRowBounds? {
        val reference = rowsByScale.entries.asSequence()
            .mapNotNull { (knownScale, rows) -> rows[index]?.let { knownScale to it } }
            .minByOrNull { (knownScale, _) -> abs(knownScale - scalePercent) }
            // A live row is measured in the captured source scene, not in the requested target
            // scene. Pairing it with scalePercent made the projection ratio exactly 1 and caused
            // prefetched edge rows to keep their old height/position in every target layout.
            ?: liveRows[index]?.let { base -> baseScalePercent to base }
            ?: return null
        val (knownScale, bounds) = reference
        return LyricZoomSpec.scaleBoundsAroundAnchor(
            bounds = bounds,
            anchorCenterYPx = frozenAnchorCenterYPx,
            ratio = (scalePercent.toFloat() / knownScale.coerceAtLeast(1).toFloat())
                .coerceIn(0.5f, 1.8f),
        )
    }
}

@Composable
internal fun rememberLyricZoomTransitionState(key: Any?): LyricZoomTransitionState =
    remember(key) { LyricZoomTransitionState() }
