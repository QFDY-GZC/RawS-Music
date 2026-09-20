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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
    val latestOnDismissProgressChange by rememberUpdatedState(onDismissProgressChange)
    val latestGestureBlocked by rememberUpdatedState(gestureBlocked)
    val dragDismissOffset = remember { Animatable(0f) }
    var directDragActive by remember { mutableStateOf(false) }
    var directDragOffset by remember { mutableFloatStateOf(0f) }
    var dismissingPlayer by remember { mutableStateOf(false) }
    val topDragLimitPx = with(density) { 132.dp.toPx() }
    val dismissThresholdPx = with(density) { 240.dp.toPx() }
    val dismissVelocityThresholdPx = with(density) { 1250.dp.toPx() }
    val dismissTargetPx = remember(view.height) {
        view.height.takeIf { it > 0 }?.toFloat() ?: with(density) { 760.dp.toPx() }
    }
    val renderedDismissOffset = if (directDragActive) directDragOffset else dragDismissOffset.value
    // Commit threshold and visual handoff are different coordinates. The 240dp threshold decides
    // whether the gesture closes, but MAIN should recover over the player's complete physical
    // travel. Publishing threshold progress made the retained page finish its alpha/scale far too
    // early and visually looked like there was no return animation at all.
    val visualDismissProgress = (renderedDismissOffset / dismissTargetPx.coerceAtLeast(1f))
        .coerceIn(0f, 1f)
    val dragCornerRadius = 30.dp * visualDismissProgress

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
        directDragActive = false
        directDragOffset = 0f
        dragDismissOffset.snapTo(0f)
        onDismissProgressChange(0f)
    }
    // Animatable owns only settle/back-button motion. Finger-driven motion is published directly
    // from onDrag so a quick direction reversal cannot queue stale snapTo coroutines behind the
    // pointer. This collector mirrors settle frames into the MAIN reveal clock without forcing the
    // entire player tree through a second scene animator.
    LaunchedEffect(dragDismissOffset, dismissTargetPx) {
        snapshotFlow { dragDismissOffset.value }.collect { offset ->
            if (!directDragActive) {
                latestOnDismissProgressChange(
                    (offset / dismissTargetPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                )
            }
        }
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
                                gestureOffset = if (directDragActive) {
                                    directDragOffset
                                } else {
                                    dragDismissOffset.value
                                }
                                velocityTracker.resetTracking()
                                velocityTracker.addPosition(SystemClock.uptimeMillis(), offset)
                                if (closeGesture) {
                                    directDragOffset = gestureOffset
                                    directDragActive = true
                                    latestOnDismissProgressChange(
                                        (gestureOffset / dismissTargetPx.coerceAtLeast(1f))
                                            .coerceIn(0f, 1f)
                                    )
                                    scope.launch { dragDismissOffset.stop() }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (latestGestureBlocked) {
                                    if (closeGesture) {
                                        closeGesture = false
                                        val settleStart = directDragOffset
                                        scope.launch {
                                            dragDismissOffset.snapTo(settleStart)
                                            directDragActive = false
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
                                // Once the dismiss gesture is captured, vertical motion belongs to
                                // this host in both directions. Applying extra resistance only when
                                // the finger reverses makes the sheet lag behind the pointer and is
                                // especially visible after a partial downward drag. Keep the motion
                                // physically reversible and 1:1; the release threshold still decides
                                // whether the gesture commits or springs back.
                                gestureOffset = (gestureOffset + dragAmount.y)
                                    .coerceIn(0f, dismissTargetPx)
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                // Pointer owns the physical offset synchronously. Do not dispatch a
                                // coroutine per event: reversing direction must update this frame,
                                // not after older snapTo jobs have drained.
                                directDragOffset = gestureOffset
                                latestOnDismissProgressChange(
                                    (gestureOffset / dismissTargetPx.coerceAtLeast(1f))
                                        .coerceIn(0f, 1f)
                                )
                                if (gestureOffset > 0f) change.consume()
                            },
                            onDragCancel = {
                                closeGesture = false
                                val settleStart = directDragOffset
                                scope.launch {
                                    dragDismissOffset.snapTo(settleStart)
                                    directDragActive = false
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
                                    val settleStart = directDragOffset
                                    scope.launch {
                                        dragDismissOffset.snapTo(settleStart)
                                        directDragActive = false
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
                                val settleStart = directDragOffset
                                scope.launch {
                                    dragDismissOffset.snapTo(settleStart)
                                    directDragActive = false
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
                    translationY = if (directDragActive) directDragOffset else dragDismissOffset.value
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
