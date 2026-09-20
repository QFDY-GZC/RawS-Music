package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sqrt

@Immutable
data class ComposeVirtualListMetrics(
    val columns: Int,
    val rowHeightPx: Int,
    val cellWidthPx: Int,
    val cellHeightPx: Int,
    val coverSizePx: Int,
    val sceneId: Int
)

internal fun computeVirtualListMetrics(
    widthPx: Int,
    density: Float,
    scaledDensity: Float,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams
): ComposeVirtualListMetrics {
    val columns = mode.columns.coerceAtLeast(1)
    val cellWidth = (widthPx / columns).coerceAtLeast(1)
    return if (mode.isGrid) {
        val cover = (cellWidth - (16f * density).toInt()).coerceAtLeast(1)
        val cellHeight = (cover + 58f * density).toInt().coerceAtLeast(1)
        ComposeVirtualListMetrics(
            columns = columns,
            rowHeightPx = cellHeight,
            cellWidthPx = cellWidth,
            cellHeightPx = cellHeight,
            coverSizePx = cover,
            sceneId = VirtualListSceneItem.SCENE_GRID
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
        ComposeVirtualListMetrics(
            columns = 1,
            rowHeightPx = rowHeight,
            cellWidthPx = widthPx.coerceAtLeast(1),
            cellHeightPx = rowHeight,
            coverSizePx = cover,
            sceneId = sceneIdForZoomIndex(mode.listLevel ?: ListZoomIndex.NORMAL)
        )
    }
}

internal fun Modifier.virtualListPointerInput(
    state: ComposeVirtualListState,
    density: Float
): Modifier = pointerInput(state, density) {
    coroutineScope {
        val gestureScope = this
        var settleJob: Job? = null
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size < 2) continue

                    // Lock the two fingers that started this zoom. A third finger must never silently
                    // replace a lifted member of the pair and feed a discontinuous span into geometry.
                    val first = pressed[0]
                    val second = pressed[1]
                    val firstId = first.id
                    val secondId = second.id
                    val baseDistance = distance(first.position, second.position).coerceAtLeast(1f)
                    var lastDistance = baseDistance
                    var lastRawDelta = 0f
                    var lastTime = first.uptimeMillis
                    var velocityDp = 0f
                    var progressVelocityPerSecond = 0f

                    settleJob?.cancel()
                    settleJob = null
                    state.beginPinch()
                    first.consume()
                    second.consume()

                    while (true) {
                        val move = awaitPointerEvent()
                        val a = move.changes.firstOrNull { it.id == firstId && it.pressed } ?: break
                        val b = move.changes.firstOrNull { it.id == secondId && it.pressed } ?: break
                        val currentDistance = distance(a.position, b.position).coerceAtLeast(1f)
                        val now = a.uptimeMillis
                        val dt = (now - lastTime).coerceAtLeast(1L) / 1000f
                        velocityDp = ((currentDistance - lastDistance) / density) / dt
                        val ratio = currentDistance / baseDistance
                        val rawDelta = ratio - 1f
                        val spanDeltaRatio = rawDelta - lastRawDelta
                        state.updatePinch(
                            rawDelta = rawDelta,
                            spanDeltaRatio = spanDeltaRatio,
                            velocityDp = velocityDp,
                        )
                        // scaleGestureOwner.mo2959() receives a second velocity in transition-progress coordinates.
                        // Span expansion is positive for zoom-in and negative for zoom-out; orient the
                        // ratio velocity so positive always means motion toward this transition's target.
                        val physicalRatioVelocity = (rawDelta - lastRawDelta) / dt
                        progressVelocityPerSecond = if (state.transitionZoomIn) {
                            physicalRatioVelocity
                        } else {
                            -physicalRatioVelocity
                        }
                        lastRawDelta = rawDelta
                        lastDistance = currentDistance
                        lastTime = now
                        a.consume()
                        b.consume()
                    }

                    settleJob = gestureScope.launch {
                        state.finishPinch(velocityDp, progressVelocityPerSecond)
                    }
                }
            }
        } finally {
            settleJob?.cancel()
            // pointerInput can be cancelled by navigation/modifier replacement while settle is in
            // flight. Never leave a half-transition as the new canonical geometry.
            state.resolveInterruptedPinch()
        }
    }
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}
