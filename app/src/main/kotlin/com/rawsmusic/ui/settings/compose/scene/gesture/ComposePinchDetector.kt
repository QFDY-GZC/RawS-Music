package com.rawsmusic.ui.settings.compose.scene.gesture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.core.ui.widget.powerlist.ListZoomLevels
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 纯 Compose 版本的捏合手势检测器
 * 替代原版 ListZoomManager 的手势处理
 *
 * 使用 awaitPointerEventScope 实现两指捏合检测
 */
class ComposePinchDetector(
    private val state: ComposeListState,
    private val viewConfiguration: ViewConfiguration,
    private val density: Float
) {
    // ==================== 手势状态 ====================
    private var pointerId1: PointerId = PointerId(0)
    private var pointerId2: PointerId = PointerId(0)
    private var pinchBaseDistance: Float = 1f
    private var lastRatio: Float = 1f
    private var lastEventTime: Long = 0L
    private var pinchVelocityDp: Float = 0f
    private var pinchRatioVelocity: Float = 0f

    // 速度追踪
    private var velocityTracker = VelocityTracker()

    /**
     * 处理捏合手势
     * 对应原版 ListZoomManager.onTouchEvent()
     *
     * @param event 指针事件
     * @param isGridMode 是否为网格模式
     */
    fun handlePinchGesture(event: PointerEvent, isGridMode: Boolean) {
        when (event.type) {
            PointerEventType.Press -> {
                if (event.changes.size == 2) {
                    beginPinch(event)
                }
            }
            PointerEventType.Move -> {
                if (state.isPinching && event.changes.size >= 2) {
                    updatePinch(event, isGridMode)
                }
            }
            PointerEventType.Release -> {
                if (state.isPinching) {
                    finishPinch()
                }
            }
        }
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
        lastEventTime = event.changes[0].uptimeMillis
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
    private fun updatePinch(event: PointerEvent, isGridMode: Boolean) {
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
            } else {
                // 正常过渡
                state.startTransition(target, isZoomIn)
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
    }

    /**
     * 完成捏合
     * 对应原版 ListZoomManager.finishPinch()
     */
    private fun finishPinch() {
        state.isPinching = false

        if (!state.isTransitioning) {
            state.resetGestureState()
            return
        }

        // 边界弹性
        if (state.boundaryElasticActive) {
            // 触发弹性回弹动画
            state.resetGestureState()
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
                // 切换到网格模式 (TODO: 实现网格切换)
                state.columns = 4
            }
            state.resetGestureState()
            return
        }

        // 正常过渡
        val shouldConfirm = PowerampEasing.shouldConfirmTransition(
            pinchVelocityDp, state.transitionProgress, state.transitionIsZoomIn
        )
        state.commitTransition(shouldConfirm)
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
