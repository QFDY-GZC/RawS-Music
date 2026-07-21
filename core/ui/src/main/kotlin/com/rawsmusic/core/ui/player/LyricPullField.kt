package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

@Stable
internal class LyricPullFieldState {
    private val engine = LyricPullEngine()
    private val rowHeightsPx = mutableMapOf<Int, Int>()
    private val visibleIndices = linkedSetOf<Int>()
    private val offsetsPx = mutableStateMapOf<Int, Float>()

    var viewportHeightPx: Int by mutableIntStateOf(0)
        private set
    var generation: Int by mutableIntStateOf(0)
        private set
    var running: Boolean by mutableStateOf(false)
        private set

    fun updateViewportHeight(heightPx: Int) {
        if (heightPx > 0) viewportHeightPx = heightPx
    }

    fun updateRowHeight(index: Int, heightPx: Int) {
        if (heightPx > 0) rowHeightsPx[index] = heightPx
    }

    fun setVisibleIndices(indices: Collection<Int>) {
        visibleIndices.clear()
        visibleIndices.addAll(indices)
        offsetsPx.keys.toList().forEach { index ->
            if (index !in visibleIndices) offsetsPx.remove(index)
        }
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

    fun beginForwardPull(anchorIndex: Int, pullDistancePx: Float, frameTimeMs: Long): Boolean {
        val started = engine.beginForwardPull(
            anchorIndex = anchorIndex,
            pullDistancePx = pullDistancePx,
            viewportHeightPx = viewportHeightPx.toFloat(),
            visibleIndices = visibleIndices,
            frameTimeMs = frameTimeMs
        )
        if (started) {
            running = true
            generation++
            applyFrame(engine.advance(frameTimeMs, visibleIndices))
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

    fun advance(frameTimeMs: Long) {
        applyFrame(engine.advance(frameTimeMs, visibleIndices))
    }

    private fun applyFrame(frame: LyricPullFrame) {
        offsetsPx.keys.toList().forEach { index ->
            if (index !in frame.offsetsPx) offsetsPx.remove(index)
        }
        frame.offsetsPx.forEach { (index, offset) ->
            if (offset == 0f) offsetsPx.remove(index) else offsetsPx[index] = offset
        }
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
