package com.rawsmusic.core.ui.scene

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 当前是否有共享元素过渡正在活跃。
 */
val LocalIsSharedTransitionActive = staticCompositionLocalOf { false }

/**
 * 共享文本动画 modifier。
 *
 * 规则：
 * - activeSceneId（= fromSceneId）的真实文本做 translation，从自己位置移到目标位置
 * - 另一方只注册位置，视觉隐藏 (alpha=0)
 * - 过渡开始时冻结两端坐标，避免父容器 graphicsLayer 动画导致每帧变化
 * - 过渡结束后两方都正常显示
 *
 * Modifier 顺序：onGloballyPositioned 在 .offset 之前，避免反馈循环。
 */
fun Modifier.sharedTextAnimated(
    registry: SharedTransitionRegistry,
    sceneId: String,
    elementId: String,
    text: String,
    color: Color,
    fontSizeSp: Float,
    fontWeight: Int = 0
): Modifier = composed {
    val spec = LocalSharedTransitionSpec.current

    DisposableEffect(sceneId, elementId) {
        onDispose {
            registry.unregister(sceneId, elementId)
        }
    }

    // 注册位置（在 offset 之前，拿到未偏移的布局坐标）
    val positioned = onGloballyPositioned { coordinates ->
        val pos = coordinates.positionInWindow()
        val size = coordinates.size
        registry.register(
            sceneId = sceneId,
            elementId = elementId,
            snapshot = SharedTransitionRegistry.SharedElementSnapshot(
                sceneId = sceneId,
                elementId = elementId,
                boundsInWindow = Rect(pos.x, pos.y, pos.x + size.width, pos.y + size.height),
                text = text,
                color = color,
                fontSizeSp = fontSizeSp,
                fontWeight = fontWeight
            )
        )
    }

    if (!spec.active) {
        return@composed this.then(positioned)
    }

    val isTransitionEndpoint =
        sceneId == spec.fromSceneId || sceneId == spec.toSceneId

    if (!isTransitionEndpoint) {
        return@composed this.then(positioned)
    }

    // 非 active 的那一边：只注册位置，视觉隐藏
    if (sceneId != spec.activeSceneId) {
        return@composed this.then(positioned).alpha(0f)
    }

    // ---- active 那一边：做 translation ----

    // 过渡开始时冻结两端坐标（只做一次）
    val lastTransitionKey = remember { mutableStateOf<String?>(null) }
    if (lastTransitionKey.value != spec.transitionKey) {
        val fromReady = registry.get(spec.fromSceneId, elementId) != null
        val toReady = registry.get(spec.toSceneId, elementId) != null
        if (fromReady && toReady) {
            registry.freeze(spec.fromSceneId, elementId)
            registry.freeze(spec.toSceneId, elementId)
            lastTransitionKey.value = spec.transitionKey
        } else {
            // 两端还没就绪，先隐藏
            return@composed this.then(positioned).alpha(0f)
        }
    }

    // 读取冻结坐标（不会每帧变化）
    val self = registry.getFrozen(sceneId, elementId)
        ?: registry.get(sceneId, elementId)
        ?: return@composed this.then(positioned).alpha(0f)

    val targetSceneId = if (sceneId == spec.fromSceneId) {
        spec.toSceneId
    } else {
        spec.fromSceneId
    }

    val target = registry.getFrozen(targetSceneId, elementId)
        ?: registry.get(targetSceneId, elementId)
        ?: return@composed this.then(positioned).alpha(0f)

    val t = spec.progress.coerceIn(0f, 1f)

    // 冻结的 dx/dy 不会每帧变化
    val dx = target.boundsInWindow.left - self.boundsInWindow.left
    val dy = target.boundsInWindow.top - self.boundsInWindow.top

    // t=0: 在自己位置 (offset=0)，t=1: 在目标位置 (offset=dx,dy)
    val offsetX = (dx * t).roundToInt()
    val offsetY = (dy * t).roundToInt()

    this
        .then(positioned)
        .offset { IntOffset(offsetX, offsetY) }
}
