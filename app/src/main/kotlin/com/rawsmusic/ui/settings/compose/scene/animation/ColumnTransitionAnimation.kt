package com.rawsmusic.ui.settings.compose.scene.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.rawsmusic.ui.settings.compose.scene.gesture.PowerampEasing
import com.rawsmusic.ui.settings.compose.scene.state.DualEngineState
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.core.ui.widget.powerlist.ListZoomLevels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 列数切换动画管理器
 * 对应原版 PowerListView 的列数切换动画系统
 *
 * 管理从列表模式切换到网格模式的动画
 */
@Stable
class ColumnTransitionAnimation(
    private val dualEngineState: DualEngineState,
    private val listState: ComposeListState,
    private val scope: CoroutineScope
) {
    /** 列数切换动画控制器 */
    private val columnAnimatable = Animatable(0f)

    /** 是否正在运行列数切换动画 */
    var isColumnAnimating = false
        private set

    /** 源列数 */
    var sourceColumns = 1
        private set

    /** 目标列数 */
    var targetColumns = 1
        private set

    /** 是否为放大方向 */
    var isZoomIn = true
        private set

    /**
     * 开始列数切换动画
     * 对应原版 PowerListView.startColumnZoomTransition()
     *
     * @param targetColumns 目标列数
     * @param isZoomIn 是否为放大方向
     */
    fun startTransition(targetColumns: Int, isZoomIn: Boolean) {
        if (isColumnAnimating) return
        if (targetColumns == listState.columns) return

        this.sourceColumns = listState.columns
        this.targetColumns = targetColumns
        this.isZoomIn = isZoomIn

        // 开始双引擎过渡
        val sourceParams = ListZoomLevels.params[listState.currentScene]!!
        val targetParams = ListZoomLevels.params[ListZoomIndex.NORMAL]!! // 网格模式使用 NORMAL 参数

        dualEngineState.startTransition(
            sourceParams = sourceParams,
            targetParams = targetParams,
            sourceColumns = sourceColumns,
            targetColumns = targetColumns,
            isZoomIn = isZoomIn
        )

        listState.startTransition(listState.currentScene, isZoomIn)
    }

    /**
     * 更新列数切换进度
     * 对应原版 PowerListView.updateColumnZoomTransition()
     *
     * @param progress 进度 (0..1)
     * @param scaleFactor 缩放因子
     */
    fun updateProgress(progress: Float, scaleFactor: Float = 1f) {
        if (!dualEngineState.isDualEngineTransition) return

        dualEngineState.updateProgress(progress, scaleFactor)
        listState.updateTransitionProgress(progress, scaleFactor)
    }

    /**
     * 完成列数切换动画
     * 对应原版 PowerListView.finishColumnZoomTransition()
     *
     * @param confirmed 是否确认切换
     * @param velocity 速度 (dp/s)
     */
    fun finishTransition(confirmed: Boolean, velocity: Float = 0f) {
        if (!dualEngineState.isDualEngineTransition) return

        val start = dualEngineState.transitionProgress
        val end = if (confirmed) 1f else 0f

        // 计算动画时长
        val baseDuration = if (confirmed) 250L else 500L
        val minDuration = if (confirmed) 100L else baseDuration / 3L
        val duration = (abs(end - start) * baseDuration).toLong().coerceIn(minDuration, baseDuration)

        scope.launch {
            isColumnAnimating = true
            columnAnimatable.snapTo(start)
            columnAnimatable.animateTo(
                targetValue = end,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = LinearEasing
                )
            ) {
                updateProgress(value, 1f)
            }

            // 动画完成
            completeTransition(confirmed)
            isColumnAnimating = false
        }
    }

    /**
     * 完成列数切换
     * 对应原版 PowerListView.completeColumnZoomTransition()
     */
    private fun completeTransition(confirmed: Boolean) {
        if (confirmed) {
            dualEngineState.confirmTransition()
            listState.commitTransition(true)
            listState.columns = targetColumns
        } else {
            dualEngineState.cancelTransition()
            listState.commitTransition(false)
        }
    }

    /**
     * 取消列数切换动画
     */
    fun cancelTransition() {
        if (!dualEngineState.isDualEngineTransition) return

        scope.launch {
            columnAnimatable.stop()
            completeTransition(false)
            isColumnAnimating = false
        }
    }
}

/**
 * 记住 ColumnTransitionAnimation
 */
@Composable
fun rememberColumnTransitionAnimation(
    dualEngineState: DualEngineState,
    listState: ComposeListState
): ColumnTransitionAnimation {
    val scope = rememberCoroutineScope()
    return remember(dualEngineState, listState) {
        ColumnTransitionAnimation(dualEngineState, listState, scope)
    }
}
