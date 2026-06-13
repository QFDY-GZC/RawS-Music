package com.rawsmusic.ui.settings.compose.scene.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
 * 场景切换动画管理器
 * 对应原版 ListZoomManager 的动画系统
 *
 * 管理场景切换的 Snap 动画、弹性动画等
 */
@Stable
class SceneTransitionAnimation(
    private val dualEngineState: DualEngineState,
    private val listState: ComposeListState,
    private val scope: CoroutineScope
) {
    /** Snap 动画控制器 */
    private val snapAnimatable = Animatable(0f)

    /** 边界弹性动画控制器 */
    private val elasticAnimatable = Animatable(0f)

    /** 是否正在运行 Snap 动画 */
    var isSnapAnimating = false
        private set

    /** 是否正在运行边界弹性动画 */
    var isElasticAnimating = false
        private set

    /**
     * 执行 Snap 动画
     * 对应原版 ListZoomManager.animateTransition()
     *
     * @param confirmed 是否确认过渡
     * @param startProgress 起始进度
     * @param endProgress 结束进度
     * @param startScaleFactor 起始缩放因子
     */
    fun animateSnap(
        confirmed: Boolean,
        startProgress: Float = dualEngineState.transitionProgress,
        endProgress: Float = if (confirmed) 1f else 0f,
        startScaleFactor: Float = dualEngineState.transitionScaleFactor
    ) {
        scope.launch {
            val distance = abs(endProgress - startProgress).coerceAtLeast(0.001f)

            // 检查是否需要动画
            if (distance < 0.001f && abs(startScaleFactor - 1f) < 0.001f) {
                dualEngineState.updateProgress(endProgress, 1f)
                if (confirmed) {
                    dualEngineState.confirmTransition()
                    listState.commitTransition(true)
                } else {
                    dualEngineState.cancelTransition()
                    listState.commitTransition(false)
                }
                return@launch
            }

            // 计算动画时长
            val duration = computeSnapDurationMs(startProgress, endProgress, confirmed, startScaleFactor)

            // 执行动画
            isSnapAnimating = true
            snapAnimatable.snapTo(0f)
            snapAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = LinearEasing
                )
            ) {
                val progress = value
                val currentProgress = startProgress + (endProgress - startProgress) * progress
                val currentScale = startScaleFactor + (1f - startScaleFactor) * progress
                dualEngineState.updateProgress(currentProgress, currentScale)
                listState.updateTransitionProgress(currentProgress, currentScale)
            }

            // 动画完成
            dualEngineState.updateProgress(endProgress, 1f)
            if (confirmed) {
                dualEngineState.confirmTransition()
                listState.commitTransition(true)
            } else {
                dualEngineState.cancelTransition()
                listState.commitTransition(false)
            }
            isSnapAnimating = false
        }
    }

    /**
     * 执行边界弹性动画
     * 对应原版 PowerListView.animateBoundaryElasticBack()
     *
     * @param startScale 起始缩放因子
     */
    fun animateBoundaryElastic(startScale: Float) {
        scope.launch {
            val start = startScale.coerceIn(0.85f, 1.15f)
            if (abs(start - 1f) < 0.001f) {
                listState.boundaryElasticScale = 1f
                return@launch
            }

            isElasticAnimating = true
            elasticAnimatable.snapTo(start)
            elasticAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 350)
            ) {
                listState.boundaryElasticScale = value
            }

            listState.boundaryElasticScale = 1f
            isElasticAnimating = false
        }
    }

    /**
     * 计算 Snap 动画时长
     * 对应原版 ListZoomManager.computeSnapDurationMs()
     */
    private fun computeSnapDurationMs(
        start: Float,
        end: Float,
        confirm: Boolean,
        startScale: Float
    ): Long {
        val distance = abs(end - start).coerceAtLeast(0.001f)
        val minDuration = if (confirm) 100L else 500L / 3L
        val progressDuration = (500L * distance).toLong().coerceIn(minDuration, 500L)
        val elasticDuration = if (abs(startScale - 1f) > 0.001f) 350L else 0L
        return maxOf(progressDuration, elasticDuration)
    }

    /**
     * 取消所有动画
     */
    fun cancelAll() {
        scope.launch {
            snapAnimatable.stop()
            elasticAnimatable.stop()
            isSnapAnimating = false
            isElasticAnimating = false
        }
    }
}

/**
 * 记住 SceneTransitionAnimation
 */
@Composable
fun rememberSceneTransitionAnimation(
    dualEngineState: DualEngineState,
    listState: ComposeListState
): SceneTransitionAnimation {
    val scope = rememberCoroutineScope()
    return remember(dualEngineState, listState) {
        SceneTransitionAnimation(dualEngineState, listState, scope)
    }
}
