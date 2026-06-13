package com.rawsmusic.ui.settings.compose.scene.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 场景返回规格
 * 对应原版 PowerListView.SceneReturnSpec
 *
 * 描述场景返回动画的参数
 */
data class SceneReturnSpec(
    /** 目标矩形 (屏幕坐标) */
    val targetRect: Rect,
    /** 是否向右滑动 */
    val swipeRight: Boolean,
    /** 初始触摸 X 坐标 */
    val initialTouchX: Float = 0f,
    /** 初始触摸 Y 坐标 */
    val initialTouchY: Float = 0f
)

/**
 * 场景返回动画管理器
 * 对应原版 PowerListView 的场景返回动画系统
 *
 * 管理从歌曲列表返回首页的动画
 */
@Stable
class SceneReturnAnimation(
    private val listState: ComposeListState,
    private val scope: CoroutineScope
) {
    /** 返回动画控制器 */
    private val returnAnimatable = Animatable(0f)

    /** 是否正在运行返回动画 */
    var isReturnAnimating = false
        private set

    /** 返回进度 (0..1) */
    var returnProgress = 0f
        private set

    /** 是否向右滑动 */
    var isSwipeRight = true
        private set

    /** 源滚动位置 */
    private var sourceScrollY = 0

    /**
     * 开始场景返回动画
     * 对应原版 PowerListView.beginSceneReturnTransition()
     *
     * @param spec 返回规格
     * @return 是否成功开始
     */
    fun beginReturn(spec: SceneReturnSpec): Boolean {
        if (isReturnAnimating) return false

        isSwipeRight = spec.swipeRight
        sourceScrollY = listState.currentParams.rowHeightValue.toInt() // 简化
        returnProgress = 0f
        isReturnAnimating = true

        return true
    }

    /**
     * 更新返回进度
     * 对应原版 PowerListView.updateSceneReturnTransitionProgress()
     *
     * @param progress 进度 (0..1)
     */
    fun updateProgress(progress: Float) {
        if (!isReturnAnimating) return
        returnProgress = progress.coerceIn(0f, 1f)
    }

    /**
     * 更新拖拽位置
     * 对应原版 PowerListView.updateSceneReturnTransitionDrag()
     *
     * @param currentTouchX 当前触摸 X 坐标
     * @param currentTouchY 当前触摸 Y 坐标
     * @param containerWidth 容器宽度
     */
    fun updateDrag(currentTouchX: Float, currentTouchY: Float, containerWidth: Int) {
        if (!isReturnAnimating) return

        val rawDx = currentTouchX - (if (isSwipeRight) 0f else containerWidth.toFloat())
        val directedDx = if (isSwipeRight) rawDx else -rawDx
        updateProgress((directedDx / containerWidth).coerceIn(0f, 1f))
    }

    /**
     * 完成返回动画
     * 对应原版 PowerListView.finishSceneReturnTransition()
     *
     * @param confirmed 是否确认返回
     * @param velocity 速度 (px/s)
     * @param onComplete 完成回调
     */
    fun finishReturn(
        confirmed: Boolean? = null,
        velocity: Float = 0f,
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (!isReturnAnimating) {
            onComplete(false)
            return
        }

        val commit = confirmed ?: when {
            abs(velocity) >= 500f -> velocity > 0f
            returnProgress > 0.3f -> true
            else -> false
        }

        val endProgress = if (commit) 1f else 0f
        val duration = if (commit) 250L else 500L

        scope.launch {
            returnAnimatable.snapTo(returnProgress)
            returnAnimatable.animateTo(
                targetValue = endProgress,
                animationSpec = tween(
                    durationMillis = (abs(endProgress - returnProgress) * duration).toInt().coerceIn(80, duration.toInt()),
                    easing = LinearEasing
                )
            ) {
                returnProgress = value
            }

            // 动画完成
            completeReturn(commit)
            onComplete(commit)
        }
    }

    /**
     * 取消返回动画
     * 对应原版 PowerListView.cancelSceneReturnTransition()
     */
    fun cancelReturn() {
        if (!isReturnAnimating) return

        scope.launch {
            returnAnimatable.stop()
            completeReturn(false)
        }
    }

    /**
     * 完成返回动画
     * 对应原版 PowerListView.completeSceneReturnTransition()
     */
    private fun completeReturn(committed: Boolean) {
        isReturnAnimating = false
        returnProgress = if (committed) 1f else 0f

        if (committed) {
            // 返回成功，重置滚动位置
            listState.resetGestureState()
        }
    }
}

/**
 * 记住 SceneReturnAnimation
 */
@Composable
fun rememberSceneReturnAnimation(
    listState: ComposeListState
): SceneReturnAnimation {
    val scope = rememberCoroutineScope()
    return remember(listState) {
        SceneReturnAnimation(listState, scope)
    }
}
