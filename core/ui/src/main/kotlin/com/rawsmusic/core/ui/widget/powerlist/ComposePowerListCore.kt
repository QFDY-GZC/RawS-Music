package com.rawsmusic.core.ui.widget.powerlist

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.sqrt

@Immutable
data class ComposePowerListMetrics(
    val columns: Int,
    val rowHeightPx: Int,
    val cellWidthPx: Int,
    val cellHeightPx: Int,
    val coverSizePx: Int,
    val sceneId: Int
)

internal fun computePowerListMetrics(
    widthPx: Int,
    density: Float,
    scaledDensity: Float,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams
): ComposePowerListMetrics {
    val columns = mode.columns.coerceAtLeast(1)
    val cellWidth = (widthPx / columns).coerceAtLeast(1)
    return if (mode.isGrid) {
        val cover = (cellWidth - (16f * density).toInt()).coerceAtLeast(1)
        val cellHeight = (cover + 58f * density).toInt().coerceAtLeast(1)
        ComposePowerListMetrics(
            columns = columns,
            rowHeightPx = cellHeight,
            cellWidthPx = cellWidth,
            cellHeightPx = cellHeight,
            coverSizePx = cover,
            sceneId = PowerListSceneItem.SCENE_GRID
        )
    } else {
        val minRow = if (params.rowHeightIsSp) {
            (params.rowHeightValue * scaledDensity).toInt()
        } else {
            (params.rowHeightValue * density).toInt()
        }
        val cover = (params.coverSizeDp * density).toInt().coerceAtLeast(1)
        val coverContribution = cover +
            ((params.coverMarginTopDp + params.coverMarginBottomDp) * density).toInt()
        val titleHeight = (22f * params.textScale * scaledDensity).toInt()
        val line2Height = if (params.line2Visible) (18.25f * params.textScale * scaledDensity).toInt() else 0
        val metaHeight = if (params.metaVisible && params.metaInlineFraction < 1f) {
            (13.5f * params.textScale * scaledDensity).toInt()
        } else {
            0
        }
        val textContribution = titleHeight + line2Height + metaHeight + (22f * density).toInt()
        val rowHeight = maxOf(minRow, coverContribution, textContribution)
        ComposePowerListMetrics(
            columns = 1,
            rowHeightPx = rowHeight,
            cellWidthPx = widthPx.coerceAtLeast(1),
            cellHeightPx = rowHeight,
            coverSizePx = cover,
            sceneId = sceneIdForZoomIndex(mode.listLevel ?: ListZoomIndex.NORMAL)
        )
    }
}

internal fun Modifier.powerListPointerInput(
    state: ComposePowerListState,
    density: Float
): Modifier = pointerInput(state, density) {
    coroutineScope {
        val gestureScope = this
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size < 2) continue

                val first = pressed[0]
                val second = pressed[1]
                val baseDistance = distance(first.position, second.position).coerceAtLeast(1f)
                var lastDistance = baseDistance
                var lastTime = first.uptimeMillis
                var velocityDp = 0f
                state.beginPinch()
                first.consume()
                second.consume()

                while (true) {
                    val move = awaitPointerEvent()
                    val active = move.changes.filter { it.pressed }
                    if (active.size < 2) break
                    val a = active[0]
                    val b = active[1]
                    val currentDistance = distance(a.position, b.position).coerceAtLeast(1f)
                    val now = a.uptimeMillis
                    val dt = (now - lastTime).coerceAtLeast(1L) / 1000f
                    velocityDp = ((currentDistance - lastDistance) / density) / dt
                    val ratio = currentDistance / baseDistance
                    state.updatePinch(ratio - 1f, velocityDp)
                    lastDistance = currentDistance
                    lastTime = now
                    a.consume()
                    b.consume()
                }

                gestureScope.launch {
                    state.finishPinch(velocityDp)
                }
            }
        }
    }
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}
