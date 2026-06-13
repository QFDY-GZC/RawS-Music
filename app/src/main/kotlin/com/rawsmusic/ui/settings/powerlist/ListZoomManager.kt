package com.rawsmusic.ui.settings.powerlist

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.rawsmusic.core.ui.widget.scene.AAItemView
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * List-mode pinch zoom controller modelled after Poweramp's c0/q1/a2 chain.
 *
 * Pinch opens a transition between two discrete list levels. Item zoom params
 * are committed only after the transition is confirmed or rolled back.
 */
class ListZoomManager(private val context: Context) {

    companion object {
        private const val VELOCITY_THRESHOLD_DP = 500f
        private const val POSITION_THRESHOLD = 0.3f
        private const val SNAP_DURATION_MS = 500L
        private const val GESTURE_PROGRESS_SCALE = 1.0f

        private fun powerampEasing(d: Float): Float {
            return when {
                d > 0f -> {
                    val clamped = (d.toDouble().coerceAtMost(3.0) / 3.0).coerceIn(0.0, 1.0)
                    (sqrt(clamped * 0.2) * 0.2).toFloat()
                }
                d < 0f -> {
                    val mapped = (1.0 - (d.toDouble() + 1.0).coerceIn(0.1, 1.0)) / 0.9
                    -(sqrt(mapped.coerceIn(0.0, 1.0) * 0.2) * 0.2).toFloat()
                }
                else -> 0f
            }
        }
    }

    var currentLevel: ListZoomIndex = ListZoomIndex.NORMAL
        private set

    var currentParams: ListZoomParams = ListZoomLevels.params[ListZoomIndex.NORMAL]!!
        private set

    var isPinching = false
        private set

    val isSnapAnimating: Boolean get() = snapAnimator?.isRunning == true

    var snapProgress: Float = 1f
        private set

    private var transitionStarted = false
    private var transitionSourceLevel = ListZoomIndex.NORMAL
    private var transitionTargetLevel = ListZoomIndex.NORMAL
    private var transitionIsZoomIn = false
    private var transitionProgress = 0f
    private var transitionScaleFactor = 1f
    private var pendingGridZoom = false
    private var gridZoomProgress = 0f
    private var boundaryElasticActive = false
    private var boundaryElasticScale = 1f

    private var pinchBaseDistance = 1f
    private var pinchStartRatio = 1f
    private var lastRatio = 1f
    private var lastEventTime = 0L
    private var pinchVelocityDp = 0f
    private var pinchRatioVelocity = 0f
    private var pointerId1 = -1
    private var pointerId2 = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null

    private var snapAnimator: ValueAnimator? = null
    private var snapCancelled = false

    var onPinchStart: ((ListZoomIndex, Boolean) -> Unit)? = null
    var onPinchProgress: ((Float, Float, Boolean) -> Unit)? = null
    var onSnapStart: ((ListZoomIndex, Boolean) -> Unit)? = null
    var onSnapProgress: ((Float, Float, Boolean) -> Unit)? = null
    var onZoomLevelSnapped: ((ListZoomIndex) -> Unit)? = null
    var onGridPinchProgress: ((Float) -> Unit)? = null
    var onGridPinchFinished: ((Boolean) -> Unit)? = null
    var onBoundaryElasticProgress: ((Float) -> Unit)? = null
    var onBoundaryElasticFinished: ((Float) -> Unit)? = null

    fun setInitialLevel(level: ListZoomIndex) {
        snapAnimator?.cancel()
        currentLevel = level
        currentParams = ListZoomLevels.params[level]!!
        resetGestureState()
    }

    fun commitLevel(level: ListZoomIndex) {
        currentLevel = level
        currentParams = ListZoomLevels.params[level]!!
        resetGestureState()
    }

    fun paramsForLevel(level: ListZoomIndex): ListZoomParams {
        return ListZoomLevels.params[level] ?: currentParams
    }

