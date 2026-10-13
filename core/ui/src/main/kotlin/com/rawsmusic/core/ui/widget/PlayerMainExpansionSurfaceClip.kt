package com.rawsmusic.core.ui.widget

import android.graphics.Path as AndroidPath
import android.view.ViewTreeObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

/**
 * One screen-space MAIN <-> PLAYER surface clip for both standard and immersive renderers.
 *
 * Expansion is read on the draw clock. A scene drag therefore invalidates only drawing instead
 * of composing/measuring the complete (and expensive) player hierarchy on every MotionEvent.
 * The source rectangle is the real MiniPlayer surface, not the navigation or artwork bounds.
 */
internal fun Modifier.playerMainExpansionSurfaceClip(
    active: Boolean,
    collapsed: PlayerSurfaceHandoffBounds,
    expansion: () -> Float,
    expansionState: State<Float>? = null,
): Modifier = composed {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    var viewport by remember(view, configuration) { mutableStateOf(Rect.Zero) }
    var expandedCorners by remember(view, configuration) { mutableStateOf(PlayerWindowCornerRadii.Zero) }
    DisposableEffect(view, viewport, configuration) {
        var lastInsets = view.rootWindowInsets
        expandedCorners = readPlayerWindowCornerRadii(view, viewport)
        // Observe late/changing insets without replacing Compose's own window-insets listener.
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            val insets = view.rootWindowInsets
            if (insets !== lastInsets) {
                lastInsets = insets
                expandedCorners = readPlayerWindowCornerRadii(view, viewport)
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else view.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }
    onGloballyPositioned { coordinates ->
        viewport = coordinates.boundsInWindow()
        expandedCorners = readPlayerWindowCornerRadii(view, viewport)
    }.drawWithCache {
        // Observe the expansion in the cache owner, independently of child layer recording.
        // Reading it only inside onDrawWithContent left the initial p=0 clip recorded while
        // descendant graphicsLayer properties continued advancing during a cold entry.
        val fraction = (expansionState?.value ?: expansion()).coerceIn(0f, 1f)
        val clipPath = AndroidPath()
        val pathRadii = FloatArray(8)
        onDrawWithContent playerSurfaceDraw@{
            if (!active) {
                drawContent()
            } else {
                val surface = resolvePlayerSurfaceHandoffBounds(
                    sourceLeft = collapsed.left,
                    sourceTop = collapsed.top,
                    sourceRight = collapsed.right,
                    sourceBottom = collapsed.bottom,
                    sourceCornerRadius = collapsed.cornerRadius,
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    fraction = fraction,
                )
                val corners = resolvePlayerSurfaceCornerRadii(
                    collapsed.cornerRadius, expandedCorners, fraction,
                    surface.right - surface.left, surface.bottom - surface.top,
                )
                if (maxOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft) > 0.5f) {
                    pathRadii[0] = corners.topLeft
                    pathRadii[1] = corners.topLeft
                    pathRadii[2] = corners.topRight
                    pathRadii[3] = corners.topRight
                    pathRadii[4] = corners.bottomRight
                    pathRadii[5] = corners.bottomRight
                    pathRadii[6] = corners.bottomLeft
                    pathRadii[7] = corners.bottomLeft
                    clipPath.rewind()
                    clipPath.addRoundRect(
                        surface.left, surface.top, surface.right, surface.bottom,
                        pathRadii,
                        AndroidPath.Direction.CW,
                    )
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val saveCount = nativeCanvas.save()
                    try {
                        nativeCanvas.clipPath(clipPath)
                        drawContent()
                    } finally {
                        nativeCanvas.restoreToCount(saveCount)
                    }
                } else {
                    clipRect(surface.left, surface.top, surface.right, surface.bottom) {
                        this@playerSurfaceDraw.drawContent()
                    }
                }
            }
        }
    }
}
