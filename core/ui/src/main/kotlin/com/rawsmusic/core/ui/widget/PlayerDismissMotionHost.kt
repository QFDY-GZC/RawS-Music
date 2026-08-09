package com.rawsmusic.core.ui.widget

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 下滑关闭手势容器
 *
 * 下滑关闭手势参数：
 * - 顶部 132dp 区域触发拖拽
 * - 240dp 位移或 1250dp/s 速度触发关闭
 * - 拖拽过程中圆角裁剪 + 位移动画
 * - 报告 dismiss progress (0..1)
 */
@Composable
fun PlayerDismissMotionHost(
    openToken: Int,
    onDismissProgressChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    gestureEnabled: Boolean = true,
    gestureBlocked: Boolean = false,
    overlayContent: @Composable () -> Unit = {},
    content: @Composable (dismissingPlayer: Boolean) -> Unit
) {
    // The ordinary player owns its vertical scene gesture. When both host inputs are disabled,
    // do not keep an idle full-screen graphicsLayer around it: during the inner PLAYER -> MAIN
    // drag that extra retained layer can be presented together with the moving scene layer.
    if (!gestureEnabled && !backEnabled) {
        SideEffect { onDismissProgressChange(0f) }
        Box(modifier = modifier.fillMaxSize().clipToBounds()) {
            content(false)
            overlayContent()
        }
        return
    }

    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestGestureBlocked by rememberUpdatedState(gestureBlocked)
    val dragDismissOffset = remember { Animatable(0f) }
    var dismissingPlayer by remember { mutableStateOf(false) }
    val topDragLimitPx = with(density) { 132.dp.toPx() }
    val dismissThresholdPx = with(density) { 240.dp.toPx() }
    val dismissVelocityThresholdPx = with(density) { 1250.dp.toPx() }
    val dismissTargetPx = remember(view.height) {
        view.height.takeIf { it > 0 }?.toFloat() ?: with(density) { 760.dp.toPx() }
    }
    val dismissProgress = (dragDismissOffset.value / dismissThresholdPx).coerceIn(0f, 1f)
    val dragCornerRadius = 30.dp * dismissProgress

    fun dismissWithMotion() {
        if (dismissingPlayer) return
        scope.launch {
            if (dismissingPlayer) return@launch
            dismissingPlayer = true
            dragDismissOffset.stop()
            dragDismissOffset.animateTo(
                targetValue = dismissTargetPx,
                animationSpec = tween(durationMillis = 260, easing = LinearOutSlowInEasing)
            )
            latestOnDismiss()
        }
    }

    LaunchedEffect(openToken) {
        dismissingPlayer = false
        dragDismissOffset.snapTo(0f)
        onDismissProgressChange(0f)
    }
    SideEffect {
        onDismissProgressChange(dismissProgress)
    }
    DisposableEffect(Unit) {
        onDispose { onDismissProgressChange(0f) }
    }
    BackHandler(enabled = backEnabled) { dismissWithMotion() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .then(
                if (gestureEnabled) {
                    Modifier.pointerInput(dismissingPlayer, dismissTargetPx, dismissThresholdPx) {
                        var closeGesture = false
                        var gestureOffset = 0f
                        val velocityTracker = VelocityTracker()
                        detectDragGestures(
                            onDragStart = { offset ->
                                closeGesture = !dismissingPlayer &&
                                    !latestGestureBlocked &&
                                    offset.y <= topDragLimitPx
                                gestureOffset = dragDismissOffset.value
                                velocityTracker.resetTracking()
                                velocityTracker.addPosition(SystemClock.uptimeMillis(), offset)
                                if (closeGesture) {
                                    scope.launch { dragDismissOffset.stop() }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (latestGestureBlocked) {
                                    if (closeGesture) {
                                        closeGesture = false
                                        scope.launch {
                                            dragDismissOffset.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        }
                                    }
                                    return@detectDragGestures
                                }
                                if (!closeGesture) return@detectDragGestures
                                gestureOffset = (gestureOffset + if (dragAmount.y > 0f) {
                                    dragAmount.y
                                } else {
                                    dragAmount.y * 0.36f
                                }).coerceIn(0f, dismissTargetPx)
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                scope.launch { dragDismissOffset.snapTo(gestureOffset) }
                                if (gestureOffset > 0f) change.consume()
                            },
                            onDragCancel = {
                                closeGesture = false
                                scope.launch {
                                    dragDismissOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            },
                            onDragEnd = {
                                if (latestGestureBlocked) {
                                    closeGesture = false
                                    scope.launch {
                                        dragDismissOffset.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                    return@detectDragGestures
                                }
                                if (!closeGesture) return@detectDragGestures
                                closeGesture = false
                                val velocityY = velocityTracker.calculateVelocity().y
                                scope.launch {
                                    if (gestureOffset >= dismissThresholdPx || velocityY >= dismissVelocityThresholdPx) {
                                        if (!dismissingPlayer) {
                                            dismissingPlayer = true
                                            dragDismissOffset.animateTo(
                                                targetValue = dismissTargetPx,
                                                animationSpec = tween(durationMillis = 260, easing = LinearOutSlowInEasing)
                                            )
                                            latestOnDismiss()
                                        }
                                    } else {
                                        dragDismissOffset.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragDismissOffset.value
                    scaleX = 1f
                    scaleY = 1f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    alpha = 1f
                }
                .clip(
                    RoundedCornerShape(
                        topStart = dragCornerRadius,
                        topEnd = dragCornerRadius
                    )
                )
        ) {
            content(dismissingPlayer)
        }

        overlayContent()
    }
}
