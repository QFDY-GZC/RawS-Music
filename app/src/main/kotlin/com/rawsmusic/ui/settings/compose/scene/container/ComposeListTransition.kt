package com.rawsmusic.ui.settings.compose.scene.container

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.rawsmusic.ui.settings.compose.scene.gesture.PowerampEasing
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.ui.settings.powerlist.ListZoomIndex
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 纯 Compose 版本的列表过渡动画
 * 替代原版 ListZoomManager.snapAnimator
 *
 * 使用 Animatable 驱动过渡动画
 */
@Composable
fun ComposeListTransition(
    state: ComposeListState,
    onComplete: (ListZoomIndex) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val transitionAnimatable = remember { Animatable(0f) }

    /**
     * 执行 Snap 动画
     * 对应原版 ListZoomManager.animateTransition()
     *
     * @param confirmed 是否确认过渡
     * @param releaseProgressVelocity 释放时的进度速度
     */
    fun animateSnap(confirmed: Boolean, releaseProgressVelocity: Float = 0f) {
        scope.launch {
            val start = state.transitionProgress
            val end = if (confirmed) 1f else 0f
            val startScale = state.transitionScaleFactor

            // 检查是否需要动画
            if (abs(start - end) < 0.001f && abs(startScale - 1f) < 0.001f) {
                state.transitionProgress = end
                state.transitionScaleFactor = 1f
                state.snapProgress = 1f
                val finalLevel = if (confirmed) state.targetScene else state.sourceScene
                state.commitTransition(confirmed)
                onComplete(finalLevel)
                return@launch
            }

            // 计算动画时长
            val duration = computeSnapDurationMs(start, end, confirmed, startScale, releaseProgressVelocity)

            // 执行动画
            transitionAnimatable.snapTo(0f)
            transitionAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = if (releaseProgressVelocity != 0f) LinearEasing else { it -> it } // 使用线性或默认
                )
            ) { value ->
                state.snapProgress = value
                state.transitionProgress = start + (end - start) * value
                state.transitionScaleFactor = startScale + (1f - startScale) * value
            }

            // 动画完成
            state.transitionProgress = end
            state.snapProgress = 1f
            val finalLevel = if (confirmed) state.targetScene else state.sourceScene
            state.commitTransition(confirmed)
            onComplete(finalLevel)
        }
    }

    /**
     * 计算 Snap 动画时长
     * 对应原版 ListZoomManager.computeSnapDurationMs()
     */
    fun computeSnapDurationMs(
        start: Float,
        end: Float,
        confirm: Boolean,
        startScale: Float,
        releaseProgressVelocity: Float
    ): Long {
        // 速度交接时长
        if (releaseProgressVelocity != 0f) {
            val velocityDuration = computeVelocityHandoffDurationMs(start, end, confirm, releaseProgressVelocity)
            if (velocityDuration != null) {
                return velocityDuration
            }
        }

        // 普通时长
        val distance = abs(end - start).coerceAtLeast(0.001f)
        val minDuration = if (confirm) 100L else 500L / 3L
        val progressDuration = (500L * distance).toLong().coerceIn(minDuration, 500L)
        val elasticDuration = if (abs(startScale - 1f) > 0.001f) 350L else 0L
        return maxOf(progressDuration, elasticDuration)
    }

    /**
     * 计算速度交接时长
     * 对应原版 ListZoomManager.computeVelocityHandoffDurationMs()
     */
    fun computeVelocityHandoffDurationMs(
        start: Float,
        end: Float,
        confirm: Boolean,
        releaseProgressVelocity: Float
    ): Long? {
        if ((confirm && releaseProgressVelocity <= 0f) || (!confirm && releaseProgressVelocity >= 0f)) {
            return null
        }
        val velocity = if (confirm) {
            releaseProgressVelocity.coerceIn(1f / (500L / 1000f), 8f)
        } else {
            releaseProgressVelocity.coerceIn(-8f, -3.5f)
        }
        val distance = abs(end - start).coerceAtLeast(0.0001f)
        return (distance / abs(velocity) * 1000f).toLong().coerceAtLeast(1L)
    }

    // 返回动画控制函数
    return // Composable 不需要返回值，动画通过 state 驱动
}

/**
 * 边界弹性动画
 * 对应原版 PowerListView.animateBoundaryElasticBack()
 */
@Composable
fun ComposeBoundaryElasticAnimation(
    state: ComposeListState
) {
    val scope = rememberCoroutineScope()
    val elasticAnimatable = remember { Animatable(0f) }

    /**
     * 执行边界弹性回弹动画
     *
     * @param startScale 起始缩放因子
     */
    fun animateBoundaryElasticBack(startScale: Float) {
        scope.launch {
            val start = startScale.coerceIn(0.85f, 1.15f)
            if (abs(start - 1f) < 0.001f) {
                state.boundaryElasticScale = 1f
                return@launch
            }

            elasticAnimatable.snapTo(start)
            elasticAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 350,
                    easing = { it -> it } // AccelerateDecelerateInterpolator 等效
                )
            ) { value ->
                state.boundaryElasticScale = value
            }

            state.boundaryElasticScale = 1f
        }
    }

    // 返回动画控制函数
    return // Composable 不需要返回值
}
