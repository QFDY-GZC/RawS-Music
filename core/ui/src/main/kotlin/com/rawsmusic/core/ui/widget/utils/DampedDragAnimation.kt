package com.rawsmusic.core.ui.widget.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 带阻尼的拖拽动画工具类
 * 支持拖拽跟手 + 松手后弹簧动画归位 + 按压缩放效果
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedFloatingPointRange<Float>,
    private val visibilityThreshold: Float = 0.001f,
    private val initialScale: Float = 1f,
    private val pressedScale: Float = 1f,
    private val onDragStarted: () -> Unit = {},
    private val onDragStopped: DampedDragAnimation.() -> Unit = {},
    private val onDrag: DampedDragAnimation.(change: PointerInputChange, dragAmount: Offset) -> Unit = { _, _ -> }
) {
    private val valueAnimatable = Animatable(initialValue, visibilityThreshold)
    private val scaleAnimatable = Animatable(initialScale, visibilityThreshold)
    private val pressProgressAnimatable = Animatable(0f, 0.001f)

    val value: Float get() = valueAnimatable.value
    val targetValue: Float get() = valueAnimatable.targetValue
    val scale: Float get() = scaleAnimatable.value
    val pressProgress: Float get() = pressProgressAnimatable.value

    // 供 layerBlock 使用的缩放属性
    val scaleX: Float get() = scaleAnimatable.value
    val scaleY: Float get() = scaleAnimatable.value

    // 拖拽速度（px/s），用于速度相关的视觉效果
    var velocity: Float = 0f
        private set

    private var lastDragTime = 0L
    private var lastDragX = 0f

    private val springSpec = spring<Float>(
        dampingRatio = 1f,
        stiffness = 300f,
        visibilityThreshold = visibilityThreshold
    )

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            press()
            onDragStarted()
            lastDragTime = android.os.SystemClock.uptimeMillis()
            lastDragX = down.position.x
            velocity = 0f

            var pastTouchSlop = false
            val touchSlop = viewConfiguration.touchSlop

            drag(down.id) { change ->
                val dragAmount = change.position - change.previousPosition
                if (!pastTouchSlop) {
                    pastTouchSlop = dragAmount.getDistance() > touchSlop
                    if (!pastTouchSlop) return@drag
                }
                change.consume()

                // 计算拖拽速度
                val now = android.os.SystemClock.uptimeMillis()
                val dt = (now - lastDragTime).coerceAtLeast(1)
                velocity = (change.position.x - lastDragX) / dt * 1000f
                lastDragTime = now
                lastDragX = change.position.x

                onDrag(this@DampedDragAnimation, change, dragAmount)
            }

            release()
            onDragStopped(this@DampedDragAnimation)
        }
    }

    fun updateValue(newValue: Float) {
        val clamped = newValue.fastCoerceIn(valueRange.start, valueRange.endInclusive)
        animationScope.launch {
            valueAnimatable.snapTo(clamped)
        }
    }

    fun animateToValue(target: Float) {
        val clamped = target.fastCoerceIn(valueRange.start, valueRange.endInclusive)
        animationScope.launch {
            valueAnimatable.animateTo(clamped, springSpec)
        }
    }

    private fun press() {
        animationScope.launch {
            pressProgressAnimatable.animateTo(1f, spring(0.5f, 300f, 0.001f))
        }
        animationScope.launch {
            scaleAnimatable.animateTo(pressedScale, spring(0.5f, 300f, visibilityThreshold))
        }
    }

    private fun release() {
        animationScope.launch {
            pressProgressAnimatable.animateTo(0f, spring(0.5f, 300f, 0.001f))
        }
        animationScope.launch {
            scaleAnimatable.animateTo(initialScale, spring(0.5f, 300f, visibilityThreshold))
        }
    }
}
