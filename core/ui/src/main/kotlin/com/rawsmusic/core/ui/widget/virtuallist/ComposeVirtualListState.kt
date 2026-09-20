package com.rawsmusic.core.ui.widget.virtuallist

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

private const val PREFS_NAME = "power_list_prefs"
private const val KEY_LIST_ZOOM_LEVEL = "list_zoom_level"
private const val KEY_COLUMN_COUNT = "column_count"
private const val ELASTIC_REBOUND_DURATION_MS = REFERENCE_ZOOM_REBOUND_MIN_MS
private const val MIN_COLUMNS = 1
private const val MAX_COLUMNS = 4

internal enum class ComposeVirtualListDisplayMode(
    val columns: Int,
    val sceneId: Int,
    val listLevel: ListZoomIndex? = null
) {
    LIST_SMALL(1, VirtualListSceneItem.SCENE_SMALL, ListZoomIndex.SMALL),
    LIST_NORMAL(1, VirtualListSceneItem.SCENE_NORMAL, ListZoomIndex.NORMAL),
    LIST_ZOOMED(1, VirtualListSceneItem.SCENE_ZOOMED, ListZoomIndex.ZOOMED),
    GRID_4(4, VirtualListSceneItem.SCENE_GRID),
    GRID_3(3, VirtualListSceneItem.SCENE_GRID),
    GRID_2(2, VirtualListSceneItem.SCENE_GRID);

    val isGrid: Boolean get() = columns > 1
}

