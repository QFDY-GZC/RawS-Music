package com.rawsmusic.ui.settings.compose.scene.container

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 纯 Compose 版本的列表过渡动画
 * 替代原版 ListZoomManager.snapAnimator
 *
 * 使用 Animatable 驱动过渡动画
 */
@Composable
fun rememberListTransition(state: ComposeListState): ListTransition {
    val scope = rememberCoroutineScope()
    val transitionAnimatable = remember { Animatable(0f) }

    return remember(state) {
        ListTransition(
            state = state,
            scope = scope,
            animatable = transitionAnimatable
        )
    }
}

class ListTransition(
    private val state: ComposeListState,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val animatable: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>
) {
    /**
     * 执行 Snap 动画
     * 对应原版 ListZoomManager.animateTransition()
     */
    fun animateSnap(confirmed: Boolean) {
        scope.launch {
            val start = state.transitionProgress
            val end = if (confirmed) 1f else 0f
            val startScale = state.transitionScaleFactor

            // 检查是否需要动画
            if (abs(start - end) < 0.001f && abs(startScale - 1f) < 0.001f) {
                state.transitionProgress = end
                state.transitionScaleFactor = 1f
                state.snapProgress = 1f
                state.commitTransition(confirmed)
                return@launch
            }

            // 计算动画时长
            val distance = abs(end - start).coerceAtLeast(0.001f)
            val minDuration = if (confirmed) 100L else 500L / 3L
            val duration = (500L * distance).toLong().coerceIn(minDuration, 500L)

            // 执行动画
            animatable.snapTo(0f)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = LinearEasing
                )
            ) {
                state.snapProgress = value
                state.transitionProgress = start + (end - start) * value
                state.transitionScaleFactor = startScale + (1f - startScale) * value
            }

            // 动画完成
            state.transitionProgress = end
            state.snapProgress = 1f
            state.commitTransition(confirmed)
        }
    }
}

/**
 * 边界弹性动画
 * 对应原版 PowerListView.animateBoundaryElasticBack()
 */
@Composable
fun rememberBoundaryElasticAnimation(state: ComposeListState): BoundaryElasticAnimation {
    val scope = rememberCoroutineScope()
    val elasticAnimatable = remember { Animatable(0f) }

    return remember(state) {
        BoundaryElasticAnimation(
            state = state,
            scope = scope,
            animatable = elasticAnimatable
        )
    }
}

class BoundaryElasticAnimation(
    private val state: ComposeListState,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val animatable: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>
) {
    /**
     * 执行边界弹性回弹动画
     */
    fun animateBack(startScale: Float) {
        scope.launch {
            val start = startScale.coerceIn(0.85f, 1.15f)
            if (abs(start - 1f) < 0.001f) {
                state.boundaryElasticScale = 1f
                return@launch
            }

            animatable.snapTo(start)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 350
                )
            ) {
                state.boundaryElasticScale = value
            }

            state.boundaryElasticScale = 1f
        }
    }
}
