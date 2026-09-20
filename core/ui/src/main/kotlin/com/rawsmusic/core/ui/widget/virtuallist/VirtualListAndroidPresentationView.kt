package com.rawsmusic.core.ui.widget.virtuallist

import android.annotation.TargetApi
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.RetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import com.rawsmusic.core.ui.widget.text.resolveNativeTextVerticalLayout
import java.util.ArrayDeque
import kotlin.math.roundToInt

private const val HOLDER_APPLY_LAYOUT = 1
private const val HOLDER_APPLY_TRANSLATION = 1 shl 1
private const val HOLDER_APPLY_SCALE = 1 shl 2
private const val HOLDER_APPLY_ALPHA = 1 shl 3
private const val HOLDER_APPLY_DIRECT_FRAME = 1 shl 4
private const val HOLDER_TRACE_ALPHA_EPSILON = 0.0001f
private const val ORDINARY_HOLDER_RESERVE_TARGET = 32

private fun virtualListBitmapDebugLabel(bitmap: Bitmap?): String {
    if (bitmap == null) return "none"
    if (bitmap.isRecycled) return "id=${System.identityHashCode(bitmap)}:recycled"
    val bytes = runCatching { bitmap.allocationByteCount }.getOrDefault(-1)
    return "id=${System.identityHashCode(bitmap)}:${bitmap.width}x${bitmap.height}:" +
        "${bitmap.config?.name ?: "null"}:bytes=$bytes"
}

private data class PhysicalHolderOrder(
    val view: View,
    val group: Int,
    val itemIndex: Int,
    val slotId: Int,
)