@Stable
class ComposeVirtualListState internal constructor(
    initialLevel: ListZoomIndex,
    initialColumns: Int,
    private val persistZoomLevel: (ListZoomIndex) -> Unit,
    private val persistColumns: (Int) -> Unit
) {
    var currentLevel by mutableStateOf(initialLevel)
        private set

    var currentColumns by mutableIntStateOf(initialColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS))
        private set

    internal var sourceMode by mutableStateOf(displayModeForColumns(currentColumns, currentLevel))
        private set

    internal var targetMode by mutableStateOf(sourceMode)
        private set

    var transitionProgress by mutableFloatStateOf(1f)
        private set

    /**
     * Resisted scaleGestureOwner progress is retained for diagnostics/policy, but source->target holder geometry
     * consumes [transitionProgress] only. Endpoint overrun is applied independently to attached holders.
     */
    var transitionVisualProgress by mutableFloatStateOf(1f)
        private set

    var transitionScaleFactor by mutableFloatStateOf(1f)
        private set

    var boundaryElasticScale by mutableFloatStateOf(1f)
        private set

    /**
     * Signed VirtualList edge overshoot, separate from logical scrollY.
     *
     * Positive = top/down pull, negative = bottom/up pull. The
     * layout/render owner distributes this one value across attached holders; there is deliberately
     * no whole-viewport scale/translation state here.
     */
    var virtualListEdgeOvershootPx by mutableFloatStateOf(0f)
        private set
    private var virtualListEdgeRawPullPx by mutableFloatStateOf(0f)
    private var virtualListEdgeActive by mutableStateOf(false)

    internal val isVirtualListEdgeActive: Boolean
        get() = virtualListEdgeActive

    /**
     * True only while the physical list is actively dragging/flinging.  This is intentionally
     * separate from scene transitions, pinch and edge rebound so the persistent alphabet rail can
     * fade exactly with VirtualList scroll activity without borrowing another animation owner.
     */
    var isListScrollInProgress by mutableStateOf(false)
        private set

    internal fun updateListScrollInProgress(value: Boolean) {
        if (isListScrollInProgress == value) return
        isListScrollInProgress = value
    }

    var isTransitioning by mutableStateOf(false)
        private set

    var isPinching by mutableStateOf(false)
        internal set

    internal val isBoundaryElasticActive: Boolean
        get() = abs(boundaryElasticScale - 1f) >= 0.001f || abs(boundaryRawOverPull) >= 0.001f

    private var boundaryAnimationGeneration = 0
    private var transitionAnimationGeneration = 0
    private val transitionPreparationGate = VirtualListTransitionPreparationGate()
    private var transitionPairGeneration = 0
    private var pinchLogicalProgress = 0f
    // Logical transition settle and endpoint rebound are independent owners in Reference scaleGestureOwner/transitionOwner.
    // Keep the resolved endpoint visible to a new pinch even while the holder-local rebound is
    // still draining, so the next scale motion can cancel rebound and acquire the adjacent pair.
    private var settledTransitionEndpointReached = false
    private var settledTransitionCommitTarget: Boolean? = null
    private var boundaryRawOverPull by mutableFloatStateOf(0f)

    /** 外部请求滚动到指定索引（字母索引用），-1 表示无请求 */
    var currentVisibleCenterIndex by mutableIntStateOf(0)
        private set

    var currentVisibleRange by mutableStateOf(IntRange.EMPTY)
        private set

    fun isIndexVisible(index: Int): Boolean {
        return index >= 0 && !currentVisibleRange.isEmpty() && index in currentVisibleRange
    }

    internal fun updateVisibleRangeForNavigation(range: IntRange) {
        currentVisibleRange = range
        if (!range.isEmpty()) {
            currentVisibleCenterIndex = ((range.first + range.last) / 2).coerceAtLeast(0)
        }
    }

    var scrollToIndexRequestIndex by mutableIntStateOf(-1)
        private set

    var scrollToIndexRequestSerial by mutableIntStateOf(0)
        private set

    internal var viewportResetSerial by mutableIntStateOf(0)
        private set

    // Initial navigation is not a scroll animation. Pages such as Queue know their desired anchor
    // before the first visible layout; carrying it as a separate one-shot request lets
    // ComposeVirtualList resolve the correct pixel scroll synchronously from the first authoritative
    // geometry instead of drawing index 0 and correcting it from a LaunchedEffect one frame later.
    // This is deliberately plain state: it is written by the page before the list body is composed
    // and consumed by that same list owner once geometry is usable.
    private var initialScrollToIndexRequestIndex: Int = -1
    private var initialViewportBound: Boolean = false
    private var lastSettledGeometrySignature: Long = Long.MIN_VALUE
    private var lastSettledViewportHeightPx: Int = -1
    private var lastSettledMaxScrollY: Int = 0

    fun seedInitialScrollToIndex(index: Int) {
        if (initialViewportBound || index < 0) return
        initialScrollToIndexRequestIndex = index
    }

    internal fun initialScrollToIndexRequest(): Int = initialScrollToIndexRequestIndex

    internal fun consumeInitialScrollToIndexRequest(index: Int) {
        if (initialScrollToIndexRequestIndex == index) {
            initialScrollToIndexRequestIndex = -1
            initialViewportBound = true
        }
    }

    internal fun markInitialViewportBound() {
        initialScrollToIndexRequestIndex = -1
        initialViewportBound = true
    }

    internal fun shouldPreserveRetainedScrollForZeroGeometry(
        rawScrollY: Int,
        maxScrollY: Int,
        geometrySignature: Long,
        viewportHeightPx: Int,
    ): Boolean {
        return rawScrollY > 0 &&
            maxScrollY <= 0 &&
            lastSettledMaxScrollY > 0 &&
            lastSettledGeometrySignature == geometrySignature &&
            lastSettledViewportHeightPx == viewportHeightPx
    }

    internal fun recordSettledGeometry(
        geometrySignature: Long,
        viewportHeightPx: Int,
        maxScrollY: Int,
    ) {
        if (viewportHeightPx <= 0 || maxScrollY < 0) return
        lastSettledGeometrySignature = geometrySignature
        lastSettledViewportHeightPx = viewportHeightPx
        lastSettledMaxScrollY = maxScrollY
    }

    /**
     * Match reference player HeaderToItem's destination pre-layout: place the clicked item around the
     * viewport center before the reverse shared transition starts, clamped by the real scroll range.
     * The captured holder bounds are already viewport-local, so this can be resolved synchronously
     * without an asynchronous scroll request or a visible post-transition correction.
     *
     * @return the actual scroll delta applied to this provider, or null when no settled geometry is
     * available yet.
     */
    internal fun prepareCollectionReturnCenter(
        itemTopPx: Float,
        itemBottomPx: Float,
    ): Float? {
        val viewportHeight = lastSettledViewportHeightPx
        if (viewportHeight <= 0 || itemBottomPx <= itemTopPx) return null
        val maxScroll = lastSettledMaxScrollY.coerceAtLeast(0).toFloat()
        val oldScroll = viewportScrollY.coerceIn(0f, maxScroll)
        val itemCenterInViewport = (itemTopPx + itemBottomPx) * 0.5f
        val viewportCenter = viewportHeight * 0.5f
        val targetScroll = (oldScroll + itemCenterInViewport - viewportCenter)
            .coerceIn(0f, maxScroll)
        val delta = targetScroll - oldScroll
        viewportScrollY = targetScroll
        return delta
    }

    fun requestScrollToIndex(index: Int) {
        if (index < 0) return
        scrollToIndexRequestIndex = index
        scrollToIndexRequestSerial += 1
    }

    /**
     * Collection detail pages are transient VirtualList providers. When a HeaderToItem back
     * transition has no concrete header holder because the large artwork is already offscreen,
     * reference player does not revive that old detail viewport on the next entry. The navigation owner
     * calls this only immediately before a later forward entry, never during the back commit while
     * the old detail LayoutRes can still be retained for the endpoint handoff. Bump a generation so
     * any retained settled bucket is rebuilt from scroll=0 before the detail can be shown again.
     */
    internal fun resetViewportToTopForFreshEntry() {
        viewportScrollOwner.updateExact(0f)
        viewportScrollOwner.updateRenderRemainder(0)
        currentVisibleCenterIndex = 0
        currentVisibleRange = IntRange.EMPTY
        scrollToIndexRequestIndex = -1
        initialScrollToIndexRequestIndex = -1
        viewportResetSerial += 1
    }

    internal fun consumeScrollToIndexRequest(serial: Int) {
        if (scrollToIndexRequestSerial == serial) {
            scrollToIndexRequestIndex = -1
        }
    }

    /**
     * Step 3B1: authoritative per-pixel scroll is plain Kotlin state. Internal VirtualList ownership
     * reads this path so a fling no longer publishes its exact position as broad snapshot state.
     */
    internal val viewportScrollOwner = VirtualListScrollPositionOwner()

    internal var viewportScrollY: Float
        get() = viewportScrollOwner.currentPx
        set(value) {
            viewportScrollOwner.updateExact(value)
        }

    internal val currentMode: ComposeVirtualListDisplayMode
        get() = displayModeForColumns(currentColumns, currentLevel)

    /** Same zoom direction used by VirtualList transition geometry and persistent artwork/header holders. */
    internal val transitionZoomIn: Boolean
        get() = modeOrder(targetMode) > modeOrder(sourceMode)

    internal val currentTransitionPairGeneration: Int
        get() = transitionPairGeneration

    internal fun markTransitionPopulationPrepared(pairGeneration: Int) {
        transitionPreparationGate.markPrepared(pairGeneration)
    }

    internal fun isTransitionPopulationPrepared(pairGeneration: Int = transitionPairGeneration): Boolean =
        !isTransitioning || transitionPreparationGate.isPrepared(pairGeneration)

    internal val renderMode: ComposeVirtualListDisplayMode
        get() = currentMode

    val isGrid: Boolean get() = renderMode.isGrid
    val columns: Int get() = renderMode.columns

    internal val sourceOneSlotZoomScaleBase: Float
        get() = 1f

    internal val targetOneSlotZoomScaleBase: Float
        get() = 1f

    val currentParams: ListZoomParams
        get() = paramsFor(currentLevel)

    fun beginPinch() {
        boundaryAnimationGeneration += 1
        transitionAnimationGeneration += 1
        // Reference scaleGestureOwner lets a new scale gesture acquire/continue the current transition owner. Keep
        // the already-rendered logical progress as the new gesture origin instead of restarting it.
        pinchLogicalProgress = if (isTransitioning) transitionProgress else 0f
        isPinching = true
    }

    fun updatePinch(
        rawDelta: Float,
        spanDeltaRatio: Float,
        velocityDp: Float,
    ) {
        // scaleGestureOwner.p() decides capture direction from the current scale motion, not from whether the total
        // span is still above/below the distance where the two fingers first landed. That distinction
        // matters when a second pinch interrupts endpoint rebound: one tiny opposite first sample
        // must not keep replaying boundary elasticity after the fingers have reversed toward a valid
        // zoom transition.
        val expandsNow = virtualListPinchExpandsNow(rawDelta, spanDeltaRatio)
        // Reference transitionOwner.P() removes the active rebound callback and transfers the retained holder
        // population straight to the adjacent transition owner. Do NOT publish currentLevel/columns
        // here: doing so makes Compose briefly expose a settled layout between two pinches, remaps the
        // visible holder ring, and can blank artwork. Rebase source/target in transition space instead.
        if (
            isTransitioning &&
            shouldCanonicalizeSettledVirtualListEndpointForNewPinch(
                endpointReached = settledTransitionEndpointReached,
                spanDeltaRatio = spanDeltaRatio,
            )
        ) {
            val commitTarget = settledTransitionCommitTarget
            if (commitTarget != null) {
                val endpointMode = if (commitTarget) targetMode else sourceMode
                val adjacentMode = adjacentVirtualListMode(endpointMode, expandsNow)
                if (adjacentMode != null) {
                    rebaseTransitionAtSettledEndpoint(endpointMode, adjacentMode)
                } else {
                    // A real terminal edge has no next transition owner. Only there do we expose the
                    // canonical endpoint before the existing boundary-elastic lane takes over.
                    completeTransition(commitTarget)
                    pinchLogicalProgress = 0f
                }
            }
        }
        if (!isTransitioning) {
            val target = nextMode(expandsNow)
            if (target == null) {
                updateBoundaryElastic(rawDelta, expandsNow)
                return
            }
            beginTransition(target)
            pinchLogicalProgress = 0f
        }

        // reference player acquires/binds the next layout engine before the first scale progress reaches
        // attached holders.  A cold or chained pair may need one or more Compose frames to publish
        // its new physical holder union, so keep the gesture scalar at the exact endpoint until the
        // presentation owner acknowledges that generation. Pointer samples can continue arriving;
        // they do not advance a half-bound pair.
        if (!transitionPreparationGate.isPrepared(transitionPairGeneration)) return

        // Accumulate the physical per-frame span delta in target-oriented coordinates. For an
        // uninterrupted pinch this is algebraically the same as using total rawDelta. Unlike the old
        // base-distance sign latch it also supports immediate owner handoff/reversal without waiting
        // for the span to cross the original touch-down distance again.
        val orientedFrameDelta = virtualListOrientedPinchFrameDelta(
            spanDeltaRatio = spanDeltaRatio,
            transitionZoomIn = transitionZoomIn,
        )
        pinchLogicalProgress += orientedFrameDelta
        val logicalProgress = pinchLogicalProgress
        transitionProgress = logicalProgress.coerceIn(0f, 1f)
        transitionVisualProgress = virtualListZoomVisualProgress(logicalProgress)
        transitionScaleFactor = virtualListZoomEndpointGroupScale(
            logicalProgress = logicalProgress,
            visualProgress = transitionVisualProgress,
            transitionZoomIn = transitionZoomIn,
        )
        if (velocityDp != 0f) {
            boundaryRawOverPull = 0f
            boundaryElasticScale = 1f
        }
    }

    suspend fun finishPinch(
        velocityDp: Float,
        progressVelocityPerSecond: Float,
    ) {
        isPinching = false
        if (!isTransitioning) {
            animateBoundaryBack()
            return
        }
        val decision = resolveVirtualListZoomRelease(
            progress = transitionProgress,
            velocityDpPerSecond = velocityDp,
            progressVelocityPerSecond = progressVelocityPerSecond,
            transitionZoomIn = transitionZoomIn,
        )
        val generation = ++transitionAnimationGeneration
        animateTransition(
            confirm = decision.confirm,
            forwardedProgressVelocityPerSecond = decision.forwardedProgressVelocityPerSecond,
            generation = generation,
        )
    }

    /**
     * Pointer-input/lifecycle emergency path. A cancelled owner must never strand half-applied
     * transition geometry. Resolve synchronously with the same 0.30 low-velocity threshold.
     */
    fun resolveInterruptedPinch() {
        transitionAnimationGeneration += 1
        isPinching = false
        if (isTransitioning) {
            completeTransition(resolveInterruptedVirtualListZoomCommit(transitionProgress))
        }
        boundaryRawOverPull = 0f
        boundaryElasticScale = 1f
    }

    suspend fun snapToLevel(level: ListZoomIndex) {
        val target = displayModeForListLevel(level)
        if (target == currentMode) return
        beginTransition(target)
        animateTransition(confirm = true, forwardedProgressVelocityPerSecond = 0f, generation = ++transitionAnimationGeneration)
    }

    suspend fun snapToColumns(columns: Int) {
        val targetColumns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val target = displayModeForColumns(targetColumns, currentLevel)
        if (target == currentMode) return
        beginTransition(target)
        animateTransition(confirm = true, forwardedProgressVelocityPerSecond = 0f, generation = ++transitionAnimationGeneration)
    }

    /**
     * Transfers a logically-settled endpoint directly into the next retained zoom pair. The settled
     * Compose tree remains untouched until the final transition commits, matching Reference's transitionOwner/scaleGestureOwner
     * owner handoff and preventing an intermediate layout/artwork rebind.
     */
    private fun rebaseTransitionAtSettledEndpoint(
        endpointMode: ComposeVirtualListDisplayMode,
        adjacentMode: ComposeVirtualListDisplayMode,
    ) {
        sourceMode = endpointMode
        targetMode = adjacentMode
        transitionProgress = 0f
        transitionVisualProgress = 0f
        transitionScaleFactor = 1f
        boundaryRawOverPull = 0f
        boundaryElasticScale = 1f
        settledTransitionEndpointReached = false
        settledTransitionCommitTarget = null
        pinchLogicalProgress = 0f
        transitionPairGeneration = transitionPreparationGate.beginPair()
        isTransitioning = true
    }

    /** Moves one step toward the denser VirtualList layout using the normal transition path. */
    private fun beginTransition(target: ComposeVirtualListDisplayMode) {
        sourceMode = currentMode
        targetMode = target
        transitionProgress = 0f
        transitionVisualProgress = 0f
        transitionScaleFactor = 1f
        boundaryRawOverPull = 0f
        boundaryElasticScale = 1f
        settledTransitionEndpointReached = false
        settledTransitionCommitTarget = null
        transitionPairGeneration = transitionPreparationGate.beginPair()
        isTransitioning = true
    }

    private suspend fun animateTransition(
        confirm: Boolean,
        forwardedProgressVelocityPerSecond: Float,
        generation: Int,
    ) {
        val preparedPairGeneration = transitionPairGeneration
        while (
            generation == transitionAnimationGeneration &&
            isTransitioning &&
            !transitionPreparationGate.isPrepared(preparedPairGeneration)
        ) {
            // This is a preparation barrier, not animation latency: progress stays on the source
            // endpoint until the physical population has been published, matching the endpoint preflight before
            // z1/O(progress) in reference player.
            withFrameNanos { }
        }
        if (generation != transitionAnimationGeneration || !isTransitioning) return
        settledTransitionEndpointReached = false
        settledTransitionCommitTarget = null
        val start = transitionProgress
        val startVisual = transitionVisualProgress
        val startScale = transitionScaleFactor
        val end = if (confirm) 1f else 0f
        val settle = resolveVirtualListZoomSettleSpec(
            startProgress = start,
            confirm = confirm,
            forwardedProgressVelocityPerSecond = forwardedProgressVelocityPerSecond,
        )
        val scaleOverrun = startScale - 1f
        val hasRebound = virtualListZoomNeedsEndpointRebound(startVisual) || abs(scaleOverrun) >= 0.001f
        // scaleGestureOwner keeps endpoint overpull on attached holders. The settle policy resolves legal logical progress
        // while each holder's endpoint scale returns independently through the derived edgeReboundPolicy reverse
        // cubic. Never add the overrun back into source->target item interpolation.
        val animationDuration = maxOf(
            settle.durationMs,
            if (hasRebound) REFERENCE_ZOOM_REBOUND_MIN_MS else 0,
            1,
        )
        try {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = animationDuration, easing = LinearEasing),
            ) { elapsedFraction, _ ->
                if (generation != transitionAnimationGeneration) {
                    throw CancellationException("VirtualList zoom settle superseded")
                }
                val elapsedMs = elapsedFraction * animationDuration
                val settleFraction = (elapsedMs / settle.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                val logicalFraction = when (settle.kind) {
                    VirtualListZoomSettleKind.AccelerateDecelerate ->
                        referenceAccelerateDecelerate(settleFraction)
                    VirtualListZoomSettleKind.ConstantVelocity -> settleFraction
                }
                transitionProgress = lerp(start, end, logicalFraction)
                if (settleFraction >= 0.999999f) {
                    settledTransitionEndpointReached = true
                    settledTransitionCommitTarget = confirm
                }

                val reboundFraction = (elapsedMs / REFERENCE_ZOOM_REBOUND_MIN_MS).coerceIn(0f, 1f)
                val remainingScaleOverrun = if (hasRebound) {
                    scaleOverrun * (1f - cubicEaseOut(reboundFraction))
                } else {
                    0f
                }
                // Geometry is always legal. The remaining endpoint elasticity is consumed by
                // each attached holder layer; there is no whole-list scale owner.
                transitionVisualProgress = transitionProgress
                transitionScaleFactor = 1f + remainingScaleOverrun
            }
            if (generation == transitionAnimationGeneration) {
                completeTransition(confirm)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun completeTransition(confirm: Boolean) {
        val finalMode = if (confirm) targetMode else sourceMode
        finalMode.listLevel?.let {
            currentLevel = it
            persistZoomLevel(it)
            currentColumns = 1
            persistColumns(1)
        } ?: run {
            currentColumns = finalMode.columns
            persistColumns(finalMode.columns)
        }
        sourceMode = finalMode
        targetMode = finalMode
        transitionProgress = 1f
        transitionVisualProgress = 1f
        transitionScaleFactor = 1f
        boundaryRawOverPull = 0f
        boundaryElasticScale = 1f
        settledTransitionEndpointReached = false
        settledTransitionCommitTarget = null
        isTransitioning = false
    }

    private fun updateBoundaryElastic(rawDelta: Float, expands: Boolean) {
        val settledMode = currentMode
        sourceMode = settledMode
        targetMode = settledMode
        transitionProgress = 1f
        transitionVisualProgress = 1f
        boundaryRawOverPull = if (expands) abs(rawDelta) else -abs(rawDelta)
        boundaryElasticScale = computeBoundaryElasticScale(boundaryRawOverPull)
        transitionScaleFactor = 1f
        isTransitioning = false
    }

    private suspend fun animateBoundaryBack() {
        val generation = boundaryAnimationGeneration
        val start = boundaryRawOverPull
        val startScale = boundaryElasticScale
        if (abs(start) < 0.001f) {
            boundaryRawOverPull = 0f
            boundaryElasticScale = 1f
            return
        }
        val reboundDurationMs = maxOf(
            ELASTIC_REBOUND_DURATION_MS,
            (abs(start) / 2f * 1000f).toInt(),
        )
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = reboundDurationMs, easing = LinearEasing)
        ) { fraction, _ ->
            if (generation == boundaryAnimationGeneration) {
                val eased = cubicEaseOut(fraction)
                boundaryRawOverPull = lerp(start, 0f, eased)
                boundaryElasticScale = lerp(startScale, 1f, eased)
            }
        }
        if (generation == boundaryAnimationGeneration) {
            boundaryRawOverPull = 0f
            boundaryElasticScale = 1f
        }
    }

    /**
     * Consume a pointer delta owned by the detail VirtualList edge.
     *
     * Extending the same edge consumes the complete delta even after the 44dp visual cap is reached,
     * matching the edge-scroll gesture ownership: the same pointer transaction is not handed to a parent.
     * Reversing first drains the accumulated edge pull; only the remainder after zero is returned to
     * normal logical scrolling.
     */
    internal fun consumeVirtualListEdgeDelta(
        deltaPx: Float,
        maxOvershootPx: Float,
    ): Float {
        if (abs(deltaPx) < 0.001f || maxOvershootPx <= 1f) return 0f

        val oldRaw = virtualListEdgeRawPullPx
        val extending = oldRaw == 0f || oldRaw * deltaPx > 0f
        if (extending) {
            val rawCap = virtualListRawPullCapPx(maxOvershootPx)
            virtualListEdgeRawPullPx = (oldRaw + deltaPx).coerceIn(-rawCap, rawCap)
            virtualListEdgeOvershootPx = virtualListVisualOvershootPx(
                rawPullPx = virtualListEdgeRawPullPx,
                maxOvershootPx = maxOvershootPx,
            )
            virtualListEdgeActive = true
            viewportScrollOwner.invalidateRender()
            return deltaPx
        }

        val deltaToZero = -oldRaw
        val consumed = if (abs(deltaPx) <= abs(deltaToZero)) deltaPx else deltaToZero
        val newRaw = oldRaw + consumed
        if (abs(newRaw) < 0.001f) {
            clearVirtualListEdge()
        } else {
            virtualListEdgeRawPullPx = newRaw
            virtualListEdgeOvershootPx = virtualListVisualOvershootPx(
                rawPullPx = newRaw,
                maxOvershootPx = maxOvershootPx,
            )
            virtualListEdgeActive = true
            viewportScrollOwner.invalidateRender()
        }
        return consumed
    }

    internal fun clearVirtualListEdge() {
        val changed = virtualListEdgeRawPullPx != 0f || virtualListEdgeOvershootPx != 0f
        virtualListEdgeRawPullPx = 0f
        virtualListEdgeOvershootPx = 0f
        virtualListEdgeActive = false
        if (changed) viewportScrollOwner.invalidateRender()
    }

    internal suspend fun animateVirtualListEdgeBack() {
        val startRaw = virtualListEdgeRawPullPx
        val startOvershoot = virtualListEdgeOvershootPx
        if (abs(startOvershoot) < 0.5f) {
            clearVirtualListEdge()
            return
        }
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = virtualListEdgeReboundDurationMs(startOvershoot),
                easing = VirtualListEdgeReboundEasing,
            ),
        ) { fraction, _ ->
            virtualListEdgeRawPullPx = lerp(startRaw, 0f, fraction)
            virtualListEdgeOvershootPx = lerp(startOvershoot, 0f, fraction)
            viewportScrollOwner.invalidateRender()
        }
        clearVirtualListEdge()
    }

    private fun nextMode(isZoomIn: Boolean): ComposeVirtualListDisplayMode? =
        adjacentVirtualListMode(currentMode, isZoomIn)

    companion object {
        fun fromContext(
            context: Context,
            namespace: String = "default"
        ): ComposeVirtualListState {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefix = namespace
                .takeIf { it.isNotBlank() && it != "default" }
                ?.let { "${it}_" }
                .orEmpty()
            val zoomKey = prefix + KEY_LIST_ZOOM_LEVEL
            val columnsKey = prefix + KEY_COLUMN_COUNT

            val initialLevel = ListZoomIndex.fromZoomInt(
                prefs.getInt(zoomKey, ListZoomIndex.NORMAL.zoomInt)
            )
            val initialColumns = prefs.getInt(columnsKey, MIN_COLUMNS)
                .coerceIn(MIN_COLUMNS, MAX_COLUMNS)
            return ComposeVirtualListState(
                initialLevel = initialLevel,
                initialColumns = initialColumns,
                persistZoomLevel = { level ->
                    prefs.edit().putInt(zoomKey, level.zoomInt).apply()
                },
                persistColumns = { columns ->
                    prefs.edit().putInt(columnsKey, columns).apply()
                }
            )
        }
    }
}

