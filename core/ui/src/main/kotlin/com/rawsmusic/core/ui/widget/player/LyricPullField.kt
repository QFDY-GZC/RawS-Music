package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

@Stable
internal class LyricPullFieldState {
    private val engine = LyricPullEngine()
    private val rowHeightsPx = mutableMapOf<Int, Int>()
    private val visibleIndices = linkedSetOf<Int>()
    private var orderedVisibleIndices: List<Int> = emptyList()
    // Keep per-row offsets out of snapshot state. Natural line changes already animate the
    // LazyColumn itself; publishing every trailing-row offset as mutableStateMap entries forced
    // N row recompositions + relayouts on every vsync.  Only a single frame revision is observable,
    // and rows consume the plain map from graphicsLayer so updates stay in the layer phase.
    private val offsetsPx = mutableMapOf<Int, Float>()

    var viewportHeightPx: Int by mutableIntStateOf(0)
        private set
    var generation: Int by mutableIntStateOf(0)
        private set

    var frameRevision: Int by mutableIntStateOf(0)
        private set
    var running: Boolean by mutableStateOf(false)
        private set

    fun updateViewportHeight(heightPx: Int) {
        if (heightPx > 0) viewportHeightPx = heightPx
    }

    fun updateRowHeight(index: Int, heightPx: Int): Boolean {
        if (heightPx <= 0 || rowHeightsPx[index] == heightPx) return false
        rowHeightsPx[index] = heightPx
        return true
    }

    fun setVisibleIndices(indices: Collection<Int>) {
        val nextOrderedIndices = indices.distinct().sorted()
        if (nextOrderedIndices == orderedVisibleIndices) return

        orderedVisibleIndices = nextOrderedIndices
        visibleIndices.clear()
        visibleIndices.addAll(nextOrderedIndices)
        var changed = false
        val iterator = offsetsPx.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !in visibleIndices) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) frameRevision++
    }

    fun estimateCompactPullDistancePx(
        previousAnchor: Int,
        newAnchor: Int,
        orderedRenderableIndices: List<Int>,
        spacingPx: Float
    ): Float {
        val previousPosition = orderedRenderableIndices.indexOf(previousAnchor)
        val newPosition = orderedRenderableIndices.indexOf(newAnchor)
        if (previousPosition < 0 || newPosition <= previousPosition) return 0f

        val knownHeights = rowHeightsPx.values.filter { it > 0 }
        val fallbackHeight = if (knownHeights.isEmpty()) {
            (viewportHeightPx / 5f).coerceAtLeast(1f)
        } else {
            knownHeights.sorted()[knownHeights.size / 2].toFloat()
        }
        var distance = 0f
        for (position in previousPosition until newPosition) {
            val index = orderedRenderableIndices[position]
            distance += (rowHeightsPx[index]?.toFloat() ?: fallbackHeight) + spacingPx
        }
        return distance
    }

    fun estimateCompactTransitionDistancePx(
        previousAnchor: Int,
        newAnchor: Int,
        orderedRenderableIndices: List<Int>,
        spacingPx: Float
    ): Float {
        val previousPosition = orderedRenderableIndices.indexOf(previousAnchor)
        val newPosition = orderedRenderableIndices.indexOf(newAnchor)
        if (previousPosition < 0 || newPosition < 0 || previousPosition == newPosition) return 0f

        val knownHeights = rowHeightsPx.values.filter { it > 0 }
        val fallbackHeight = if (knownHeights.isEmpty()) {
            (viewportHeightPx / 5f).coerceAtLeast(1f)
        } else {
            knownHeights.sorted()[knownHeights.size / 2].toFloat()
        }
        val first = minOf(previousPosition, newPosition)
        val last = maxOf(previousPosition, newPosition)
        var distance = 0f
        for (position in first until last) {
            val index = orderedRenderableIndices[position]
            distance += (rowHeightsPx[index]?.toFloat() ?: fallbackHeight) + spacingPx
        }
        return if (newPosition > previousPosition) distance else -distance
    }

    fun beginForwardPull(anchorIndex: Int, pullDistancePx: Float, frameTimeMs: Long): Boolean {
        val started = engine.beginForwardPull(
            anchorIndex = anchorIndex,
            pullDistancePx = pullDistancePx,
            viewportHeightPx = viewportHeightPx.toFloat(),
            visibleIndices = orderedVisibleIndices,
            frameTimeMs = frameTimeMs
        )
        if (started) {
            running = true
            generation++
            applyFrame(engine.advance(frameTimeMs, orderedVisibleIndices))
        } else {
            cancel()
        }
        return started
    }

    fun cancel() {
        applyFrame(engine.reset())
        running = false
        generation++
    }

    fun offsetPx(index: Int): Float = offsetsPx[index] ?: 0f

    fun knownRowHeightPx(index: Int): Float? = rowHeightsPx[index]?.toFloat()

    fun fallbackRowHeightPx(): Float {
        val knownHeights = rowHeightsPx.values.filter { it > 0 }
        return if (knownHeights.isEmpty()) {
            (viewportHeightPx / 5f).coerceAtLeast(1f)
        } else {
            knownHeights.sorted()[knownHeights.size / 2].toFloat()
        }
    }

    fun advance(frameTimeMs: Long) {
        applyFrame(engine.advance(frameTimeMs, orderedVisibleIndices))
    }

    private fun applyFrame(frame: LyricPullFrame) {
        var changed = false
        val nextOffsets = frame.offsetsPx
        val iterator = offsetsPx.keys.iterator()
        while (iterator.hasNext()) {
            val index = iterator.next()
            if (index !in nextOffsets || nextOffsets[index] == 0f) {
                iterator.remove()
                changed = true
            }
        }
        nextOffsets.forEach { (index, offset) ->
            if (offset == 0f) return@forEach
            val old = offsetsPx[index]
            if (old == null || old != offset) {
                offsetsPx[index] = offset
                changed = true
            }
        }
        if (changed) frameRevision++
        running = frame.running
    }
}

@Composable
internal fun rememberLyricPullFieldState(key: Any?): LyricPullFieldState {
    val state = remember(key) { LyricPullFieldState() }
    val generation = state.generation
    LaunchedEffect(state, generation) {
        while (state.running) {
            val frameTimeMs = withFrameNanos { it / 1_000_000L }
            state.advance(frameTimeMs)
        }
    }
    return state
}