    fun cancelSnap() {
        val animator = snapAnimator ?: return
        if (!animator.isRunning) return
        // Poweramp abort path (m2955(false,false,...)) rolls back immediately; it
        // does not threshold-commit the target based on current progress.
        snapCancelled = true
        animator.cancel()
        commitLevel(transitionSourceLevel)
        onZoomLevelSnapped?.invoke(transitionSourceLevel)
    }

    var applyToItem: (AAItemView) -> Unit = { itemView -> applyToItemDefault(itemView) }

    fun applyToItemDefault(itemView: AAItemView) {
        itemView.zoomParams = currentParams
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        velocityTrackerFor(event)?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pointerId1 = event.getPointerId(0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    beginPinch(event)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPinching && event.pointerCount >= 2) return updatePinch(event)
                if (!isPinching && event.pointerCount >= 2) {
                    beginPinch(event)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isPinching) {
                    val upId = event.getPointerId(event.actionIndex)
                    if (upId == pointerId1 || upId == pointerId2) {
                        updateReleaseVelocity(event)
                        finishPinch()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isPinching) {
                    finishPinch()
                    return true
                }
            }
        }
        return isPinching
    }

    private fun beginPinch(event: MotionEvent) {
        snapAnimator?.cancel()
        pointerId1 = event.getPointerId(0)
        pointerId2 = event.getPointerId(1)
        pinchBaseDistance = distance(event, 0, 1).coerceAtLeast(1f)
        pinchStartRatio = 1f
        lastRatio = 1f
        lastEventTime = event.eventTime
        pinchVelocityDp = 0f
        pinchRatioVelocity = 0f
        velocityTracker?.clear()
        velocityTrackerFor(event)?.addMovement(event)
        isPinching = true
        transitionStarted = false
        transitionProgress = 0f
        boundaryElasticActive = false
        boundaryElasticScale = 1f
    }

    private fun updatePinch(event: MotionEvent): Boolean {
        val idx1 = event.findPointerIndex(pointerId1)
        val idx2 = event.findPointerIndex(pointerId2)
        if (idx1 < 0 || idx2 < 0) return false

        val currentDistance = distance(event, idx1, idx2)
        if (abs(currentDistance - pinchBaseDistance) < touchSlop && !transitionStarted) return true

        val ratio = currentDistance / pinchBaseDistance
        val dt = (event.eventTime - lastEventTime).coerceAtLeast(1L)
        // Updated on release with Poweramp's two-pointer radial velocity. Keep this
        // MOVE estimate only as a fallback when VelocityTracker cannot resolve pointers.
        pinchVelocityDp = ((ratio - lastRatio) * pinchBaseDistance / context.resources.displayMetrics.density) / (dt / 1000f)
        pinchRatioVelocity = (ratio - lastRatio) / (dt / 1000f)
        lastRatio = ratio
        lastEventTime = event.eventTime

        val rawDelta = ratio - pinchStartRatio
        val isZoomIn = rawDelta > 0f
        if (rawDelta == 0f && !transitionStarted) return true

        if (!transitionStarted) {
            val target = adjacentLevel(currentLevel, isZoomIn)
            if (target == currentLevel) {
                if (currentLevel == ListZoomIndex.ZOOMED && isZoomIn) {
                    pendingGridZoom = true
                    transitionStarted = true
                    transitionIsZoomIn = true
                    transitionProgress = 0f
                    gridZoomProgress = 0f
                } else {
                    boundaryElasticActive = true
                    transitionStarted = true
                    transitionIsZoomIn = isZoomIn
                    boundaryElasticScale = 1f
                }
            } else {
                transitionSourceLevel = currentLevel
                transitionTargetLevel = target
                transitionIsZoomIn = isZoomIn
                transitionStarted = true
                transitionProgress = 0f
                transitionScaleFactor = 1f
                onPinchStart?.invoke(target, isZoomIn)
            }
        }

        if (pendingGridZoom) {
            gridZoomProgress = rawDelta.coerceIn(0f, 1f)
            onGridPinchProgress?.invoke(gridZoomProgress)
            return true
        }

        if (boundaryElasticActive) {
            boundaryElasticScale = computeBoundaryElasticScale(abs(rawDelta), expands = transitionIsZoomIn)
            onBoundaryElasticProgress?.invoke(boundaryElasticScale)
            return true
        }

        val signedDelta = if (transitionIsZoomIn) rawDelta else -rawDelta
        transitionProgress = (signedDelta * GESTURE_PROGRESS_SCALE).coerceIn(0f, 1f)
        transitionScaleFactor = powerampElasticScale(signedDelta, transitionIsZoomIn)
        onPinchProgress?.invoke(transitionProgress, transitionScaleFactor, transitionIsZoomIn)
        return true
    }

    private fun finishPinch() {
        isPinching = false
        if (!transitionStarted) {
            resetGestureState()
            return
        }

        if (boundaryElasticActive) {
            onBoundaryElasticFinished?.invoke(boundaryElasticScale)
            resetGestureState()
            return
        }

        if (pendingGridZoom) {
            val shouldConfirmGrid = if (abs(pinchVelocityDp) >= VELOCITY_THRESHOLD_DP) {
                pinchVelocityDp > 0f
            } else {
                gridZoomProgress > POSITION_THRESHOLD
            }
            onGridPinchFinished?.invoke(shouldConfirmGrid)
            resetGestureState()
            return
        }

        val shouldConfirm = if (abs(pinchVelocityDp) >= VELOCITY_THRESHOLD_DP) {
            (pinchVelocityDp > 0f) == transitionIsZoomIn
        } else {
            transitionProgress > POSITION_THRESHOLD
        }
        val velocityTowardEnd = (pinchVelocityDp > 0f) == transitionIsZoomIn
        val releaseProgressVelocity = when {
            shouldConfirm && velocityTowardEnd && transitionProgress > 0.8f -> abs(pinchRatioVelocity) / 4f
            !shouldConfirm && !velocityTowardEnd && transitionProgress < 0.2f -> -abs(pinchRatioVelocity) / 4f
            else -> 0f
        }
        animateTransition(shouldConfirm, releaseProgressVelocity)
    }

    private fun animateTransition(confirm: Boolean, releaseProgressVelocity: Float = 0f) {
        val start = transitionProgress
        val end = if (confirm) 1f else 0f
        val startScale = transitionScaleFactor
        if (abs(start - end) < 0.001f && abs(startScale - 1f) < 0.001f) {
            transitionProgress = end
            transitionScaleFactor = 1f
            snapProgress = 1f
            val finalLevel = if (confirm) transitionTargetLevel else transitionSourceLevel
            commitLevel(finalLevel)
            onZoomLevelSnapped?.invoke(finalLevel)
            return
        }

        snapAnimator?.cancel()
        snapCancelled = false
        snapProgress = 0f
        val progressDistance = abs(end - start)
        val velocityDuration = if (progressDistance > 0.001f) {
            computeVelocityHandoffDurationMs(start, end, confirm, releaseProgressVelocity)
        } else {
            null
        }
        val snapDuration = velocityDuration ?: computeSnapDurationMs(start, end, confirm, startScale)
        onSnapStart?.invoke(transitionTargetLevel, transitionIsZoomIn)
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = snapDuration
            interpolator = if (velocityDuration != null) LinearInterpolator() else AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                snapProgress = anim.animatedValue as Float
                transitionProgress = start + (end - start) * snapProgress
                transitionScaleFactor = startScale + (1f - startScale) * snapProgress
                onSnapProgress?.invoke(transitionProgress, transitionScaleFactor, transitionIsZoomIn)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (snapCancelled) {
                        snapCancelled = false
                        return
                    }
                    transitionProgress = end
                    snapProgress = 1f
                    val finalLevel = if (confirm) transitionTargetLevel else transitionSourceLevel
                    commitLevel(finalLevel)
                    onZoomLevelSnapped?.invoke(finalLevel)
                }
            })
            start()
        }
    }

    private fun computeSnapDurationMs(start: Float, end: Float, confirm: Boolean, startScale: Float): Long {
        val distance = abs(end - start).coerceAtLeast(0.001f)
        val minDuration = if (confirm) 100L else SNAP_DURATION_MS / 3L
        val progressDuration = (SNAP_DURATION_MS * distance).roundToLong().coerceIn(minDuration, SNAP_DURATION_MS)
        val elasticDuration = if (abs(startScale - 1f) > 0.001f) 350L else 0L
        return maxOf(progressDuration, elasticDuration)
    }

    private fun computeVelocityHandoffDurationMs(
        start: Float,
        end: Float,
        confirm: Boolean,
        releaseProgressVelocity: Float
    ): Long? {
        if ((confirm && releaseProgressVelocity <= 0f) || (!confirm && releaseProgressVelocity >= 0f)) return null
        val velocity = if (confirm) {
            releaseProgressVelocity.coerceIn(1f / (SNAP_DURATION_MS / 1000f), 8f)
        } else {
            releaseProgressVelocity.coerceIn(-8f, -3.5f)
        }
        val distance = abs(end - start).coerceAtLeast(0.0001f)
        return (distance / abs(velocity) * 1000f).roundToLong().coerceAtLeast(1L)
    }

    private fun powerampElasticScale(signedDelta: Float, isZoomIn: Boolean): Float {
        if (signedDelta in 0f..1f) return 1f
        val beyond = if (signedDelta > 1f) signedDelta - 1f else -signedDelta
        val eased = abs(powerampEasing(beyond))
        return if ((signedDelta > 1f) == isZoomIn) {
            1f + eased
        } else {
            1f - eased
        }.coerceIn(0.85f, 1.15f)
    }

    private fun computeBoundaryElasticScale(overPull: Float, expands: Boolean): Float {
        val eased = abs(powerampEasing(overPull))
        return if (expands) 1f + eased else 1f - eased
    }

    private fun updateReleaseVelocity(event: MotionEvent) {
        val tracker = velocityTracker ?: return
        val idx1 = event.findPointerIndex(pointerId1)
        val idx2 = event.findPointerIndex(pointerId2)
        if (idx1 < 0 || idx2 < 0) return

        tracker.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
        val x1 = event.getX(idx1)
        val y1 = event.getY(idx1)
        val dx = event.getX(idx2) - x1
        val dy = event.getY(idx2) - y1
        val currentDistance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

        val vx1 = tracker.getXVelocity(pointerId1)
        val vy1 = tracker.getYVelocity(pointerId1)
        val vx2 = tracker.getXVelocity(pointerId2)
        val vy2 = tracker.getYVelocity(pointerId2)

        // Poweramp X.java projects the relative pointer velocity over 0.01s onto
        // the current distance vector, then derives radial expansion velocity.
        val projectedDx = ((vx2 - vx1) * 0.01f) + dx
        val projectedDy = ((vy2 - vy1) * 0.01f) + dy
        val projectedDistance = sqrt(projectedDx * projectedDx + projectedDy * projectedDy)
        val radialPxPerSec = (projectedDistance - currentDistance) / 0.01f
        pinchVelocityDp = radialPxPerSec / context.resources.displayMetrics.density
        pinchRatioVelocity = radialPxPerSec / pinchBaseDistance.coerceAtLeast(1f)
        tracker.clear()
    }

    private fun velocityTrackerFor(event: MotionEvent): VelocityTracker? {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocityTracker?.clear()
        }
        return velocityTracker
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

    private fun distance(event: MotionEvent, idx1: Int, idx2: Int): Float {
        val dx = event.getX(idx2) - event.getX(idx1)
        val dy = event.getY(idx2) - event.getY(idx1)
        return sqrt(dx * dx + dy * dy)
    }

    fun isPinchActive(): Boolean = isPinching

    fun destroy() {
        snapAnimator?.cancel()
        snapAnimator = null
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun resetGestureState() {
        transitionStarted = false
        transitionProgress = 0f
        transitionScaleFactor = 1f
        pendingGridZoom = false
        gridZoomProgress = 0f
        boundaryElasticActive = false
        boundaryElasticScale = 1f
        snapProgress = 1f
        pinchVelocityDp = 0f
        pinchRatioVelocity = 0f
    }
}