internal fun paramsFor(level: ListZoomIndex): ListZoomParams = ListZoomLevels.params[level]!!

internal fun displayModeForListLevel(level: ListZoomIndex): ComposeVirtualListDisplayMode = when (level) {
    ListZoomIndex.SMALL -> ComposeVirtualListDisplayMode.LIST_SMALL
    ListZoomIndex.NORMAL -> ComposeVirtualListDisplayMode.LIST_NORMAL
    ListZoomIndex.ZOOMED -> ComposeVirtualListDisplayMode.LIST_ZOOMED
}

internal fun displayModeForColumns(columns: Int, currentLevel: ListZoomIndex): ComposeVirtualListDisplayMode {
    return when {
        columns <= 1 -> displayModeForListLevel(currentLevel)
        columns >= 4 -> ComposeVirtualListDisplayMode.GRID_4
        columns == 3 -> ComposeVirtualListDisplayMode.GRID_3
        else -> ComposeVirtualListDisplayMode.GRID_2
    }
}

private fun adjacentLevel(from: ListZoomIndex, isZoomIn: Boolean): ListZoomIndex {
    return if (isZoomIn) {
        when (from) {
            ListZoomIndex.SMALL -> ListZoomIndex.NORMAL
            ListZoomIndex.NORMAL -> ListZoomIndex.ZOOMED
            ListZoomIndex.ZOOMED -> ListZoomIndex.ZOOMED
        }
    } else {
        when (from) {
            ListZoomIndex.ZOOMED -> ListZoomIndex.NORMAL
            ListZoomIndex.NORMAL -> ListZoomIndex.SMALL
            ListZoomIndex.SMALL -> ListZoomIndex.SMALL
        }
    }
}

