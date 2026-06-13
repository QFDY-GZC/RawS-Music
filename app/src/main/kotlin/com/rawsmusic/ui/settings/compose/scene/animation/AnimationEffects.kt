package com.rawsmusic.ui.settings.compose.scene.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 动画效果管理器
 * 对应原版的动画效果系统
 *
 * 提供各种动画效果：弹性、淡入淡出、缩放等
 */
@Stable
class AnimationEffects(
    private val scope: CoroutineScope
) {
    /** 弹性动画控制器 */
    private val springAnimatable = Animatable(0f)

    /** 淡入淡出动画控制器 */
    private val fadeAnimatable = Animatable(1f)

    /** 缩放动画控制器 */
    private val scaleAnimatable = Animatable(1f)

    /**
     * 弹性动画
     *
     * @param initialValue 初始值
     * @param targetValue 目标值
     * @param dampingRatio 阻尼比
     * @param stiffness 刚度
     * @param onUpdate 更新回调
     * @param onComplete 完成回调
     */
    fun springAnimation(
        initialValue: Float,
        targetValue: Float,
        dampingRatio: Float = Spring.DampingRatioMediumBouncy,
        stiffness: Float = Spring.StiffnessMedium,
        onUpdate: (Float) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        scope.launch {
            springAnimatable.snapTo(initialValue)
            springAnimatable.animateTo(
                targetValue = targetValue,
                animationSpec = spring(
                    dampingRatio = dampingRatio,
                    stiffness = stiffness
                )
            ) {
                onUpdate(value)
            }
            onComplete()
        }
    }

    /**
     * 淡入动画
     *
     * @param duration 时长 (ms)
     * @param onUpdate 更新回调
     * @param onComplete 完成回调
     */
    fun fadeIn(
        duration: Int = 300,
        onUpdate: (Float) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        scope.launch {
            fadeAnimatable.snapTo(0f)
            fadeAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = duration,
                    easing = LinearEasing
                )
            ) {
                onUpdate(value)
            }
            onComplete()
        }
    }

    /**
     * 淡出动画
     *
     * @param duration 时长 (ms)
     * @param onUpdate 更新回调
     * @param onComplete 完成回调
     */
    fun fadeOut(
        duration: Int = 300,
        onUpdate: (Float) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        scope.launch {
            fadeAnimatable.snapTo(1f)
            fadeAnimatable.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = duration,
                    easing = LinearEasing
                )
            ) {
                onUpdate(value)
            }
            onComplete()
        }
    }

    /**
     * 缩放动画
     *
     * @param initialValue 初始值
     * @param targetValue 目标值
     * @param duration 时长 (ms)
     * @param onUpdate 更新回调
     * @param onComplete 完成回调
     */
    fun scaleAnimation(
        initialValue: Float,
        targetValue: Float,
        duration: Int = 300,
        onUpdate: (Float) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        scope.launch {
            scaleAnimatable.snapTo(initialValue)
            scaleAnimatable.animateTo(
                targetValue = targetValue,
                animationSpec = tween(
                    durationMillis = duration,
                    easing = LinearEasing
                )
            ) {
                onUpdate(value)
            }
            onComplete()
        }
    }

    /**
     * 弹性缩放动画
     *
     * @param initialValue 初始值
     * @param targetValue 目标值
     * @param dampingRatio 阻尼比
     * @param onUpdate 更新回调
     * @param onComplete 完成回调
     */
    fun springScaleAnimation(
        initialValue: Float,
        targetValue: Float,
        dampingRatio: Float = Spring.DampingRatioMediumBouncy,
        onUpdate: (Float) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        springAnimation(
            initialValue = initialValue,
            targetValue = targetValue,
            dampingRatio = dampingRatio,
            stiffness = Spring.StiffnessMedium,
            onUpdate = onUpdate,
            onComplete = onComplete
        )
    }

    /**
     * 按钮点击动画
     *
     * @param onClick 点击回调
     */
    fun buttonClickAnimation(
        onClick: () -> Unit = {}
    ) {
        springAnimation(
            initialValue = 1f,
            targetValue = 0.95f,
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessHigh,
            onUpdate = {},
            onComplete = {
                springAnimation(
                    initialValue = 0.95f,
                    targetValue = 1f,
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                    onUpdate = {},
                    onComplete = onClick
                )
            }
        )
    }

    /**
     * 取消所有动画
     */
    fun cancelAll() {
        scope.launch {
            springAnimatable.stop()
            fadeAnimatable.stop()
            scaleAnimatable.stop()
        }
    }
}

/**
 * 记住 AnimationEffects
 */
@Composable
fun rememberAnimationEffects(): AnimationEffects {
    val scope = rememberCoroutineScope()
    return remember { AnimationEffects(scope) }
}