@TargetApi(Build.VERSION_CODES.Q)
internal class RawVirtualListPresentationView(
    context: Context,
) : ViewGroup(context), Choreographer.FrameCallback, ViewTreeObserver.OnPreDrawListener, VirtualListPhysicalSharedHost {

    private var runtime: VirtualListSettledNodeRuntime? = null
    private var externalTransform: RetainedSceneItemTransform? = null
    private var presentationVisibleProvider: () -> Boolean = { true }
    private var parentCompositionContext: CompositionContext? = null

    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var noteResources: Resources? = null
    private var noteDrawable: android.graphics.drawable.Drawable? = null
    private val holderViews = LinkedHashMap<Int, RawVirtualListHolderView>()
    private val ordinaryHolderSparePool = ArrayDeque<RawVirtualListHolderView>()
    private val customHolderViews = LinkedHashMap<Int, RawVirtualListCustomHolderView>()
    private val activeSlots = HashSet<Int>()
    private var layoutRequestBarrier: VirtualListLayoutRequestBarrier? = null
    private val pendingArtworkInvalidations = LinkedHashSet<Int>()
    private var promotedHolderView: RawVirtualListHolderView? = null
    private var promotedSourceSlotId: Int = -1
    private var promotedElementId: String = ""
    private var promotedTransitionKey: String = ""
    private var promotedStartRectInWindow: RectF? = null
    private var promotedStartRadiusPx: Float = 0f
    private var promotedCurrentVisibleRadiusPx: Float = 0f
    private var promotedTarget: SharedCoverSnapshot? = null
    private var promotedProgressProvider: (() -> Float)? = null
    private val hostLocationInWindow = IntArray(2)
    private var lastStructureGeneration = Long.MIN_VALUE
    private var syncPending = true
    private var contentPreparationPending = true
    private var framePosted = false
    private var marqueeAcquired = false
    private var drawingOrderDirty = true
    private var childDrawingOrder = IntArray(0)
    private var ordinaryReserveWarmupPosted = false

    private var frameClipEnabled = false
    private var frameClipLeft = 0f
    private var frameClipTop = 0f
    private var frameClipRight = 0f
    private var frameClipBottom = 0f

    init {
        layoutRequestBarrier = VirtualListLayoutRequestBarrier()
        clipChildren = true
        clipToPadding = true
        setChildrenDrawingOrderEnabled(true)
        isClickable = false
        isLongClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun requestLayout() {
        val barrier = layoutRequestBarrier
        if (barrier == null || barrier.requestParentLayoutNow()) super.requestLayout()
    }

    private inline fun <T> withLayoutCommit(
        releaseDeferredParentRequest: Boolean,
        block: () -> T,
    ): T {
        val barrier = layoutRequestBarrier ?: return block()
        barrier.beginCommit()
        return try {
            block()
        } finally {
            val deferred = barrier.endCommit()
            if (deferred && releaseDeferredParentRequest) super.requestLayout()
        }
    }

    fun bind(
        nextRuntime: VirtualListSettledNodeRuntime,
        nextExternalTransform: RetainedSceneItemTransform?,
        nextPresentationVisibleProvider: () -> Boolean,
        nextParentCompositionContext: CompositionContext,
    ) {
        val runtimeChanged = runtime !== nextRuntime
        val transformChanged = externalTransform !== nextExternalTransform
        val visibilityProviderChanged = presentationVisibleProvider !== nextPresentationVisibleProvider
        if (runtimeChanged) {
            runtime?.let { previous ->
                previous.detachInvalidator(this)
                previous.detachPhysicalSharedHost(this)
                releaseResidentChildren(previous)
            }
            runtime = nextRuntime
            nextRuntime.attachPhysicalSharedHost(this)
            noteResources = null
            noteDrawable = null
            lastStructureGeneration = Long.MIN_VALUE
            if (isAttachedToWindow) nextRuntime.attachInvalidator(this, ::invalidateFromRuntime)
        }
        externalTransform = nextExternalTransform
        presentationVisibleProvider = nextPresentationVisibleProvider
        val compositionContextChanged = parentCompositionContext !== nextParentCompositionContext
        parentCompositionContext = nextParentCompositionContext
        if (compositionContextChanged) {
            customHolderViews.values.forEach { it.updateParentCompositionContext(nextParentCompositionContext) }
        }
        if (runtimeChanged || transformChanged || visibilityProviderChanged || compositionContextChanged) scheduleSync()
        if (isAttachedToWindow) {
            val visible = isPresentationVisible()
            updateMarqueeOwnership(visible && nextRuntime.presentationHasMarquee())
            if (!visible) cancelFrame()
            if (visible && nextRuntime.presentationNeedsFrameCallback()) postFrame()
        }
    }

    fun unbind() {
        cancelFrame()
        updateMarqueeOwnership(false)
        runtime?.let { bound ->
            bound.detachInvalidator(this)
            bound.detachPhysicalSharedHost(this)
            releaseResidentChildren(bound)
        }
        runtime = null
        noteResources = null
        noteDrawable = null
        externalTransform = null
        parentCompositionContext = null
        presentationVisibleProvider = { true }
        syncPending = false
        layoutRequestBarrier?.reset()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(this)
        val boundRuntime = runtime ?: return
        boundRuntime.attachInvalidator(this, ::invalidateFromRuntime)
        AppLogger.i(
            VIEW_HOST_TRACE_TAG,
            "VIEWGROUP_ATTACH host=${System.identityHashCode(this)} runtime=${System.identityHashCode(boundRuntime)} holders=${boundRuntime.holderCount}",
        )
        scheduleSync()
        val visible = isPresentationVisible()
        updateMarqueeOwnership(visible && boundRuntime.presentationHasMarquee())
        if (visible && boundRuntime.presentationNeedsFrameCallback()) postFrame()
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
        val boundRuntime = runtime
        if (boundRuntime != null) {
            AppLogger.i(
                VIEW_HOST_TRACE_TAG,
                "VIEWGROUP_DETACH host=${System.identityHashCode(this)} runtime=${System.identityHashCode(boundRuntime)} holders=${boundRuntime.holderCount}",
            )
            boundRuntime.detachInvalidator(this)
        }
        cancelFrame()
        updateMarqueeOwnership(false)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            // A stopped window may lose HWUI display lists without detaching this host.
            // Revalidate content even when neither scrolling nor animation requests a frame.
            scheduleSync()
            for (child in holderViews.values) child.invalidate()
            for (child in customHolderViews.values) child.invalidate()
            promotedHolderView?.invalidate()
            if (isPresentationVisible() && runtime?.presentationNeedsFrameCallback() == true) postFrame()
        } else if (visibility != VISIBLE) {
            cancelFrame()
            updateMarqueeOwnership(false)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        for (child in holderViews.values) {
            child.measure(
                MeasureSpec.makeMeasureSpec(child.desiredWidth.coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.desiredHeight.coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
        }
        for (child in customHolderViews.values) {
            if (child.parent === this) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.desiredWidth.coerceAtLeast(1), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child.desiredHeight.coerceAtLeast(1), MeasureSpec.EXACTLY),
                )
            }
        }
        promotedHolderView?.let { child ->
            child.measure(
                MeasureSpec.makeMeasureSpec(child.desiredWidth.coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.desiredHeight.coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (child in holderViews.values) {
            child.layout(0, 0, child.desiredWidth.coerceAtLeast(1), child.desiredHeight.coerceAtLeast(1))
        }
        for (child in customHolderViews.values) {
            if (child.parent === this) {
                child.layout(0, 0, child.desiredWidth.coerceAtLeast(1), child.desiredHeight.coerceAtLeast(1))
            }
        }
        promotedHolderView?.let { child ->
            child.layout(0, 0, child.desiredWidth.coerceAtLeast(1), child.desiredHeight.coerceAtLeast(1))
        }
    }

    override fun onPreDraw(): Boolean {
        // Prepare/bind before HWUI records the tree. dispatchDraw must never add children,
        // reshape text or request another layout while it is already submitting this frame.
        if (syncPending) syncFromRuntime()
        return true
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        drawingOrderDirty = true
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        drawingOrderDirty = true
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        if (!drawingOrderDirty && childDrawingOrder.size == childCount) {
            childDrawingOrder[drawingPosition]
        } else drawingPosition

    private fun drawOrdinaryChildren(canvas: Canvas, promoted: View) {
        for (position in 0 until childCount) {
            val child = getChildAt(getChildDrawingOrder(childCount, position))
            if (child !== promoted && child.visibility == VISIBLE) drawChild(canvas, child, drawingTime)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val promoted = promotedHolderView
        if (promoted != null && promoted.visibility == VISIBLE) {
            if (frameClipEnabled) {
                val save = canvas.save()
                canvas.clipRect(frameClipLeft, frameClipTop, frameClipRight, frameClipBottom)
                drawOrdinaryChildren(canvas, promoted)
                canvas.restoreToCount(save)
            } else {
                drawOrdinaryChildren(canvas, promoted)
            }
            // VirtualList bringChildToFront(view): the promoted concrete holder is intentionally
            // outside the ordinary viewport clip while it occupies the detail header LayoutRes.
            drawChild(canvas, promoted, drawingTime)
            return
        }
        if (frameClipEnabled) {
            val save = canvas.save()
            canvas.clipRect(frameClipLeft, frameClipTop, frameClipRight, frameClipBottom)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    private fun invalidateFromRuntime(signal: VirtualListRenderInvalidation) {
        if (!isAttachedToWindow) return
        val boundRuntime = runtime ?: return
        val visible = isPresentationVisible()
        updateMarqueeOwnership(visible && boundRuntime.presentationHasMarquee())
        if (!visible) {
            cancelFrame()
            if (signal.kind == VirtualListInvalidationKind.CONTENT) scheduleSync()
            return
        }
        when (signal.kind) {
            VirtualListInvalidationKind.PARENT_TRANSFORM -> {
                applyExternalParentTransform()
                return
            }

            VirtualListInvalidationKind.ARTWORK -> {
                if (
                    syncPending || contentPreparationPending ||
                    layoutRequestBarrier?.isCommitActive == true
                ) {
                    pendingArtworkInvalidations += signal.slotId
                } else if (applyArtworkInvalidation(boundRuntime, signal.slotId)) {
                    if (boundRuntime.presentationNeedsFrameCallback()) postFrame()
                    return
                } else {
                    // The child may be between provider publication and physical attachment. One
                    // normal content sync will bind it; this is never a structure-generation edge.
                    pendingArtworkInvalidations += signal.slotId
                    scheduleSync()
                }
            }

            VirtualListInvalidationKind.MOTION -> {
                boundRuntime.consumeSettledMotionInvalidation()
                if (
                    !syncPending &&
                    !contentPreparationPending &&
                    boundRuntime.settledMotionOwnsPhysicalPresentation() &&
                    withLayoutCommit(releaseDeferredParentRequest = false) {
                        applySettledMotionTick(boundRuntime)
                    }
                ) {
                    invalidate()
                    if (boundRuntime.presentationNeedsFrameCallback()) postFrame()
                    return
                }
                scheduleSync()
            }

            VirtualListInvalidationKind.CONTENT -> scheduleSync()
        }
        if (boundRuntime.presentationNeedsFrameCallback()) postFrame()
    }

    private fun applyExternalParentTransform() {
        val transform = externalTransform
        if (transform == null) {
            resetParentTransform()
            return
        }
        val nextScale = transform.scaleProvider().coerceIn(0.5f, 1.5f)
        val nextAlpha = transform.alphaProvider().coerceIn(0f, 1f)
        if (pivotX != transform.pivotX) pivotX = transform.pivotX
        if (pivotY != transform.pivotY) pivotY = transform.pivotY
        if (scaleX != nextScale) scaleX = nextScale
        if (scaleY != nextScale) scaleY = nextScale
        if (alpha != nextAlpha) alpha = nextAlpha
    }

    private fun applyArtworkInvalidation(
        boundRuntime: VirtualListSettledNodeRuntime,
        slotId: Int,
    ): Boolean {
        if (slotId < 0) return false
        val child = holderViews[slotId] ?: return false
        if (child.parent !== this) return false
        val state = boundRuntime.prepareArtworkViewGroupState(slotId) ?: return false
        child.applyArtworkState(state)
        if (promotedSourceSlotId == slotId) {
            promotedHolderView?.setPromotedArtworkBitmap(
                boundRuntime.displayedPromotedSharedArtworkBitmap()
            )
        }
        return true
    }

    private fun flushPendingArtworkInvalidations(boundRuntime: VirtualListSettledNodeRuntime) {
        if (pendingArtworkInvalidations.isEmpty()) return
        val iterator = pendingArtworkInvalidations.iterator()
        while (iterator.hasNext()) {
            val slotId = iterator.next()
            if (applyArtworkInvalidation(boundRuntime, slotId)) iterator.remove()
        }
    }

    private fun scheduleSync() {
        contentPreparationPending = true
        syncPending = true
        invalidate()
    }

    private fun bindOrdinaryHolderInput(holder: RawVirtualListHolderView) {
        holder.bindPhysicalInput(
            onClick = { slotId -> runtime?.onPhysicalHolderClick(slotId) },
            onLongClick = { slotId, x, y -> runtime?.onPhysicalHolderLongClick(slotId, x, y) },
        )
    }

    private fun obtainOrdinaryHolder(
        slotId: Int,
        desiredIndex: Int,
        width: Int,
        height: Int,
    ): RawVirtualListHolderView {
        holderViews[slotId]?.let { return it }
        val child = if (ordinaryHolderSparePool.isEmpty()) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_HOLDER_COLD_CREATE,
            )
            RawVirtualListHolderView(context)
        } else {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_HOLDER_POOL_HIT,
            )
            ordinaryHolderSparePool.removeFirst()
        }
        child.slotId = slotId
        bindOrdinaryHolderInput(child)
        holderViews[slotId] = child
        if (child.parent !== this) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_CHILD_ADD,
            )
            addViewInLayout(
                child,
                desiredIndex.coerceAtMost(childCount),
                LayoutParams(width.coerceAtLeast(1), height.coerceAtLeast(1)),
                true,
            )
        }
        return child
    }

    /**
     * reference player keeps a holder bank/holder slot bank independent from the current provider. Raw used to allocate the
     * first ordinary Android holder population only after HOME -> category navigation had already
     * entered PREPARE, producing 20+ addView/measure operations on the transition critical path.
     * Build fresh, content-free holder shells one idle vsync at a time instead. They stay attached
     * and INVISIBLE, so the first category transition only binds slot/LayoutRes/content ownership.
     */
    private fun scheduleOrdinaryHolderReserveWarmup(boundRuntime: VirtualListSettledNodeRuntime) {
        if (ordinaryReserveWarmupPosted) return
        if (!isAttachedToWindow || !isPresentationVisible()) return
        if (holderViews.size + ordinaryHolderSparePool.size >= ORDINARY_HOLDER_RESERVE_TARGET) return
        ordinaryReserveWarmupPosted = true
        postOnAnimation {
            ordinaryReserveWarmupPosted = false
            if (!isAttachedToWindow || runtime !== boundRuntime || !isPresentationVisible()) return@postOnAnimation
            if (
                syncPending ||
                contentPreparationPending ||
                layoutRequestBarrier?.isCommitActive == true ||
                boundRuntime.outerMotionOwnsPhysicalPresentation() ||
                boundRuntime.presentationInteractionActive()
            ) {
                return@postOnAnimation
            }
            if (holderViews.size + ordinaryHolderSparePool.size >= ORDINARY_HOLDER_RESERVE_TARGET) {
                return@postOnAnimation
            }
            val spare = RawVirtualListHolderView(context).also { holder ->
                holder.slotId = -1
                bindOrdinaryHolderInput(holder)
                holder.visibility = INVISIBLE
                holder.alpha = 0f
            }
            ordinaryHolderSparePool.addLast(spare)
            addView(spare, LayoutParams(1, 1))
            scheduleOrdinaryHolderReserveWarmup(boundRuntime)
        }
    }

    private fun syncFromRuntime() {
        withLayoutCommit(releaseDeferredParentRequest = true) {
            syncFromRuntimeInsideLayoutCommit()
        }
    }

    private fun syncFromRuntimeInsideLayoutCommit() {
        syncPending = false
        val boundRuntime = runtime ?: run {
            hideAllChildren()
            return
        }
        if (!isPresentationVisible()) {
            hideAllChildren()
            updateMarqueeOwnership(false)
            resetParentTransform()
            return
        }
        val resources = boundRuntime.presentationResources()
        if (resources !== noteResources) {
            noteResources = resources
            noteDrawable = resources?.getDrawable(R.drawable.ic_music_note, null)?.mutate()
        }
        // Runtime publication/bitmap arrival dirties content; pure pivot frames only move the
        // existing child Views. Marquee remains a content-property consumer while active.
        if (contentPreparationPending) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_CONTENT_DIRTY)
            contentPreparationPending = false
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.measure(
                com.rawsmusic.core.ui.perf.TransitionPerfStage.VIEW_CONTENT_PREPARE,
            ) { boundRuntime.prepareViewGroupContent(rowPaint, noteDrawable) }
        }
        val syncStarted = if (com.rawsmusic.core.ui.perf.TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        // VirtualList binds NEXT physical holder Views into the same ViewGroup before GenericPivot starts.
        // Materialize Raw's preflighted NEXT children first (alpha-zero), then resolve the live
        // CURRENT/OUTER frame. If a slot has already become live, the live state below wins.
        val preparedStates = boundRuntime.prepareDetachedPrewarmViewGroupStates(
            rowPaint = rowPaint,
            noteDrawable = noteDrawable,
        )
        val preparedCustomStates = boundRuntime.prepareDetachedPrewarmCustomViewGroupStates()
        val frame = boundRuntime.prepareViewGroupPresentation(
            rowPaint = rowPaint,
            noteDrawable = noteDrawable,
            externalTransform = externalTransform,
        )
        frameClipEnabled = frame.clipEnabled
        frameClipLeft = frame.clipLeft
        frameClipTop = frame.clipTop
        frameClipRight = frame.clipRight
        frameClipBottom = frame.clipBottom
        if (pivotX != frame.parentPivotX) pivotX = frame.parentPivotX
        if (pivotY != frame.parentPivotY) pivotY = frame.parentPivotY
        if (scaleX != frame.parentScale) scaleX = frame.parentScale
        if (scaleY != frame.parentScale) scaleY = frame.parentScale
        if (alpha != frame.parentAlpha) alpha = frame.parentAlpha

        val liveSlotIds = HashSet<Int>(frame.holders.size + frame.customHolders.size)
        for (state in frame.holders) liveSlotIds += state.slotId
        for (state in frame.customHolders) liveSlotIds += state.slotId
        val preparedOnlyCount = preparedStates.count { it.slotId !in liveSlotIds }
        val preparedCustomOnlyCount = preparedCustomStates.count { it.slotId !in liveSlotIds }
        val previousActiveSlotCount = activeSlots.size
        val structureChanged = shouldReconcilePresentationStructure(
            lastStructureGeneration,
            frame.structureGeneration,
            previousActiveSlotCount,
            frame.holders.size + frame.customHolders.size + preparedOnlyCount + preparedCustomOnlyCount,
        )
        // LayoutState ownership changes even when the resident child structure does not. Rebuild
        // the live-slot set on every content commit so source-only endpoint Views can be parked
        // immediately after cancel/commit instead of remaining touchable until another structural
        // generation. Keep the holder resident but INVISIBLE.
        activeSlots.clear()
        val holderApplyStarted = if (com.rawsmusic.core.ui.perf.TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        var translationMutations = 0L
        var scaleMutations = 0L
        var alphaMutations = 0L
        for ((desiredIndex, state) in frame.holders.withIndex()) {
            ensureOrdinaryHolderType(boundRuntime, state.slotId)
            activeSlots += state.slotId
            val child = obtainOrdinaryHolder(
                slotId = state.slotId,
                desiredIndex = desiredIndex,
                width = state.width,
                height = state.height,
            )
            val applyMask = child.applyState(state)
            if ((applyMask and HOLDER_APPLY_TRANSLATION) != 0) translationMutations += 1L
            if ((applyMask and HOLDER_APPLY_SCALE) != 0) scaleMutations += 1L
            if ((applyMask and HOLDER_APPLY_ALPHA) != 0) alphaMutations += 1L
            if ((applyMask and HOLDER_APPLY_LAYOUT) != 0 || !child.isLaidOut) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                    com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_HOLDER_RELAYOUT,
                )
                // The retained LayoutRes already resolved the exact size. Do not start a second
                // parent traversal just to apply it to this physical holder.
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.desiredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child.desiredHeight, MeasureSpec.EXACTLY),
                )
                child.layout(0, 0, child.desiredWidth, child.desiredHeight)
            }
        }
        val customParentContext = parentCompositionContext
        for ((customIndex, state) in frame.customHolders.withIndex()) {
            ensureCustomHolderType(boundRuntime, state.slotId)
            activeSlots += state.slotId
            val desiredIndex = frame.holders.size + customIndex
            val child = customHolderViews[state.slotId] ?: RawVirtualListCustomHolderView(context).also {
                it.slotId = state.slotId
                customParentContext?.let(it::updateParentCompositionContext)
                customHolderViews[state.slotId] = it
            }
            if (child.parent !== this) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                    com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_CHILD_ADD,
                )
                addViewInLayout(
                    child,
                    desiredIndex.coerceAtMost(childCount),
                    LayoutParams(state.width.coerceAtLeast(1), state.height.coerceAtLeast(1)),
                    true,
                )
            }
            val applyMask = child.applyState(state)
            if ((applyMask and HOLDER_APPLY_TRANSLATION) != 0) translationMutations += 1L
            if ((applyMask and HOLDER_APPLY_SCALE) != 0) scaleMutations += 1L
            if ((applyMask and HOLDER_APPLY_ALPHA) != 0) alphaMutations += 1L
            if ((applyMask and HOLDER_APPLY_LAYOUT) != 0 || !child.isLaidOut) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                    com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_HOLDER_RELAYOUT,
                )
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.desiredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child.desiredHeight, MeasureSpec.EXACTLY),
                )
                child.layout(0, 0, child.desiredWidth, child.desiredHeight)
            }
        }
        for (state in preparedCustomStates) {
            if (state.slotId in liveSlotIds) continue
            ensureCustomHolderType(boundRuntime, state.slotId)
            activeSlots += state.slotId
            val child = customHolderViews[state.slotId] ?: RawVirtualListCustomHolderView(context).also {
                it.slotId = state.slotId
                customParentContext?.let(it::updateParentCompositionContext)
                customHolderViews[state.slotId] = it
            }
            if (child.parent !== this) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                    com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_CHILD_ADD,
                )
                addViewInLayout(
                    child,
                    childCount,
                    LayoutParams(state.width.coerceAtLeast(1), state.height.coerceAtLeast(1)),
                    true,
                )
            }
            val applyMask = child.applyState(state)
            // reference player binds NEXT into a live holder slot/View before the transition progress step starts.  The holder stays
            // VISIBLE and the transition LayoutRes owns visibility through alpha=0; only a holder
            // which is no longer part of CURRENT/NEXT is made INVISIBLE by the parking pass below.
            // This preserves the same View/render owner. GPU texture residency is measured
            // separately; VISIBLE+alpha=0 by itself is not treated as proof of pre-upload.
            if (child.visibility != VISIBLE) child.visibility = VISIBLE
            if (child.alpha != 0f) child.alpha = 0f
            if ((applyMask and HOLDER_APPLY_TRANSLATION) != 0) translationMutations += 1L
            if ((applyMask and HOLDER_APPLY_SCALE) != 0) scaleMutations += 1L
            if ((applyMask and HOLDER_APPLY_ALPHA) != 0) alphaMutations += 1L
            if ((applyMask and HOLDER_APPLY_LAYOUT) != 0 || !child.isLaidOut) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.desiredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child.desiredHeight, MeasureSpec.EXACTLY),
                )
                child.layout(0, 0, child.desiredWidth, child.desiredHeight)
            }
        }
        // NEXT holders are already fully bound/prepared. Do not mark those holders inactive:
        // leave the View VISIBLE while GenericPivot's LayoutRes starts
        // it at alpha=0.  Match that distinction here; the later parking pass is reserved for truly
        // inactive resident holders which are outside both CURRENT and NEXT.
        for (state in preparedStates) {
            if (state.slotId in liveSlotIds) continue
            ensureOrdinaryHolderType(boundRuntime, state.slotId)
            activeSlots += state.slotId
            val child = obtainOrdinaryHolder(
                slotId = state.slotId,
                desiredIndex = childCount,
                width = state.width,
                height = state.height,
            )
            val applyMask = child.applyState(state)
            if (child.visibility != VISIBLE) child.visibility = VISIBLE
            if (child.alpha != 0f) child.alpha = 0f
            child.markPreparedRenderResident(state)
            if ((applyMask and HOLDER_APPLY_TRANSLATION) != 0) translationMutations += 1L
            if ((applyMask and HOLDER_APPLY_SCALE) != 0) scaleMutations += 1L
            if ((applyMask and HOLDER_APPLY_ALPHA) != 0) alphaMutations += 1L
            if ((applyMask and HOLDER_APPLY_LAYOUT) != 0 || !child.isLaidOut) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                    com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_HOLDER_RELAYOUT,
                )
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.desiredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child.desiredHeight, MeasureSpec.EXACTLY),
                )
                child.layout(0, 0, child.desiredWidth, child.desiredHeight)
            }
        }
        if (
            preparedStates.isNotEmpty() &&
            preparedStates.all { state -> holderViews[state.slotId]?.parent === this }
        ) {
            boundRuntime.acknowledgeDetachedOrdinaryPrewarmMaterialized()
        }
        if (holderApplyStarted != 0L) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_TRANSLATION_MUTATION,
                translationMutations,
            )
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_SCALE_MUTATION,
                scaleMutations,
            )
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_ALPHA_MUTATION,
                alphaMutations,
            )
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.recordDuration(
                com.rawsmusic.core.ui.perf.TransitionPerfStage.VIEW_HOLDER_STATE_APPLY,
                System.nanoTime() - holderApplyStarted,
            )
        }

        // Endpoint role commit is not a structure mutation. Park every resident child that is no
        // longer owned by the live/prepared LayoutState even when structureGeneration is unchanged.
        // This is the Android-View resident-but-invisible holder state.
        var parkedOwnerCount = 0
        for ((slotId, child) in holderViews) {
            if (slotId in activeSlots || child === promotedHolderView) continue
            if (child.visibility != INVISIBLE) {
                child.visibility = INVISIBLE
                parkedOwnerCount += 1
            }
        }
        for ((slotId, child) in customHolderViews) {
            if (slotId in activeSlots) continue
            if (child.visibility != INVISIBLE) {
                child.visibility = INVISIBLE
                parkedOwnerCount += 1
            }
        }
        if (parkedOwnerCount > 0 && com.rawsmusic.core.ui.perf.TransitionPerfTrace.isActive()) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.mark(
                "view_owner_park",
                "count=$parkedOwnerCount live=${liveSlotIds.size} resident=${holderViews.size + customHolderViews.size}",
            )
        }

        if (structureChanged) {
            val reconcileStarted = if (com.rawsmusic.core.ui.perf.TransitionPerfTrace.isActive()) System.nanoTime() else 0L
            val desiredOrder = ArrayList<PhysicalHolderOrder>(
                frame.holders.size + frame.customHolders.size + preparedOnlyCount + preparedCustomOnlyCount
            )
            for (state in frame.holders) {
                holderViews[state.slotId]?.takeIf { it.parent === this }?.let { child ->
                    desiredOrder += PhysicalHolderOrder(
                        view = child,
                        group = state.drawOrderGroup,
                        itemIndex = state.drawOrderIndex,
                        slotId = state.slotId,
                    )
                }
            }
            for (state in frame.customHolders) {
                customHolderViews[state.slotId]?.takeIf { it.parent === this }?.let { child ->
                    desiredOrder += PhysicalHolderOrder(
                        view = child,
                        group = state.drawOrderGroup,
                        itemIndex = state.drawOrderIndex,
                        slotId = state.slotId,
                    )
                }
            }
            for (state in preparedStates) {
                if (state.slotId in liveSlotIds) continue
                holderViews[state.slotId]?.takeIf { it.parent === this }?.let { child ->
                    desiredOrder += PhysicalHolderOrder(
                        view = child,
                        group = 3,
                        itemIndex = state.drawOrderIndex,
                        slotId = state.slotId,
                    )
                }
            }
            for (state in preparedCustomStates) {
                if (state.slotId in liveSlotIds) continue
                customHolderViews[state.slotId]?.takeIf { it.parent === this }?.let { child ->
                    desiredOrder += PhysicalHolderOrder(
                        view = child,
                        group = 3,
                        itemIndex = state.drawOrderIndex,
                        slotId = state.slotId,
                    )
                }
            }
            desiredOrder.sortWith(
                compareBy<PhysicalHolderOrder> { it.group }
                    .thenBy { it.itemIndex }
                    .thenBy { it.slotId }
            )
            // Different reference player provider identity providers retain different holder bank banks. Their resident Views do
            // not need to be physically shuffled to child index 0 whenever CURRENT changes. Keep
            // Raw's resident child array stable and express the active LayoutState ordering through
            // ViewGroup drawing order instead. This also keeps reverse/cancel gestures from doing a
            // temporary-detach/attach sweep over the complete category population.
            val preferredDrawingIndices = desiredOrder.mapNotNull { entry ->
                indexOfChild(entry.view).takeIf { it >= 0 }
            }
            if (reconcileStarted != 0L) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.recordDuration(
                    com.rawsmusic.core.ui.perf.TransitionPerfStage.VIEW_STRUCTURE_RECONCILE,
                    System.nanoTime() - reconcileStarted,
                )
            }
            lastStructureGeneration = frame.structureGeneration
            childDrawingOrder = resolveVirtualListChildDrawingOrder(
                count = childCount,
                preferred = preferredDrawingIndices,
            )
            drawingOrderDirty = false
            if (com.rawsmusic.core.ui.perf.TransitionPerfTrace.isActive()) {
                var attachedHolders = 0
                var visibleHolders = 0
                var alphaZeroHolders = 0
                for (index in 0 until childCount) {
                    val holder = getChildAt(index)
                    if (holder !is RawVirtualListHolderView && holder !is RawVirtualListCustomHolderView) continue
                    attachedHolders += 1
                    if (holder.visibility == VISIBLE) visibleHolders += 1
                    if (holder.alpha <= 0.0001f) alphaZeroHolders += 1
                }
                AppLogger.i(
                    "SceneHandoff",
                    "${com.rawsmusic.core.ui.perf.TransitionPerfTrace.exportContext()} VIEWGROUP_STRUCTURE " +
                        "generation=${frame.structureGeneration} childCount=$childCount attachedHolders=$attachedHolders " +
                        "holderViews=${holderViews.size} customViews=${customHolderViews.size} " +
                        "live=${frame.holders.size}+${frame.customHolders.size} preparedOnly=$preparedOnlyCount " +
                        "visible=$visibleHolders alpha0=$alphaZeroHolders activeSlots=${activeSlots.size}",
                )
            }
        } else if (drawingOrderDirty) {
            childDrawingOrder = IntArray(childCount) { it }
            drawingOrderDirty = false
        }
        applyPromotedGeometry()
        promotedHolderView?.setPromotedArtworkBitmap(
            boundRuntime.displayedPromotedSharedArtworkBitmap()
        )
        flushPendingArtworkInvalidations(boundRuntime)
        updateMarqueeOwnership(boundRuntime.presentationHasMarquee())
        if (boundRuntime.presentationNeedsFrameCallback() || promotedProgressProvider != null) postFrame()
        scheduleOrdinaryHolderReserveWarmup(boundRuntime)
        if (syncStarted != 0L) com.rawsmusic.core.ui.perf.TransitionPerfTrace.recordDuration(
            com.rawsmusic.core.ui.perf.TransitionPerfStage.VIEW_PROPERTY_SYNC, System.nanoTime() - syncStarted)
    }

    /**
     * Apply one ordinary scroll/edge tick directly to the attached holder Views.
     *
     * The provider population and local child scene are unchanged while the viewport moves. This
     * is the settled equivalent of VirtualList applying a new LayoutRes translation to existing physical holders
     * holders: no prewarm query, live-slot set, provider publication or child reconciliation.
     */
    private fun applySettledMotionTick(boundRuntime: VirtualListSettledNodeRuntime): Boolean {
        val states = boundRuntime.prepareSettledMotionViewGroupStates() ?: return false
        val customStates = boundRuntime.prepareSettledCustomMotionViewGroupStates() ?: return false
        for (state in states) {
            if (holderViews[state.slotId] == null) return false
        }
        for (state in customStates) {
            val child = customHolderViews[state.slotId] ?: return false
            if (child.parent !== this) return false
        }
        for (state in states) {
            holderViews[state.slotId]?.applyMotionState(state) ?: return false
        }
        for (state in customStates) {
            customHolderViews[state.slotId]?.applyMotionState(state) ?: return false
        }
        applyPromotedGeometry()
        return true
    }

    /**
     * Apply one GenericPivot progress tick to the already attached physical holder Views.
     *
     * reference player does not re-run VirtualList child reconciliation for a progress tick. Its layout
     * manager mutates the two LayoutRes records on each holder and the retained-layout commit path writes only that
     * LayoutRes to the physical holder. Keep this path equally narrow: no provider/content/prewarm/structure work.
     */
    private fun applyOuterMotionTick(boundRuntime: VirtualListSettledNodeRuntime): Boolean {
        val states = boundRuntime.prepareOuterMotionViewGroupStates() ?: return false
        val customStates = boundRuntime.prepareOuterCustomMotionViewGroupStates() ?: return false

        // Never partially commit a frame. A missing child means publication/structure ownership
        // changed and the normal sync path must atomically reconcile before animation continues.
        for (state in states) {
            if (holderViews[state.slotId] == null) return false
        }
        for (state in customStates) {
            val child = customHolderViews[state.slotId] ?: return false
            if (child.parent !== this) return false
        }

        val applyStarted = if (com.rawsmusic.core.ui.perf.TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        var translationMutations = 0L
        var scaleMutations = 0L
        var alphaMutations = 0L
        for (state in states) {
            val child = holderViews[state.slotId] ?: return false
            val applyMask = child.applyMotionState(state)
            // The retained-layout commit path writes translationX/Y for every LayoutRes commit.
            translationMutations += 1L
            if ((applyMask and HOLDER_APPLY_SCALE) != 0) scaleMutations += 1L
            if ((applyMask and HOLDER_APPLY_ALPHA) != 0) alphaMutations += 1L
            if ((applyMask and HOLDER_APPLY_LAYOUT) != 0) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                    com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_HOLDER_RELAYOUT,
                )
            }
            if ((applyMask and HOLDER_APPLY_DIRECT_FRAME) != 0) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                    com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_HOLDER_DIRECT_FRAME,
                )
            }
        }
        for (state in customStates) {
            val child = customHolderViews[state.slotId] ?: return false
            val applyMask = child.applyMotionState(state)
            translationMutations += 1L
            if ((applyMask and HOLDER_APPLY_SCALE) != 0) scaleMutations += 1L
            if ((applyMask and HOLDER_APPLY_ALPHA) != 0) alphaMutations += 1L
            if ((applyMask and HOLDER_APPLY_LAYOUT) != 0) {
                com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                    com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_HOLDER_RELAYOUT,
                )
            }
        }
        applyPromotedGeometry()
        promotedHolderView?.setPromotedArtworkBitmap(boundRuntime.displayedPromotedSharedArtworkBitmap())
        if (applyStarted != 0L) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_TRANSLATION_MUTATION,
                translationMutations,
            )
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_SCALE_MUTATION,
                scaleMutations,
            )
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_ALPHA_MUTATION,
                alphaMutations,
            )
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.recordDuration(
                com.rawsmusic.core.ui.perf.TransitionPerfStage.VIEW_HOLDER_STATE_APPLY,
                System.nanoTime() - applyStarted,
            )
        }
        return true
    }

    private fun ensureOrdinaryHolderType(
        boundRuntime: VirtualListSettledNodeRuntime,
        slotId: Int,
    ) {
        val custom = customHolderViews.remove(slotId) ?: return
        if (custom.parent === this) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_CHILD_REMOVE,
            )
            removeViewInLayout(custom)
        }
        custom.release()
        boundRuntime.invalidatePrewarmResidencyForSlot(slotId)
    }

    private fun ensureCustomHolderType(
        boundRuntime: VirtualListSettledNodeRuntime,
        slotId: Int,
    ) {
        val ordinary = holderViews[slotId] ?: return
        if (ordinary === promotedHolderView) return
        holderViews.remove(slotId)
        if (ordinary.parent === this) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.VIEW_CHILD_REMOVE,
            )
            boundRuntime.onPhysicalHolderRealDetach(slotId)
            removeViewInLayout(ordinary)
        }
        boundRuntime.invalidatePrewarmResidencyForSlot(slotId)
    }

    /**
     * True owner teardown. This is intentionally the only bulk-release path: viewport churn keeps
     * holder Views resident, while runtime replacement/unbind releases bitmap leases/compositions.
     */
    private fun releaseResidentChildren(boundRuntime: VirtualListSettledNodeRuntime) {
        ordinaryReserveWarmupPosted = false
        val releasedOrdinarySlots = HashSet<Int>(holderViews.size)
        for ((slotId, child) in holderViews) {
            releasedOrdinarySlots += slotId
            boundRuntime.onPhysicalHolderRealDetach(slotId)
            if (child.parent === this) removeView(child)
        }
        holderViews.clear()

        while (ordinaryHolderSparePool.isNotEmpty()) {
            val spare = ordinaryHolderSparePool.removeFirst()
            if (spare.parent === this) removeView(spare)
        }

        for ((slotId, child) in customHolderViews) {
            child.release()
            boundRuntime.invalidatePrewarmResidencyForSlot(slotId)
            if (child.parent === this) removeView(child)
        }
        customHolderViews.clear()

        promotedHolderView?.let { promoted ->
            val slotId = promotedSourceSlotId
            if (slotId >= 0 && slotId !in releasedOrdinarySlots) {
                boundRuntime.onPhysicalHolderRealDetach(slotId)
            }
            if (promoted.parent === this) removeView(promoted)
        }
        clearPromotionState()
        activeSlots.clear()
        pendingArtworkInvalidations.clear()
        lastStructureGeneration = Long.MIN_VALUE
        childDrawingOrder = IntArray(0)
        drawingOrderDirty = true
    }

    private fun moveChildToIndexVirtualListStyle(child: View, requestedIndex: Int) {
        if (child.parent !== this) return
        val currentIndex = indexOfChild(child)
        if (currentIndex < 0 || currentIndex == requestedIndex) return
        val layoutParams = child.layoutParams ?: LayoutParams(
            child.measuredWidth.coerceAtLeast(1),
            child.measuredHeight.coerceAtLeast(1),
        )
        child.dispatchStartTemporaryDetach()
        detachViewFromParent(child)
        val targetIndex = requestedIndex.coerceIn(0, childCount)
        attachViewToParent(child, targetIndex, layoutParams)
        child.dispatchFinishTemporaryDetach()
    }

    private fun hideAllChildren() {
        for (child in holderViews.values) if (child.visibility != INVISIBLE) child.visibility = INVISIBLE
        for (child in customHolderViews.values) if (child.visibility != INVISIBLE) child.visibility = INVISIBLE
        promotedHolderView?.let { if (it.visibility != INVISIBLE) it.visibility = INVISIBLE }
        frameClipEnabled = false
    }

    private fun resetParentTransform() {
        val px = width * 0.5f
        val py = height * 0.5f
        if (pivotX != px) pivotX = px
        if (pivotY != py) pivotY = py
        if (scaleX != 1f) scaleX = 1f
        if (scaleY != 1f) scaleY = 1f
        if (alpha != 1f) alpha = 1f
    }

    private fun isPresentationVisible(): Boolean = presentationVisibleProvider()

    private fun updateMarqueeOwnership(needed: Boolean) {
        if (needed == marqueeAcquired) return
        marqueeAcquired = needed
        if (needed) LongTextMotionState.acquireMarquee() else LongTextMotionState.releaseMarquee()
    }

    private fun postFrame() {
        if (!isAttachedToWindow || framePosted || !isPresentationVisible()) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun cancelFrame() {
        if (!framePosted) return
        Choreographer.getInstance().removeFrameCallback(this)
        framePosted = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!isAttachedToWindow) return
        val boundRuntime = runtime ?: return
        if (!isPresentationVisible()) {
            updateMarqueeOwnership(false)
            scheduleSync()
            return
        }
        boundRuntime.advancePresentation(frameTimeNanos)
        // reference player commits both GenericPivot and attached-child local animation clocks directly to
        // the existing View population. Once the physical children are published, a marquee tick
        // or artwork-view replacement fade is not a reason to re-run provider/content/structure
        // synchronization. CONTENT invalidation remains the only path which may do that work.
        val appliedLayoutResDirectly = if (!syncPending && !contentPreparationPending) {
            withLayoutCommit(releaseDeferredParentRequest = false) {
                when {
                    boundRuntime.outerMotionOwnsPhysicalPresentation() ->
                        applyOuterMotionTick(boundRuntime)
                    boundRuntime.settledMotionOwnsPhysicalPresentation() ->
                        applySettledMotionTick(boundRuntime)
                    else -> false
                }
            }
        } else {
            false
        }
        if (!appliedLayoutResDirectly) syncFromRuntime()
        if (boundRuntime.presentationNeedsFrameCallback() || promotedProgressProvider != null) postFrame()
    }

    override fun promoteSlot(slotId: Int, elementId: String): Boolean {
        if (promotedHolderView != null) return promotedSourceSlotId == slotId && promotedElementId == elementId
        val child = holderViews[slotId] ?: return false
        if (child.coverWidth <= 0 || child.coverHeight <= 0) {
            return false
        }

        // ItemToHeader must never leave the source row without a concrete text/meta owner for one
        // frame. The promoted actor is the existing physical child View, but before clipping it down
        // to the artwork lane synchronously materialize a replacement row View which shares the
        // already-recorded presentation state. Runtime will replace its contentNode with the
        // artwork-suppressed row on the next sync; until then the duplicate cover is exactly under
        // the promoted actor and therefore invisible, while title/subtitle/meta never disappear.
        val childIndex = indexOfChild(child).coerceAtLeast(0)
        RawVirtualListHolderView(context).also { row ->
            row.slotId = slotId
            bindOrdinaryHolderInput(row)
            row.adoptPresentationStateFrom(child)
            holderViews[slotId] = row
            addView(row, childIndex)
            row.measure(
                MeasureSpec.makeMeasureSpec(row.desiredWidth.coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(row.desiredHeight.coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
            row.layout(0, 0, row.desiredWidth.coerceAtLeast(1), row.desiredHeight.coerceAtLeast(1))
        }
        promotedHolderView = child
        promotedSourceSlotId = slotId
        promotedElementId = elementId
        promotedTransitionKey = ""
        promotedProgressProvider = null
        promotedTarget = null
        promotedStartRectInWindow = currentPromotedCoverRectInWindow(child)
        promotedStartRadiusPx = child.coverRadiusPx * minOf(child.scaleX, child.scaleY).coerceAtLeast(0.001f)
        promotedCurrentVisibleRadiusPx = promotedStartRadiusPx
        child.clipBounds = Rect(
            child.coverLeft,
            child.coverTop,
            child.coverLeft + child.coverWidth,
            child.coverTop + child.coverHeight,
        )
        child.setPromotedClipRadius(child.coverRadiusPx)
        child.alpha = 1f
        child.visibility = VISIBLE

        // The runtime has already suppressed only this holder's artwork lane. Materialize the
        // ordinary source-row child *synchronously* before the original child is exposed as the
        // clipped promoted actor. Waiting for scheduleSync()/the next draw leaves one real frame in
        // which the original row is clipped to its cover while no replacement owns its text/meta.
        // That is the category-text flash at ItemToHeader start.
        syncPending = true
        syncFromRuntime()
        AppLogger.i(
            VIEW_HOST_TRACE_TAG,
            "SHARED_PROMOTE_ATOMIC slot=$slotId element=$elementId replacement=${holderViews[slotId] != null}",
        )
        requestLayout()
        invalidate()
        return true
    }

    override fun updatePromotedGeometry(
        transitionKey: String,
        target: SharedCoverSnapshot,
        progressProvider: (() -> Float)?,
        animateFromCurrent: Boolean,
    ) {
        val child = promotedHolderView ?: return
        if (target.elementId != promotedElementId) return
        if (animateFromCurrent && promotedTransitionKey != transitionKey) {
            promotedStartRectInWindow = currentPromotedCoverRectInWindow(child)
            promotedStartRadiusPx = promotedCurrentVisibleRadiusPx
        }
        promotedTransitionKey = transitionKey
        promotedTarget = target
        promotedProgressProvider = progressProvider
        applyPromotedGeometry()
        invalidate()
        if (progressProvider != null) postFrame()
    }

    override fun restorePromotedSlot(slotId: Int) {
        val promoted = promotedHolderView ?: return
        // Runtime has already rebound the pinned NodeHolder to the destination settled frame before
        // calling us. Materialize that destination child state synchronously if the host has not
        // produced it yet; VirtualList never waits one more vsync between B.f(..., true) and the
        // restored View geometry becoming authoritative.
        if (holderViews[slotId] == null) {
            syncPending = true
            syncFromRuntime()
        }
        val temporary = holderViews.remove(slotId)
        if (temporary != null && temporary !== promoted) {
            // VirtualList HeaderToItem restores the concrete View into the destination holder slot only after
            // that destination LayoutRes is authoritative. Mirror that atomic handoff here: copy
            // the already-present destination child state onto the promoted concrete View before
            // dropping header clip/ownership. This leaves no frame where the same View has list
            // ownership but still carries the full-size header translation/scale.
            promoted.adoptPresentationStateFrom(temporary)
            removeView(temporary)
        }
        promoted.setPromotedClipRadius(null)
        promoted.clipBounds = null
        promoted.slotId = slotId
        promoted.alpha = 1f
        holderViews[slotId] = promoted
        clearPromotionState()
        // Complete HeaderToItem as one physical-owner transaction. The temporary destination row
        // intentionally has its artwork lane suppressed while the promoted child is in flight, so
        // clearing the promoted raster before a runtime sync exposes one blank/default-art frame.
        // Runtime has already rebound the pinned NodeHolder above; materialize that authoritative
        // content synchronously while the promoted raster is still alive, then drop the promotion
        // overlay only after the normal holder display list contains artwork again.
        syncPending = true
        syncFromRuntime()
        promoted.setPromotedArtworkBitmap(null)
        requestLayout()
        invalidate()
    }

    override fun clearPromotedSlot(): Boolean {
        val promoted = promotedHolderView
        val promotedId = promotedElementId
        if (promoted != null) {
            promoted.setPromotedArtworkBitmap(null)
            promoted.setPromotedClipRadius(null)
            removeView(promoted)
        }
        AppLogger.i(
            VIEW_HOST_TRACE_TAG,
            "SHARED_CLEAR_PROMOTED hadView=${promoted != null} element=$promotedId",
        )
        clearPromotionState()
        requestLayout()
        invalidate()
        return promoted != null
    }

    private fun clearPromotionState() {
        promotedHolderView = null
        promotedSourceSlotId = -1
        promotedElementId = ""
        promotedTransitionKey = ""
        promotedStartRectInWindow = null
        promotedStartRadiusPx = 0f
        promotedCurrentVisibleRadiusPx = 0f
        promotedTarget = null
        promotedProgressProvider = null
    }

    private fun currentPromotedCoverRectInWindow(child: RawVirtualListHolderView): RectF {
        getLocationInWindow(hostLocationInWindow)
        val sx = child.scaleX.coerceAtLeast(0.0001f)
        val sy = child.scaleY.coerceAtLeast(0.0001f)
        val left = hostLocationInWindow[0] + child.translationX + child.coverLeft * sx
        val top = hostLocationInWindow[1] + child.translationY + child.coverTop * sy
        return RectF(
            left,
            top,
            left + child.coverWidth * sx,
            top + child.coverHeight * sy,
        )
    }

    private fun applyPromotedGeometry() {
        val child = promotedHolderView ?: return
        val targetSnapshot = promotedTarget ?: return
        val target = targetSnapshot.physicalViewportBounds?.let { viewportLocal ->
            val hostLocal = runtime?.resolveViewportLocalBoundsInHost(viewportLocal) ?: viewportLocal
            getLocationInWindow(hostLocationInWindow)
            RectF(
                hostLocationInWindow[0] + hostLocal.left,
                hostLocationInWindow[1] + hostLocal.top,
                hostLocationInWindow[0] + hostLocal.right,
                hostLocationInWindow[1] + hostLocal.bottom,
            )
        } ?: targetSnapshot.boundsInWindow.let { windowBounds ->
            RectF(windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom)
        }
        val start = promotedStartRectInWindow ?: currentPromotedCoverRectInWindow(child).also {
            promotedStartRectInWindow = it
        }
        val progress = promotedProgressProvider?.invoke()?.coerceIn(0f, 1f) ?: 1f
        fun lerpFloat(a: Float, b: Float): Float = a + (b - a) * progress
        val left = lerpFloat(start.left, target.left)
        val top = lerpFloat(start.top, target.top)
        val right = lerpFloat(start.right, target.right)
        val bottom = lerpFloat(start.bottom, target.bottom)
        val width = (right - left).coerceAtLeast(1f)
        val height = (bottom - top).coerceAtLeast(1f)
        val sx = width / child.coverWidth.coerceAtLeast(1).toFloat()
        val sy = height / child.coverHeight.coerceAtLeast(1).toFloat()
        val targetRadiusPx = targetSnapshot.radiusDp.coerceAtLeast(0f) * resources.displayMetrics.density
        val visibleRadiusPx = lerpFloat(promotedStartRadiusPx, targetRadiusPx)
        val localRadiusPx = visibleRadiusPx / minOf(sx, sy).coerceAtLeast(0.001f)
        promotedCurrentVisibleRadiusPx = visibleRadiusPx
        getLocationInWindow(hostLocationInWindow)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = sx
        child.scaleY = sy
        child.translationX = left - hostLocationInWindow[0] - child.coverLeft * sx
        child.translationY = top - hostLocationInWindow[1] - child.coverTop * sy
        child.alpha = 1f
        child.visibility = VISIBLE
        child.setPromotedClipRadius(localRadiusPx)
        child.clipBounds = Rect(
            child.coverLeft,
            child.coverTop,
            child.coverLeft + child.coverWidth,
            child.coverTop + child.coverHeight,
        )
    }

    private companion object {
        const val VIEW_HOST_TRACE_TAG = "VirtualListViewHost"
    }
}

/**
 * Heterogeneous VirtualList holder. The outer holder is a real long-lived Android View;
 * Compose is only the implementation of that holder's local content.
 * GenericPivot never changes this composition and only commits LayoutRes properties to this View.
 */
private class RawVirtualListCustomHolderView(context: Context) : ViewGroup(context) {
    var slotId: Int = -1
    var desiredWidth: Int = 1
        private set
    var desiredHeight: Int = 1
        private set

    private val composeView = ComposeView(context)
    private var binding: VirtualListPowerCustomBinding? = null
    private var composedBinding: VirtualListPowerCustomBinding? = null
    private var parentContext: CompositionContext? = null

    init {
        clipChildren = false
        clipToPadding = false
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun updateParentCompositionContext(next: CompositionContext) {
        if (parentContext === next) return
        parentContext = next
        composeView.setParentCompositionContext(next)
    }

    private fun bind(next: VirtualListPowerCustomBinding) {
        if (binding != next) binding = next
        ensureCompositionIfAttached()
    }

    private fun ensureCompositionIfAttached() {
        if (!isAttachedToWindow) return
        val next = binding ?: return
        if (composedBinding == next) return
        composedBinding = next
        composeView.setContent {
            CompositionLocalProvider(LocalVirtualListCustomProvider provides next.provider) {
                next.provider.content(next.song, next.index, Modifier.fillMaxSize())
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // AndroidView.factory/bind can prepare this holder before the presentation host itself has
        // joined the Activity ViewTree. Do not start the nested ComposeView in that detached window:
        // NavigationEvent's LocalNavigationEventDispatcherOwner resolves through LocalView ->
        // ViewTreeNavigationEventDispatcherOwner, which is only available after the holder is a real
        // descendant of the ComponentActivity tree. The binding/physical holder still prewarms; only
        // composition creation is deferred until its normal ViewTree owners are authoritative.
        ensureCompositionIfAttached()
    }

    /**
     * Dispose only on a true physical holder release. Ordinary viewport churn keeps this View and
     * its nested composition resident (INVISIBLE), matching VirtualList's resident-holder lifecycle.
     */
    fun release() {
        binding = null
        composedBinding = null
        parentContext = null
        composeView.disposeComposition()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val height = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        composeView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        composeView.layout(0, 0, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Transition holders explicitly refuse touch dispatch while their alpha is zero.
        // Prepared NEXT holders therefore remain VISIBLE as render owners without becoming an
        // invisible input shield over the CURRENT page. GPU texture residency is measured separately.
        if (alpha <= 0.0001f) return false
        return super.dispatchTouchEvent(event)
    }

    fun applyState(state: VirtualListCustomViewGroupHolderState): Int {
        state.binding?.let(::bind)
        var result = 0
        if (desiredWidth != state.width || desiredHeight != state.height) {
            desiredWidth = state.width.coerceAtLeast(1)
            desiredHeight = state.height.coerceAtLeast(1)
            result = result or HOLDER_APPLY_LAYOUT
        }
        if (visibility != VISIBLE) visibility = VISIBLE
        if (translationX != state.left || translationY != state.top) {
            translationX = state.left
            translationY = state.top
            result = result or HOLDER_APPLY_TRANSLATION
        }
        if (scaleX != state.scaleX || scaleY != state.scaleY) {
            scaleX = state.scaleX
            scaleY = state.scaleY
            result = result or HOLDER_APPLY_SCALE
        }
        if (alpha != state.alpha) {
            alpha = state.alpha
            result = result or HOLDER_APPLY_ALPHA
        }
        return result
    }

    fun applyMotionState(state: VirtualListCustomViewGroupHolderState): Int {
        state.binding?.let(::bind)
        var result = 0
        val dirty = state.dirtyBits
        if ((dirty and VIRTUAL_LIST_LAYOUT_DIRTY_SIZE) != 0) {
            desiredWidth = state.width.coerceAtLeast(1)
            desiredHeight = state.height.coerceAtLeast(1)
            measure(
                MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY),
            )
            layout(0, 0, desiredWidth, desiredHeight)
            pivotX = desiredWidth * 0.5f
            pivotY = desiredHeight * 0.5f
            result = result or HOLDER_APPLY_LAYOUT
        }
        translationX = state.left
        translationY = state.top
        result = result or HOLDER_APPLY_TRANSLATION
        if ((dirty and VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_X) != 0) {
            scaleX = state.scaleX
            result = result or HOLDER_APPLY_SCALE
        }
        if ((dirty and VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_Y) != 0) {
            scaleY = state.scaleY
            result = result or HOLDER_APPLY_SCALE
        }
        if ((dirty and VIRTUAL_LIST_LAYOUT_DIRTY_ALPHA) != 0) {
            alpha = state.alpha
            result = result or HOLDER_APPLY_ALPHA
        }
        return result
    }
}

/** Retained text child owned by one physical song holder. */
@TargetApi(Build.VERSION_CODES.Q)
private class RawVirtualListTextView(context: Context) : View(context) {
    // The optional icon occupies the left inset; text is clipped to its right. Like
    // This non-overlapping text leaf needs no offscreen alpha layer.
    override fun hasOverlappingRendering(): Boolean = false

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val rasterMetrics = VirtualListTextEndpointMetrics()
    private var sourceTextSizePx = 0f
    private var targetTextSizePx = 0f
    private var rasterTextSizePx = 0f
    private val marqueeMotion = VirtualListMarqueeMotion()
    private val marqueeMetrics = VirtualListTextEndpointMetrics()
    // Retained text scene nodes. The View display list records these node
    // references once; scene progress subsequently changes only RenderNode properties/bounds.
    private val glyphNode = RenderNode("RawVirtualListGlyph")
    private val textClipNode = RenderNode("RawVirtualListTextClip")
    private val iconNode = RenderNode("RawVirtualListMetaIcon")
    private var textClipNodeRecorded = false
    private var glyphBaselineInNode = 0f
    private var glyphFontAscent = 0f
    private var glyphFontDescent = 0f
    private var glyphFontTop = 0f
    private var glyphFontBottom = 0f
    private var glyphInkTop = 0f
    private var glyphInkBottom = 0f

    override fun onDetachedFromWindow() {
        marqueeMotion.reset()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            marqueeMotion.hide()
            horizontalOffsetPx = 0f
            invalidate()
        }
    }
    private var sceneLeft = 0
    private var sceneTop = 0
    private var sceneWidth = 1
    private var sceneHeight = 1
    private var textSizePx = 0f
    private var text = ""
    private var typeface: Typeface = Typeface.DEFAULT
    private var textColor = 0
    private var leftInsetPx = 0f
    private var horizontalOffsetPx = 0f
    private var ellipsize = false
    private var metaIconKind = VIRTUAL_LIST_META_ICON_NONE
    private var metaIconSizePx = 0
    private var noteDrawable: android.graphics.drawable.Drawable? = null
    private var folderDrawable: android.graphics.drawable.Drawable? = null
    private var cachedEllipsizedSource = ""
    private var cachedEllipsizedWidth = Float.NaN
    private var cachedEllipsizedTextSize = Float.NaN
    private var cachedEllipsizedTypeface: Typeface? = null
    private var cachedEllipsizedDisplay = ""

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        textClipNode.clipToBounds = true
        glyphNode.clipToBounds = false
        iconNode.clipToBounds = false
    }

    fun applyState(state: VirtualListTextChildState) {
        val textChanged = text != state.text
        val nextSourceSize = state.sourceTextSizePx.takeIf { it > 0f } ?: state.textSizePx
        val nextTargetSize = state.targetTextSizePx.takeIf { it > 0f } ?: state.textSizePx
        val nextRasterSize = virtualListTransitionRasterTextSize(nextSourceSize, nextTargetSize)
        val textLayoutChanged = textChanged || sceneWidth != state.width ||
            rasterTextSizePx != nextRasterSize || typeface !== state.typeface || leftInsetPx != state.leftInsetPx
        if (textChanged) marqueeMotion.reset()
        if (state.marqueeEnabled && nextRasterSize > 0f) {
            marqueeMetrics.prepare(state.text, nextRasterSize, state.typeface)
        }
        val glyphScale = virtualListTransitionGlyphScale(state.textSizePx, nextRasterSize)
        val extent = if (nextRasterSize > 0f) marqueeMetrics.advance * glyphScale else 0f
        val nextOffset = marqueeMotion.update(
            now = android.os.SystemClock.uptimeMillis(),
            distance = (extent - (state.width - state.leftInsetPx)).coerceAtLeast(0f),
            pixelsPerSecond = state.marqueeSpeed,
            enabled = state.marqueeEnabled,
            visible = isShown && state.alpha > 0f && ((parent as? View)?.alpha ?: 1f) > 0f,
            layoutChanged = textLayoutChanged,
        )
        var changed = false
        if (sceneLeft != state.left) { sceneLeft = state.left; changed = true }
        if (sceneTop != state.top) { sceneTop = state.top; changed = true }
        if (sceneWidth != state.width) { sceneWidth = state.width.coerceAtLeast(1); changed = true }
        if (sceneHeight != state.height) { sceneHeight = state.height.coerceAtLeast(1); changed = true }
        if (textSizePx != state.textSizePx) { textSizePx = state.textSizePx; changed = true }
        if (sourceTextSizePx != state.sourceTextSizePx) { sourceTextSizePx = state.sourceTextSizePx; changed = true }
        if (targetTextSizePx != state.targetTextSizePx) { targetTextSizePx = state.targetTextSizePx; changed = true }
        if (rasterTextSizePx != nextRasterSize) { rasterTextSizePx = nextRasterSize; changed = true }
        if (text != state.text) { text = state.text; changed = true }
        if (typeface !== state.typeface) { typeface = state.typeface; changed = true }
        if (textColor != state.color) { textColor = state.color; changed = true }
        if (leftInsetPx != state.leftInsetPx) { leftInsetPx = state.leftInsetPx; changed = true }
        if (horizontalOffsetPx != nextOffset) { horizontalOffsetPx = nextOffset; changed = true }
        if (ellipsize != state.ellipsize) { ellipsize = state.ellipsize; changed = true }
        if (metaIconKind != state.metaIconKind) { metaIconKind = state.metaIconKind; changed = true }
        if (metaIconSizePx != state.metaIconSizePx) { metaIconSizePx = state.metaIconSizePx; changed = true }
        if (alpha != state.alpha) alpha = state.alpha
        if (visibility != VISIBLE) visibility = VISIBLE
        if (changed) invalidate()
    }

    fun adoptStateFrom(other: RawVirtualListTextView) {
        marqueeMotion.copyFrom(other.marqueeMotion)
        sceneLeft = other.sceneLeft
        sceneTop = other.sceneTop
        sceneWidth = other.sceneWidth
        sceneHeight = other.sceneHeight
        textSizePx = other.textSizePx
        sourceTextSizePx = other.sourceTextSizePx
        targetTextSizePx = other.targetTextSizePx
        rasterTextSizePx = other.rasterTextSizePx
        text = other.text
        typeface = other.typeface
        textColor = other.textColor
        leftInsetPx = other.leftInsetPx
        horizontalOffsetPx = other.horizontalOffsetPx
        ellipsize = other.ellipsize
        metaIconKind = other.metaIconKind
        metaIconSizePx = other.metaIconSizePx
        alpha = other.alpha
        visibility = other.visibility
        cachedEllipsizedSource = other.cachedEllipsizedSource
        cachedEllipsizedWidth = other.cachedEllipsizedWidth
        cachedEllipsizedTextSize = other.cachedEllipsizedTextSize
        cachedEllipsizedTypeface = other.cachedEllipsizedTypeface
        cachedEllipsizedDisplay = other.cachedEllipsizedDisplay
        invalidate()
    }

    private fun displayText(rasterSizePx: Float, glyphScale: Float): String {
        if (!ellipsize || text.isEmpty()) return text
        // Ellipsize against the unscaled glyph run. The final View/display-list transform applies
        // [glyphScale], so divide the device-space clip width by that scale before shaping once.
        val availableWidth = (sceneWidth.toFloat() - leftInsetPx)
            .coerceAtLeast(1f) / glyphScale.coerceAtLeast(0.0001f)
        if (
            cachedEllipsizedSource != text ||
            cachedEllipsizedWidth != availableWidth ||
            cachedEllipsizedTextSize != rasterSizePx ||
            cachedEllipsizedTypeface !== typeface
        ) {
            paint.textSize = rasterSizePx
            paint.typeface = typeface
            cachedEllipsizedSource = text
            cachedEllipsizedWidth = availableWidth
            cachedEllipsizedTextSize = rasterSizePx
            cachedEllipsizedTypeface = typeface
            cachedEllipsizedDisplay = TextUtils.ellipsize(
                text,
                paint,
                availableWidth,
                TextUtils.TruncateAt.END,
            ).toString()
        }
        return cachedEllipsizedDisplay
    }

    private fun recordTextClipNode() {
        if (textClipNodeRecorded) return
        // The wrapper only contains a reference to glyphNode. Record it once with a generous
        // command-space extent; setPosition() below owns the live horizontal clip rectangle.
        val recordWidth = maxOf(resources.displayMetrics.widthPixels * 2, 2048)
        val recordHeight = maxOf(resources.displayMetrics.heightPixels * 2, 4096)
        textClipNode.setPosition(0, 0, 1, 1)
        val recording = textClipNode.beginRecording(recordWidth, recordHeight)
        recording.drawRenderNode(glyphNode)
        textClipNode.endRecording()
        textClipNodeRecorded = true
    }

    private fun recordGlyphNode() {
        if (com.rawsmusic.core.ui.perf.TransitionPerfTrace.isActive()) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.count(
                com.rawsmusic.core.ui.perf.TransitionPerfEvent.TEXT_NODE_RECORD,
            )
        }
        val rasterSize = rasterTextSizePx.takeIf { it > 0f } ?: textSizePx
        val visualScale = virtualListTransitionGlyphScale(textSizePx, rasterSize)
            .coerceAtLeast(0.0001f)
        val display = if (rasterSize > 0f) displayText(rasterSize, visualScale) else ""

        paint.color = textColor
        paint.typeface = typeface
        paint.textSize = rasterSize.coerceAtLeast(1f)
        rasterMetrics.prepare(display, paint.textSize, typeface)
        val font = rasterMetrics.font
        glyphFontAscent = font.ascent
        glyphFontDescent = font.descent
        glyphFontTop = font.top
        glyphFontBottom = font.bottom
        glyphInkTop = rasterMetrics.ink.top.toFloat()
        glyphInkBottom = rasterMetrics.ink.bottom.toFloat()

        val nodeWidth = kotlin.math.ceil(maxOf(1f, rasterMetrics.advance + 2f)).toInt().coerceAtLeast(1)
        val nodeHeight = kotlin.math.ceil(maxOf(1f, font.bottom - font.top + 2f)).toInt().coerceAtLeast(1)
        glyphBaselineInNode = 1f - font.top
        glyphNode.setPosition(0, 0, nodeWidth, nodeHeight)
        glyphNode.setPivotX(0f)
        glyphNode.setPivotY(0f)
        val recording = glyphNode.beginRecording(nodeWidth, nodeHeight)
        if (display.isNotEmpty() && rasterSize > 0f) {
            recording.drawText(display, 0f, glyphBaselineInNode, paint)
        }
        glyphNode.endRecording()
    }

    private fun recordIconNode() {
        val size = metaIconSizePx.coerceAtLeast(1)
        iconNode.setPosition(0, 0, size, size)
        iconNode.setPivotX(0f)
        iconNode.setPivotY(0f)
        val recording = iconNode.beginRecording(size, size)
        val icon = metaDrawable()
        if (metaIconKind != VIRTUAL_LIST_META_ICON_NONE && metaIconSizePx > 0 && icon != null) {
            icon.setTint(textColor)
            icon.setBounds(0, 0, size, size)
            icon.draw(recording)
        }
        iconNode.endRecording()
    }

    private fun updateSceneRenderNodes() {
        if (!glyphNode.hasDisplayList()) return
        if (!textClipNodeRecorded) recordTextClipNode()

        val rasterSize = rasterTextSizePx.takeIf { it > 0f } ?: textSizePx
        val glyphScale = virtualListTransitionGlyphScale(textSizePx, rasterSize)
            .coerceAtLeast(0.0001f)
        glyphNode.setScaleX(glyphScale)
        glyphNode.setScaleY(glyphScale)
        glyphNode.setTranslationX(-horizontalOffsetPx)
        glyphNode.setAlpha(if (text.isBlank() || textSizePx <= 0f) 0f else 1f)

        val holderHeight = maxOf(height, sceneTop + sceneHeight, 1)
        val vertical = resolveNativeTextVerticalLayout(
            containerTop = sceneTop.toFloat(),
            containerBottom = sceneTop + sceneHeight.toFloat(),
            canvasTop = 0f,
            canvasBottom = holderHeight.toFloat(),
            ascent = glyphFontAscent * glyphScale,
            descent = glyphFontDescent * glyphScale,
            fontTop = glyphFontTop * glyphScale,
            fontBottom = glyphFontBottom * glyphScale,
            inkTop = glyphInkTop * glyphScale,
            inkBottom = glyphInkBottom * glyphScale,
        )
        glyphNode.setTranslationY(vertical.baseline - glyphBaselineInNode * glyphScale)

        val clipLeft = (sceneLeft + leftInsetPx).roundToInt()
        val clipRight = (sceneLeft + sceneWidth).coerceAtLeast(clipLeft + 1)
        textClipNode.setPosition(clipLeft, 0, clipRight, holderHeight)

        val iconSize = metaIconSizePx.coerceAtLeast(1)
        iconNode.setTranslationX(sceneLeft.toFloat())
        iconNode.setTranslationY(sceneTop + (sceneHeight - iconSize) * 0.5f)
        iconNode.setAlpha(
            if (metaIconKind == VIRTUAL_LIST_META_ICON_NONE || metaIconSizePx <= 0) 0f else 1f
        )
    }

    private fun metaDrawable(): android.graphics.drawable.Drawable? = when (metaIconKind) {
        VIRTUAL_LIST_META_ICON_NOTE -> noteDrawable
            ?: context.getDrawable(R.drawable.ic_music_note)?.mutate()?.also { noteDrawable = it }
        VIRTUAL_LIST_META_ICON_FOLDER -> folderDrawable
            ?: context.getDrawable(R.drawable.ic_folder)?.mutate()?.also { folderDrawable = it }
        else -> null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sceneWidth <= 0 || sceneHeight <= 0) return

        val icon = metaDrawable()
        if (icon != null && metaIconSizePx > 0) {
            val iconTop = sceneTop + (sceneHeight - metaIconSizePx) / 2
            icon.setTint(textColor)
            icon.setBounds(
                sceneLeft,
                iconTop,
                sceneLeft + metaIconSizePx,
                iconTop + metaIconSizePx,
            )
            icon.draw(canvas)
        }

        if (text.isBlank() || textSizePx <= 0f) return
        paint.color = textColor
        paint.typeface = typeface
        val sourceSize = sourceTextSizePx.takeIf { it > 0f } ?: textSizePx
        val targetSize = targetTextSizePx.takeIf { it > 0f } ?: textSizePx
        val rasterSize = virtualListTransitionRasterTextSize(sourceSize, targetSize)
            .takeIf { it > 0f } ?: textSizePx
        val glyphScale = virtualListTransitionGlyphScale(textSizePx, rasterSize)
            .coerceAtLeast(0.0001f)
        paint.textSize = rasterSize
        val display = displayText(rasterSize, glyphScale)
        rasterMetrics.prepare(display, rasterSize, typeface)
        val font = rasterMetrics.font
        val vertical = resolveNativeTextVerticalLayout(
            containerTop = sceneTop.toFloat(),
            containerBottom = sceneTop + sceneHeight.toFloat(),
            canvasTop = 0f,
            canvasBottom = height.toFloat(),
            ascent = font.ascent,
            descent = font.descent,
            fontTop = font.top,
            fontBottom = font.bottom,
            inkTop = rasterMetrics.ink.top.toFloat(),
            inkBottom = rasterMetrics.ink.bottom.toFloat(),
        )
        val save = canvas.save()
        canvas.clipRect(
            sceneLeft.toFloat() + leftInsetPx,
            vertical.clipTop,
            sceneLeft + sceneWidth.toFloat(),
            vertical.clipBottom,
        )
        // Rasterize once at the larger endpoint and let HWUI scale the retained glyph display list
        // for the intermediate zoom frames. Scaling around the text origin / container centre keeps
        // left alignment and native vertical centring stable in both zoom directions.
        val textOriginX = sceneLeft.toFloat() + leftInsetPx
        val textPivotY = sceneTop.toFloat() + sceneHeight * 0.5f
        canvas.scale(glyphScale, glyphScale, textOriginX, textPivotY)
        canvas.drawText(
            display,
            textOriginX - horizontalOffsetPx / glyphScale,
            vertical.baseline,
            paint,
        )
        canvas.restoreToCount(save)
    }
}

@TargetApi(Build.VERSION_CODES.Q)
private class RawVirtualListHolderView(context: Context) : ViewGroup(context) {
    var slotId: Int = -1
    var desiredWidth: Int = 1
        private set
    var desiredHeight: Int = 1
        private set
    var coverLeft: Int = 0
        private set
    var coverTop: Int = 0
        private set
    var coverWidth: Int = 0
        private set
    var coverHeight: Int = 0
        private set
    var coverRadiusPx: Float = 0f
        private set
    private var contentNode: RenderNode? = null
    private var promotedArtworkBitmap: android.graphics.Bitmap? = null
    private val promotedArtworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val promotedArtworkSrc = Rect()
    private val promotedArtworkDst = RectF()
    private val promotedClipRect = RectF()
    private val promotedClipPath = Path()
    private var promotedClipRadiusPx: Float? = null
    private val artworkView = RawVirtualListArtworkView(context)
    private val titleView = RawVirtualListTextView(context)
    private val subtitleView = RawVirtualListTextView(context)
    private val metaView = RawVirtualListTextView(context)
    private var physicalClick: ((Int) -> Unit)? = null
    private var physicalLongClick: ((Int, Float, Float) -> Unit)? = null
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var debugPreparedArtworkArmed = false
    private var debugPreparedArtworkBitmap: Bitmap? = null
    private var debugPreparedArtworkSwaps = 0
    private var debugArtworkHandleValid = false
    private var debugArtworkTier = "-"
    private var debugArtworkRequestActive = false
    private var debugArtworkRequestSide = 0
    private var debugArtworkLastRequestEndReason = "none"
    private var debugArtworkKeyTag = "-"
    private var debugArtworkTraceSessionId = 0L
    private var debugArtworkReentryTraceCount = 0

    init {
        setWillNotDraw(true)
        isClickable = true
        isLongClickable = true
        isDuplicateParentStateEnabled = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(artworkView, LayoutParams(1, 1))
        addView(titleView, LayoutParams(1, 1))
        addView(subtitleView, LayoutParams(1, 1))
        addView(metaView, LayoutParams(1, 1))
    }

    fun bindPhysicalInput(
        onClick: (Int) -> Unit,
        onLongClick: (Int, Float, Float) -> Unit,
    ) {
        physicalClick = onClick
        physicalLongClick = onLongClick
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouchX = event.x
        lastTouchY = event.y
        return super.onTouchEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Alpha-zero transition holders
        // are render residents, not input owners.
        if (alpha <= 0.0001f) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
        artworkView.measure(
            MeasureSpec.makeMeasureSpec(coverWidth.coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(coverHeight.coerceAtLeast(1), MeasureSpec.EXACTLY),
        )
        val fullWidth = measuredWidth.coerceAtLeast(1)
        val fullHeight = measuredHeight.coerceAtLeast(1)
        val fullWidthSpec = MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.EXACTLY)
        val fullHeightSpec = MeasureSpec.makeMeasureSpec(fullHeight, MeasureSpec.EXACTLY)
        titleView.measure(fullWidthSpec, fullHeightSpec)
        subtitleView.measure(fullWidthSpec, fullHeightSpec)
        metaView.measure(fullWidthSpec, fullHeightSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        artworkView.layout(
            coverLeft,
            coverTop,
            coverLeft + coverWidth.coerceAtLeast(1),
            coverTop + coverHeight.coerceAtLeast(1),
        )
        val width = (r - l).coerceAtLeast(1)
        val height = (b - t).coerceAtLeast(1)
        titleView.layout(0, 0, width, height)
        subtitleView.layout(0, 0, width, height)
        metaView.layout(0, 0, width, height)
    }

    fun applyState(state: VirtualListViewGroupHolderState): Int {
        var result = 0
        if (desiredWidth != state.width || desiredHeight != state.height) {
            desiredWidth = state.width.coerceAtLeast(1)
            desiredHeight = state.height.coerceAtLeast(1)
            result = result or HOLDER_APPLY_LAYOUT
        }
        if (pivotX != state.pivotX) pivotX = state.pivotX
        if (pivotY != state.pivotY) pivotY = state.pivotY
        if (contentNode !== state.contentNode) {
            contentNode = state.contentNode
            invalidate()
        }
        if (coverLeft != state.coverLeft || coverTop != state.coverTop ||
            coverWidth != state.coverWidth || coverHeight != state.coverHeight
        ) {
            result = result or HOLDER_APPLY_LAYOUT
        }
        coverLeft = state.coverLeft
        coverTop = state.coverTop
        coverWidth = state.coverWidth
        coverHeight = state.coverHeight
        coverRadiusPx = state.coverRadiusPx
        artworkView.applyState(state)
        updateArtworkDebugMetadata(state)
        titleView.applyState(state.titleText)
        subtitleView.applyState(state.subtitleText)
        metaView.applyState(state.metaText)
        if (visibility != VISIBLE) visibility = VISIBLE
        if (translationX != state.left || translationY != state.top) {
            if (translationX != state.left) translationX = state.left
            if (translationY != state.top) translationY = state.top
            result = result or HOLDER_APPLY_TRANSLATION
        }
        if (scaleX != state.scaleX || scaleY != state.scaleY) {
            if (scaleX != state.scaleX) scaleX = state.scaleX
            if (scaleY != state.scaleY) scaleY = state.scaleY
            result = result or HOLDER_APPLY_SCALE
        }
        if (alpha != state.alpha) {
            alpha = state.alpha
            result = result or HOLDER_APPLY_ALPHA
        }
        return result
    }

    /** Slot-local artwork bitmap/shader update; never changes holder structure/layout. */
    fun applyArtworkState(state: VirtualListViewGroupHolderState) {
        coverRadiusPx = state.coverRadiusPx
        val before = if (debugPreparedArtworkArmed) artworkView.debugPrimaryBitmap() else null
        artworkView.applyState(state)
        updateArtworkDebugMetadata(state)
        if (debugPreparedArtworkArmed && alpha <= HOLDER_TRACE_ALPHA_EPSILON) {
            val after = artworkView.debugPrimaryBitmap()
            if (before !== after) debugPreparedArtworkSwaps += 1
            debugPreparedArtworkBitmap = after
        }
    }

    /**
     * PREPARED NEXT is a render resident (VISIBLE + alpha=0), not an inactive holder. Remember the
     * exact Bitmap/lease state before GenericPivot starts so Perfetto texture-upload spikes can be
     * distinguished from a UI/provider rebind.
     */
    fun markPreparedRenderResident(state: VirtualListViewGroupHolderState) {
        updateArtworkDebugMetadata(state)
        debugPreparedArtworkArmed = true
        debugPreparedArtworkSwaps = 0
        debugPreparedArtworkBitmap = artworkView.debugPrimaryBitmap()
    }

    /**
     * Commit the mutable LayoutRes produced by one GenericPivot tick.
     * Translation is written for every retained-layout commit; scale/alpha
     * and measured size are gated by the exact LayoutRes dirty bits.
     */
    fun applyMotionState(state: VirtualListViewGroupMotionState): Int {
        val previousRootAlpha = alpha
        var result = 0
        val dirty = state.dirtyBits
        if ((dirty and VIRTUAL_LIST_LAYOUT_DIRTY_SIZE) != 0) {
            val nextWidth = state.width.coerceAtLeast(1)
            val nextHeight = state.height.coerceAtLeast(1)
            desiredWidth = nextWidth
            desiredHeight = nextHeight
            if (resolveVirtualListHolderFrameCommit(state.dualLayout) == VirtualListHolderFrameCommit.DIRECT_BOUNDS) {
                // The holder uses a retained scene layout: the dual LayoutRes branch commits the
                // interpolated View bounds every tick through setLeftTopRightBottom(). Do not call
                // layout() here: ViewGroup.layout() executes onLayout() and re-enters child layout
                // work on every size tick, which is exactly the command-issue cost this fast path
                // exists to avoid. Endpoint/publication commits still perform normal measure/layout.
                if (width != nextWidth || height != nextHeight || !isLaidOut) {
                    setLeftTopRightBottom(0, 0, nextWidth, nextHeight)
                    titleView.setLeftTopRightBottom(0, 0, nextWidth, nextHeight)
                    subtitleView.setLeftTopRightBottom(0, 0, nextWidth, nextHeight)
                    metaView.setLeftTopRightBottom(0, 0, nextWidth, nextHeight)
                    result = result or HOLDER_APPLY_DIRECT_FRAME
                }
                pivotX = nextWidth * 0.5f
                pivotY = nextHeight * 0.5f
            } else {
                measure(
                    MeasureSpec.makeMeasureSpec(nextWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(nextHeight, MeasureSpec.EXACTLY),
                )
                layout(0, 0, nextWidth, nextHeight)
                result = result or HOLDER_APPLY_LAYOUT
            }
        }
        if (
            state.updateInternalChildren && (
                coverLeft != state.coverLeft ||
                    coverTop != state.coverTop ||
                    coverWidth != state.coverWidth ||
                    coverHeight != state.coverHeight
                )
        ) {
            coverLeft = state.coverLeft
            coverTop = state.coverTop
            coverWidth = state.coverWidth.coerceAtLeast(1)
            coverHeight = state.coverHeight.coerceAtLeast(1)
            if (!state.dualLayout) {
                artworkView.measure(
                    MeasureSpec.makeMeasureSpec(coverWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(coverHeight, MeasureSpec.EXACTLY),
                )
                artworkView.layout(
                    coverLeft,
                    coverTop,
                    coverLeft + coverWidth,
                    coverTop + coverHeight,
                )
                result = result or HOLDER_APPLY_LAYOUT
            } else {
                artworkView.setLeftTopRightBottom(
                    coverLeft,
                    coverTop,
                    coverLeft + coverWidth,
                    coverTop + coverHeight,
                )
                result = result or HOLDER_APPLY_DIRECT_FRAME
            }
        }
        // Neither retained-layout commit nor interpolation needs LayoutRes dirty bits for translation. They are
        // committed on every application after measurement and before scale/alpha.
        translationX = state.left
        translationY = state.top
        result = result or HOLDER_APPLY_TRANSLATION
        if ((dirty and VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_X) != 0) {
            scaleX = state.scaleX
            result = result or HOLDER_APPLY_SCALE
        }
        if ((dirty and VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_Y) != 0) {
            scaleY = state.scaleY
            result = result or HOLDER_APPLY_SCALE
        }
        if ((dirty and VIRTUAL_LIST_LAYOUT_DIRTY_ALPHA) != 0) {
            alpha = state.alpha
            result = result or HOLDER_APPLY_ALPHA
        }
        artworkView.applyMotionAlpha(
            current = state.artworkCurrentAlpha,
            previous = state.artworkPreviousAlpha,
        )
        if (state.updateInternalChildren) {
            artworkView.applyMotionRadius(state.coverRadiusPx)
            titleView.applyState(state.titleText)
            subtitleView.applyState(state.subtitleText)
            metaView.applyState(state.metaText)
        }
        traceArtworkRenderResidencyEdge(previousRootAlpha, alpha)
        return result
    }

    private fun updateArtworkDebugMetadata(state: VirtualListViewGroupHolderState) {
        debugArtworkHandleValid = state.artworkDebugHandleValid
        debugArtworkTier = state.artworkDebugTier
        debugArtworkRequestActive = state.artworkDebugRequestActive
        debugArtworkRequestSide = state.artworkDebugRequestSide
        debugArtworkLastRequestEndReason = state.artworkDebugLastRequestEndReason
        debugArtworkKeyTag = state.artworkDebugKeyTag
    }

    private fun traceArtworkRenderResidencyEdge(previousRootAlpha: Float, nextRootAlpha: Float) {
        if (!com.rawsmusic.core.ui.perf.TransitionPerfTrace.isActive()) {
            debugPreparedArtworkArmed = false
            return
        }
        val traceSession = com.rawsmusic.core.ui.perf.TransitionPerfTrace.currentSessionId()
        if (debugArtworkTraceSessionId != traceSession) {
            debugArtworkTraceSessionId = traceSession
            debugArtworkReentryTraceCount = 0
        }

        if (previousRootAlpha > HOLDER_TRACE_ALPHA_EPSILON && nextRootAlpha <= HOLDER_TRACE_ALPHA_EPSILON) {
            // A reverse gesture can park the same holder at the endpoint without ending the
            // transition transaction. Arm another identity check for the subsequent re-entry.
            debugPreparedArtworkArmed = true
            debugPreparedArtworkSwaps = 0
            debugPreparedArtworkBitmap = artworkView.debugPrimaryBitmap()
            return
        }

        if (
            !debugPreparedArtworkArmed ||
            previousRootAlpha > HOLDER_TRACE_ALPHA_EPSILON ||
            nextRootAlpha <= HOLDER_TRACE_ALPHA_EPSILON
        ) return

        val liveBitmap = artworkView.debugPrimaryBitmap()
        if (debugArtworkReentryTraceCount < 2) {
            com.rawsmusic.core.ui.perf.TransitionPerfTrace.mark(
                "artwork_render_reentry",
                "slot=$slotId sameBitmap=${liveBitmap === debugPreparedArtworkBitmap} " +
                    "prepared=${virtualListBitmapDebugLabel(debugPreparedArtworkBitmap)} " +
                    "live=${virtualListBitmapDebugLabel(liveBitmap)} shader=${artworkView.debugPrimaryShaderPresent()} " +
                    "handle=$debugArtworkHandleValid tier=$debugArtworkTier request=$debugArtworkRequestActive " +
                    "requestSide=$debugArtworkRequestSide swaps=$debugPreparedArtworkSwaps " +
                    "requestEnd=$debugArtworkLastRequestEndReason key=$debugArtworkKeyTag",
            )
            debugArtworkReentryTraceCount += 1
        }
        debugPreparedArtworkArmed = false
    }

    override fun performClick(): Boolean {
        val action = physicalClick
        return if (action != null) {
            super.performClick()
            action.invoke(slotId)
            true
        } else {
            super.performClick()
        }
    }

    override fun performLongClick(): Boolean {
        val action = physicalLongClick
        return if (action != null) {
            action.invoke(slotId, lastTouchX, lastTouchY)
            true
        } else {
            super.performLongClick()
        }
    }

    fun setPromotedArtworkBitmap(bitmap: android.graphics.Bitmap?) {
        val valid = bitmap?.takeIf { !it.isRecycled }
        if (promotedArtworkBitmap === valid) return
        promotedArtworkBitmap = valid
        invalidate()
    }

    fun adoptPresentationStateFrom(other: RawVirtualListHolderView) {
        desiredWidth = other.desiredWidth
        desiredHeight = other.desiredHeight
        coverLeft = other.coverLeft
        coverTop = other.coverTop
        coverWidth = other.coverWidth
        coverHeight = other.coverHeight
        coverRadiusPx = other.coverRadiusPx
        artworkView.adoptStateFrom(other.artworkView)
        titleView.adoptStateFrom(other.titleView)
        subtitleView.adoptStateFrom(other.subtitleView)
        metaView.adoptStateFrom(other.metaView)
        if (contentNode !== other.contentNode) {
            contentNode = other.contentNode
            invalidate()
        }
        pivotX = other.pivotX
        pivotY = other.pivotY
        translationX = other.translationX
        translationY = other.translationY
        scaleX = other.scaleX
        scaleY = other.scaleY
        alpha = other.alpha
        visibility = other.visibility
    }

    fun setPromotedClipRadius(radiusPx: Float?) {
        val next = radiusPx?.coerceAtLeast(0f)
        if (promotedClipRadiusPx == next) return
        promotedClipRadiusPx = next
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val promotedRadius = promotedClipRadiusPx
        val clipSave = if (promotedRadius != null && coverWidth > 0 && coverHeight > 0) {
            val save = canvas.save()
            promotedClipRect.set(
                coverLeft.toFloat(),
                coverTop.toFloat(),
                (coverLeft + coverWidth).toFloat(),
                (coverTop + coverHeight).toFloat(),
            )
            val boundedRadius = promotedRadius.coerceAtMost(
                minOf(promotedClipRect.width(), promotedClipRect.height()) * 0.5f
            )
            if (boundedRadius > 0.001f) {
                promotedClipPath.reset()
                promotedClipPath.addRoundRect(
                    promotedClipRect,
                    boundedRadius,
                    boundedRadius,
                    Path.Direction.CW,
                )
                canvas.clipPath(promotedClipPath)
            } else {
                canvas.clipRect(promotedClipRect)
            }
            save
        } else {
            -1
        }

        contentNode?.let(canvas::drawRenderNode)
        super.dispatchDraw(canvas)
        val bitmap = promotedArtworkBitmap?.takeIf { !it.isRecycled }
        if (bitmap != null && coverWidth > 0 && coverHeight > 0 && bitmap.width > 0 && bitmap.height > 0) {
            // Keep one artwork-view scale type while ItemToHeader only changes its
            // SceneParams. Raw's list provider is KeepAspect, so the promoted high-res raster must
            // occupy the same fitted visible rect instead of silently switching to center-crop.
            val scale = minOf(
                coverWidth.toFloat() / bitmap.width.toFloat(),
                coverHeight.toFloat() / bitmap.height.toFloat(),
            )
            val drawnWidth = bitmap.width * scale
            val drawnHeight = bitmap.height * scale
            val fittedLeft = coverLeft + (coverWidth - drawnWidth) * 0.5f
            val fittedTop = coverTop + (coverHeight - drawnHeight) * 0.5f
            promotedArtworkSrc.set(0, 0, bitmap.width, bitmap.height)
            promotedArtworkDst.set(
                fittedLeft,
                fittedTop,
                fittedLeft + drawnWidth,
                fittedTop + drawnHeight,
            )
            val innerSave = canvas.save()
            val visibleRadius = (promotedRadius ?: 0f).coerceIn(
                0f,
                minOf(promotedArtworkDst.width(), promotedArtworkDst.height()) * 0.5f,
            )
            if (visibleRadius > 0.001f) {
                promotedClipPath.reset()
                promotedClipPath.addRoundRect(
                    promotedArtworkDst,
                    visibleRadius,
                    visibleRadius,
                    Path.Direction.CW,
                )
                canvas.clipPath(promotedClipPath)
            } else {
                canvas.clipRect(promotedArtworkDst)
            }
            canvas.drawBitmap(bitmap, promotedArtworkSrc, promotedArtworkDst, promotedArtworkPaint)
            canvas.restoreToCount(innerSave)
        }
        if (clipSave >= 0) canvas.restoreToCount(clipSave)
    }
}

/**
 * Artwork view used by the Android presentation backend.
 *
 * The Bitmap/BitmapShader live on a real View, not inside Raw's retained RenderNode hierarchy.
 * Temporary detach therefore follows Android's native View lifecycle exactly: the shader/bitmap
 * survive dispatchStartTemporaryDetach()/dispatchFinishTemporaryDetach(), while a real window
 * detach clears presentation ownership and must be rebound by the provider on the next attach.
 */
@TargetApi(Build.VERSION_CODES.Q)
private class RawVirtualListArtworkView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val matrix = Matrix()
    private val dst = RectF()
    private var currentBitmap: Bitmap? = null
    private var previousBitmap: Bitmap? = null
    private var fallbackBitmap: Bitmap? = null
    private var currentShader: BitmapShader? = null
    private var previousShader: BitmapShader? = null
    private var fallbackShader: BitmapShader? = null
    private var currentAlpha = 1f
    private var previousAlpha = 0f
    private var radiusPx = 0f
    private var hidden = false
    private var temporaryDetach = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun applyState(state: VirtualListViewGroupHolderState) {
        var changed = false
        val nextCurrent = state.artworkCurrentBitmap?.takeIf { !it.isRecycled }
        val nextPrevious = state.artworkPreviousBitmap?.takeIf { !it.isRecycled }
        val nextFallback = state.artworkFallbackBitmap?.takeIf { !it.isRecycled }
        if (currentBitmap !== nextCurrent) {
            currentBitmap = nextCurrent
            currentShader = null
            changed = true
        }
        if (previousBitmap !== nextPrevious) {
            previousBitmap = nextPrevious
            previousShader = null
            changed = true
        }
        if (fallbackBitmap !== nextFallback) {
            fallbackBitmap = nextFallback
            fallbackShader = null
            changed = true
        }
        if (currentAlpha != state.artworkCurrentAlpha) {
            currentAlpha = state.artworkCurrentAlpha
            changed = true
        }
        if (previousAlpha != state.artworkPreviousAlpha) {
            previousAlpha = state.artworkPreviousAlpha
            changed = true
        }
        if (radiusPx != state.coverRadiusPx) {
            radiusPx = state.coverRadiusPx
            changed = true
        }
        if (hidden != state.artworkHidden) {
            hidden = state.artworkHidden
            changed = true
        }
        if (changed) invalidate()
    }

    fun applyMotionAlpha(current: Float, previous: Float) {
        var changed = false
        if (currentAlpha != current) {
            currentAlpha = current
            changed = true
        }
        if (previousAlpha != previous) {
            previousAlpha = previous
            changed = true
        }
        if (changed) invalidate()
    }

    fun applyMotionRadius(radius: Float) {
        if (radiusPx == radius) return
        radiusPx = radius
        invalidate()
    }

    fun adoptStateFrom(other: RawVirtualListArtworkView) {
        currentBitmap = other.currentBitmap
        previousBitmap = other.previousBitmap
        fallbackBitmap = other.fallbackBitmap
        currentShader = other.currentShader
        previousShader = other.previousShader
        fallbackShader = other.fallbackShader
        currentAlpha = other.currentAlpha
        previousAlpha = other.previousAlpha
        radiusPx = other.radiusPx
        hidden = other.hidden
        invalidate()
    }

    fun debugPrimaryBitmap(): Bitmap? = currentBitmap ?: fallbackBitmap

    fun debugPrimaryShaderPresent(): Boolean = when {
        currentBitmap != null -> currentShader != null
        fallbackBitmap != null -> fallbackShader != null
        else -> false
    }

    override fun onStartTemporaryDetach() {
        super.onStartTemporaryDetach()
        temporaryDetach = true
    }

    override fun onFinishTemporaryDetach() {
        super.onFinishTemporaryDetach()
        temporaryDetach = false
    }

    override fun onDetachedFromWindow() {
        if (!temporaryDetach) clearPresentation()
        super.onDetachedFromWindow()
    }

    private fun clearPresentation() {
        currentBitmap = null
        previousBitmap = null
        fallbackBitmap = null
        currentShader = null
        previousShader = null
        fallbackShader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hidden || width <= 0 || height <= 0) return
        val previous = previousBitmap
        if (previous != null && previousAlpha > 0f) {
            val shader = previousShader ?: newShader(previous).also { previousShader = it }
            configureFitCenter(shader, previous)
            paint.shader = shader
            paint.alpha = (255f * previousAlpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
            drawArtwork(canvas)
        }

        val current = currentBitmap
        if (current != null && currentAlpha > 0f) {
            val shader = currentShader ?: newShader(current).also { currentShader = it }
            configureFitCenter(shader, current)
            paint.shader = shader
            paint.alpha = (255f * currentAlpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
            drawArtwork(canvas)
        } else if (current == null && fallbackBitmap != null) {
            val fallback = fallbackBitmap ?: return
            val shader = fallbackShader ?: newShader(fallback).also { fallbackShader = it }
            configureFitCenter(shader, fallback)
            paint.shader = shader
            paint.alpha = 255
            drawArtwork(canvas)
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun newShader(bitmap: Bitmap): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)

    private fun configureFitCenter(shader: BitmapShader, bitmap: Bitmap) {
        val bw = bitmap.width.coerceAtLeast(1).toFloat()
        val bh = bitmap.height.coerceAtLeast(1).toFloat()
        val scale = minOf(width.toFloat() / bw, height.toFloat() / bh)
        val drawnWidth = bw * scale
        val drawnHeight = bh * scale
        val dx = (width - drawnWidth) * 0.5f
        val dy = (height - drawnHeight) * 0.5f
        dst.set(dx, dy, dx + drawnWidth, dy + drawnHeight)
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        shader.setLocalMatrix(matrix)
    }

    private fun drawArtwork(canvas: Canvas) {
        val boundedRadius = radiusPx.coerceIn(0f, minOf(dst.width(), dst.height()) * 0.5f)
        canvas.drawRoundRect(dst, boundedRadius, boundedRadius, paint)
    }
}

internal enum class VirtualListPresentationHostBackend {
    COMPOSE_NODE,
    ANDROID_VIEW,
}

internal val ReferenceLibraryPresentationHostBackend =
    VirtualListPresentationHostBackend.ANDROID_VIEW