private fun modeOrder(mode: ComposeVirtualListDisplayMode): Int = when (mode) {
    ComposeVirtualListDisplayMode.LIST_SMALL -> 0
    ComposeVirtualListDisplayMode.LIST_NORMAL -> 1
    ComposeVirtualListDisplayMode.LIST_ZOOMED -> 2
    ComposeVirtualListDisplayMode.GRID_4 -> 3
    ComposeVirtualListDisplayMode.GRID_3 -> 4
    ComposeVirtualListDisplayMode.GRID_2 -> 5
}

private fun computeBoundaryElasticScale(rawOverPull: Float): Float {
    val easedOffset = computeElasticOverpullOffset(rawOverPull)
    return (1f + easedOffset).coerceIn(0.9105f, 1.0895f)
}

private fun computeElasticOverpullOffset(rawOverPull: Float): Float = when {
    rawOverPull > 0f -> {
        boundaryEasing((rawOverPull.coerceAtMost(3f) / 3f).coerceIn(0f, 1f))
    }
    rawOverPull < 0f -> {
        val reverse = (1f - (1f + rawOverPull).coerceIn(0.1f, 1f)) / 0.9f
        -boundaryEasing(reverse.coerceIn(0f, 1f))
    }
    else -> 0f
}

private fun boundaryEasing(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return sqrt(clamped * 0.2f) * 0.2f
}

/** Android AccelerateDecelerateInterpolator used by transition time settles. */
private fun referenceAccelerateDecelerate(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return ((cos((t + 1f) * PI) / 2.0) + 0.5).toFloat()
}

private fun cubicEaseOut(value: Float): Float {
    val remaining = 1f - value.coerceIn(0f, 1f)
    return 1f - remaining * remaining * remaining
}

private fun lerpUnclamped(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun lerp(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction.coerceIn(0f, 1f)
}
