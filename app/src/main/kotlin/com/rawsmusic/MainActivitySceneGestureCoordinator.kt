package com.rawsmusic

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.rawsmusic.core.ui.scene.NavigationState
import com.rawsmusic.core.ui.widget.PlayerSceneController
import kotlin.math.abs

/** Owns root-level scene and category swipe recognition for MainActivity. */
internal class MainActivitySceneGestureCoordinator(
    private val mainNavigation: NavigationState,
    private val playerScene: PlayerSceneController,
    private val isProgressSeekActive: () -> Boolean,
    private val isGestureBlocked: () -> Boolean,
    private val isAudioInfoSharedWindowActive: () -> Boolean,
) {
    fun install(base: Modifier): Modifier = base.pointerInput(Unit) {
        val pointerDensity = density
        awaitEachGesture {
            val down = awaitPointerEvent(PointerEventPass.Main)
                .changes
                .firstOrNull { it.changedToDownIgnoreConsumed() }
                ?: return@awaitEachGesture
            if (
                isProgressSeekActive() ||
                isGestureBlocked() ||
                playerScene.disableGestureIntercept ||
                playerScene.isTransitioning
            ) return@awaitEachGesture

            // MAIN owns category-to-category and nested-page gestures in
            // SceneTransitionHost. Keep category-back recognition here because
            // that host can be recomposed while a PowerList is measuring.
            if (
                isAudioInfoSharedWindowActive() ||
                playerScene.currentScene == PlayerSceneController.Scene.MAIN
            ) {
                handleMainNavigationDrag(down, pointerDensity)
                return@awaitEachGesture
            }

            // The standard lyric page owns its vertical cover gesture and its
            // explicit swipe-right detector. Do not interpret horizontal drift
            // during lyric scrolling as a partial LYRIC -> PLAYER scale.
            if (
                playerScene.currentScene == PlayerSceneController.Scene.LYRIC &&
                !playerScene.isImmersiveEnabled
            ) return@awaitEachGesture

            handleSceneTransitionDrag(down, pointerDensity)
        }
    }

    private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleMainNavigationDrag(
        down: androidx.compose.ui.input.pointer.PointerInputChange,
        pointerDensity: Float,
    ) {
        val start = down.position
        val pointerId = down.id
        val widthPx = size.width.toFloat().coerceAtLeast(1f)
        val touchSlop = viewConfiguration.touchSlop
        val densityValue = pointerDensity.coerceAtLeast(0.1f)
        val velocityTracker = VelocityTracker().apply {
            addPosition(down.uptimeMillis, down.position)
        }
        var draggingBack = false
        var rejected = false
        var dragDirection = 1f
        var progress = 0f

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == pointerId }
                ?: event.changes.firstOrNull()
                ?: break
            if (isProgressSeekActive() || isGestureBlocked()) {
                if (draggingBack) mainNavigation.releaseBackDrag(false, 0f)
                break
            }

            val dx = change.position.x - start.x
            val dy = change.position.y - start.y
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            if (!change.pressed) {
                if (draggingBack) {
                    val velocityX = velocityTracker.calculateVelocity().x
                    val signedVelocityX = velocityX * dragDirection
                    val velocityDp = signedVelocityX / densityValue
                    val commit = if (abs(velocityDp) >= 500f) {
                        velocityDp > 0f
                    } else {
                        progress >= 0.30f
                    }
                    mainNavigation.releaseBackDrag(commit, signedVelocityX)
                }
                break
            }

            if (!draggingBack && !rejected &&
                (abs(dx) > touchSlop || abs(dy) > touchSlop)
            ) {
                // MiniPlayerView gets first refusal at the Final pass. Never
                // turn its horizontal song switch into a scene-back gesture.
                val horizontal = abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f
                if (!horizontal || !mainNavigation.canNavigateBack()) {
                    rejected = true
                } else {
                    dragDirection = if (dx < 0f) -1f else 1f
                    draggingBack = mainNavigation.startBackDrag(dragDirection)
                }
            }

            if (draggingBack) {
                progress = ((abs(dx) - touchSlop) / widthPx).coerceIn(0f, 1f)
                mainNavigation.updateBackDrag(progress)
                change.consume()
            }
        }
    }

    private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleSceneTransitionDrag(
        down: androidx.compose.ui.input.pointer.PointerInputChange,
        pointerDensity: Float,
    ) {
        val start = down.position
        val pointerId = down.id
        var last = start
        var dragging = false
        var totalDx = 0f
        val touchSlop = viewConfiguration.touchSlop
        val widthPx = size.width.toFloat().coerceAtLeast(1f)
        val edgeBackWidthPx = viewConfiguration.touchSlop * 4f
        var velocityX = 0f
        var lastTime = down.uptimeMillis

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == pointerId }
                ?: event.changes.firstOrNull()
                ?: return

            if (
                isProgressSeekActive() ||
                isGestureBlocked() ||
                playerScene.disableGestureIntercept
            ) return

            if (change.changedToUpIgnoreConsumed()) {
                if (dragging) {
                    playerScene.releaseGestureDrag(totalDx, velocityX, widthPx, pointerDensity)
                    change.consume()
                }
                return
            }

            val dxFromStart = change.position.x - start.x
            val dyFromStart = change.position.y - start.y
            val dxFromLast = change.position.x - last.x
            val dyFromLast = change.position.y - last.y
            if (!dragging && dxFromLast == 0f && dyFromLast == 0f) continue

            if (!dragging &&
                abs(dxFromStart) > touchSlop &&
                abs(dxFromStart) > abs(dyFromStart) * 1.25f
            ) {
                if (playerScene.currentScene == PlayerSceneController.Scene.MAIN &&
                    !playerScene.isDeepHomePage
                ) return

                // Normal player mode only keeps system-style edge-back; its
                // content area remains available for the player's own gesture.
                val forceBackToMain = playerScene.currentScene == PlayerSceneController.Scene.PLAYER &&
                    dxFromStart > 0f &&
                    start.x <= edgeBackWidthPx
                if (
                    playerScene.currentScene == PlayerSceneController.Scene.PLAYER &&
                    !playerScene.isImmersiveEnabled &&
                    !forceBackToMain
                ) return

                dragging = true
                playerScene.onDragStart(dxFromStart < 0f, forceBackToMain)
            }

            if (dragging) {
                val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                velocityX = dxFromLast / dt * 1000f
                totalDx = dxFromStart
                last = change.position
                lastTime = change.uptimeMillis
                playerScene.updateGestureDrag(totalDx, widthPx)
                change.consume()
            }
        }
    }
}
