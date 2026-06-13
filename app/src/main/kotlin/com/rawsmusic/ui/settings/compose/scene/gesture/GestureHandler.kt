package com.rawsmusic.ui.settings.compose.scene.gesture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ViewConfiguration
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 手势处理器
 * 对应原版 ListZoomManager 的手势处理
 *
 * 处理捏合、滑动、点击等手势
 */
class GestureHandler(
    private val state: ComposeListState,
    private val viewConfiguration: ViewConfiguration,
    private val density: Float,
    private val scope: CoroutineScope
) {
    // ==================== 捏合手势状态 ====================
    private var pointerId1: PointerId = PointerId(0)
    private var pointerId2: PointerId = PointerId(0)
    private var pinchBaseDistance: Float = 1f
    private var lastRatio: Float = 1f
    private var lastEventTime: Long = 0L
    private var pinchVelocityDp: Float = 0f
    private var pinchRatioVelocity: Float = 0f
    private var velocityTracker = VelocityTracker()

    // ==================== 滑动手势状态 ====================
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var isSwiping: Boolean = false
    private var swipeDirection: SwipeDirection = SwipeDirection.NONE

    // ==================== 手势回调 ====================

    /** 捏合开始回调 */
    var onPinchStart: ((ListZoomIndex, Boolean) -> Unit)? = null

    /** 捏合进度回调 */
    var onPinchProgress: ((Float, Float, Boolean) -> Unit)? = null

    /** 捏合完成回调 */
    var onPinchEnd: ((Boolean) -> Unit)? = null

    /** 滑动回调 */
    var onSwipe: ((SwipeDirection, Float) -> Unit)? = null

    /**
     * 处理指针事件
     * 对应原版 ListZoomManager.onTouchEvent()
     *
     * @param event 指针事件
     */
    fun handlePointerEvent(event: PointerEvent) {
        when (event.type) {
            PointerEventType.Press -> {
                handlePress(event)
            }
            PointerEventType.Move -> {
                handleMove(event)
            }
            PointerEventType.Release -> {
                handleRelease(event)
            }
        }
    }

    /**
     * 处理按下事件
     */
    private fun handlePress(event: PointerEvent) {
        if (event.changes.size == 2) {
            // 两指按下：开始捏合
            beginPinch(event)
        } else if (event.changes.size == 1) {
            // 单指按下：开始滑动
            val change = event.changes[0]
            lastTouchX = change.position.x
            lastTouchY = change.position.y
            isSwiping = true
            swipeDirection = SwipeDirection.NONE
        }
    }

    /**
     * 处理移动事件
     */
    private fun handleMove(event: PointerEvent) {
        if (state.isPinching && event.changes.size >= 2) {
            // 捏合中
            updatePinch(event)
        } else if (isSwiping && event.changes.size == 1) {
            // 滑动中
            val change = event.changes[0]
            val dx = change.position.x - lastTouchX
            val dy = change.position.y - lastTouchY

            // 确定滑动方向
            if (swipeDirection == SwipeDirection.NONE) {
                if (abs(dx) > abs(dy) && abs(dx) > viewConfiguration.touchSlop) {
                    swipeDirection = if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                } else if (abs(dy) > abs(dx) && abs(dy) > viewConfiguration.touchSlop) {
                    swipeDirection = if (dy > 0) SwipeDirection.DOWN else SwipeDirection.UP
                }
            }

            // 触发滑动回调
            if (swipeDirection != SwipeDirection.NONE) {
                onSwipe?.invoke(swipeDirection, if (swipeDirection.isHorizontal) dx else dy)
            }

            lastTouchX = change.position.x
            lastTouchY = change.position.y
        }
    }

    /**
     * 处理释放事件
     */
    private fun handleRelease(event: PointerEvent) {
        if (state.isPinching) {
            finishPinch()
        }
        isSwiping = false
        swipeDirection = SwipeDirection.NONE
    }

    /**
     * 开始捏合
     * 对应原版 ListZoomManager.beginPinch()
     */
    private fun beginPinch(event: PointerEvent) {
        val p1 = event.changes[0]
        val p2 = event.changes[1]

        pointerId1 = p1.id
        pointerId2 = p2.id
        pinchBaseDistance = distance(p1, p2).coerceAtLeast(1f)
        lastRatio = 1f
        lastEventTime = p1.uptimeMillis
        pinchVelocityDp = 0f
        pinchRatioVelocity = 0f
        velocityTracker.resetTracking()

        state.isPinching = true
        state.isTransitioning = false
        state.transitionProgress = 0f
        state.boundaryElasticActive = false
        state.boundaryElasticScale = 1f
    }

    /**
     * 更新捏合
     * 对应原版 ListZoomManager.updatePinch()
     */
    private fun updatePinch(event: PointerEvent) {
        val p1 = event.changes.find { it.id == pointerId1 } ?: return
        val p2 = event.changes.find { it.id == pointerId2 } ?: return

        val currentDistance = distance(p1, p2)
        if (abs(currentDistance - pinchBaseDistance) < viewConfiguration.touchSlop && !state.isTransitioning) {
            return
        }

        val ratio = currentDistance / pinchBaseDistance
        val dt = (p1.uptimeMillis - lastEventTime).coerceAtLeast(1L)

        // 更新速度
        pinchVelocityDp = ((ratio - lastRatio) * pinchBaseDistance / density) / (dt / 1000f)
        pinchRatioVelocity = (ratio - lastRatio) / (dt / 1000f)
        lastRatio = ratio
        lastEventTime = p1.uptimeMillis

        val rawDelta = ratio - 1f
        val isZoomIn = rawDelta > 0f
        if (rawDelta == 0f && !state.isTransitioning) return

        // 开始过渡
        if (!state.isTransitioning) {
            val target = state.adjacentLevel(state.currentScene, isZoomIn)
            if (target == state.currentScene) {
                if (state.currentScene == ListZoomIndex.ZOOMED && isZoomIn) {
                    // 待确认网格切换
                    state.pendingGridZoom = true
                    state.isTransitioning = true
                    state.transitionIsZoomIn = true
                    state.transitionProgress = 0f
                    state.gridZoomProgress = 0f
                } else {
                    // 边界弹性
                    state.boundaryElasticActive = true
                    state.isTransitioning = true
                    state.transitionIsZoomIn = isZoomIn
                    state.boundaryElasticScale = 1f
                }
                onPinchStart?.invoke(target, isZoomIn)
            } else {
                // 正常过渡
                state.startTransition(target, isZoomIn)
                onPinchStart?.invoke(target, isZoomIn)
            }
        }

        // 更新网格缩放
        if (state.pendingGridZoom) {
            state.gridZoomProgress = rawDelta.coerceIn(0f, 1f)
            return
        }

        // 更新边界弹性
        if (state.boundaryElasticActive) {
            state.boundaryElasticScale = PowerampEasing.computeBoundaryElasticScale(
                abs(rawDelta), expands = state.transitionIsZoomIn
            )
            return
        }

        // 更新正常过渡
        val signedDelta = if (state.transitionIsZoomIn) rawDelta else -rawDelta
        state.transitionProgress = signedDelta.coerceIn(0f, 1f)
        state.transitionScaleFactor = PowerampEasing.powerampElasticScale(signedDelta, state.transitionIsZoomIn)
        onPinchProgress?.invoke(state.transitionProgress, state.transitionScaleFactor, state.transitionIsZoomIn)
    }

    /**
     * 完成捏合
     * 对应原版 ListZoomManager.finishPinch()
     */
    private fun finishPinch() {
        state.isPinching = false

        if (!state.isTransitioning) {
            state.resetGestureState()
            onPinchEnd?.invoke(false)
            return
        }

        // 边界弹性
        if (state.boundaryElasticActive) {
            state.resetGestureState()
            onPinchEnd?.invoke(false)
            return
        }

        // 待确认网格切换
        if (state.pendingGridZoom) {
            val shouldConfirmGrid = if (abs(pinchVelocityDp) >= 500f) {
                pinchVelocityDp > 0f
            } else {
                state.gridZoomProgress > 0.3f
            }
            if (shouldConfirmGrid) {
                state.columns = 4
            }
            state.resetGestureState()
            onPinchEnd?.invoke(shouldConfirmGrid)
            return
        }

        // 正常过渡
        val shouldConfirm = PowerampEasing.shouldConfirmTransition(
            pinchVelocityDp, state.transitionProgress, state.transitionIsZoomIn
        )
        state.commitTransition(shouldConfirm)
        onPinchEnd?.invoke(shouldConfirm)
    }

    /**
     * 计算两点距离
     */
    private fun distance(p1: PointerInputChange, p2: PointerInputChange): Float {
        val dx = p2.position.x - p1.position.x
        val dy = p2.position.y - p1.position.y
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * 滑动方向
 */
enum class SwipeDirection(val isHorizontal: Boolean) {
    LEFT(true),
    RIGHT(true),
    UP(false),
    DOWN(false),
    NONE(false)
}
