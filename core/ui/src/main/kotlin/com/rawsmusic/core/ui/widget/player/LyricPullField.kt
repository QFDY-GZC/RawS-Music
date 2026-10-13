package com.rawsmusic.core.ui.widget.player

import android.util.Log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

@Stable
internal class LyricPullFieldState {
    private val engine = LyricPullEngine()
    private val rowHeightsPx = mutableMapOf<Int, Int>()
    private val visibleIndices = linkedSetOf<Int>()
    private var orderedVisibleIndices: List<Int> = emptyList()
    private var traceAnchor = -1
    private var traceStartedMs = 0L
    private var traceStage = 2
    private var lastVsyncMs = Long.MIN_VALUE
    private var lastGapReportMs = Long.MIN_VALUE
    private var pendingJumpAnchor = -1
    private var pendingJumpStagger = false
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
    var stackOffsetPx: Float by mutableFloatStateOf(0f)
        private set
    var geometryRevision: Int by mutableIntStateOf(0)
        private set

    fun updateViewportHeight(heightPx: Int) {
        if (heightPx > 0) viewportHeightPx = heightPx
    }

    fun updateRowHeight(index: Int, heightPx: Int): Boolean {
        if (heightPx <= 0 || rowHeightsPx[index] == heightPx) return false
        rowHeightsPx[index] = heightPx
        geometryRevision++
        return true
    }

    fun setVisibleIndices(indices: Collection<Int>) {
        val nextOrderedIndices = indices.distinct().sorted()
        val rebindJump = pendingJumpAnchor in nextOrderedIndices && pendingJumpAnchor >= 0
        if (nextOrderedIndices == orderedVisibleIndices && !rebindJump) return

        orderedVisibleIndices = nextOrderedIndices
        visibleIndices.clear()
        visibleIndices.addAll(nextOrderedIndices)
        if (rebindJump) {
            val now = System.nanoTime() / 1_000_000L
            engine.rebindJumpRows(pendingJumpAnchor, nextOrderedIndices, now, pendingJumpStagger)
            pendingJumpAnchor = -1
            applyFrame(engine.advance(now, nextOrderedIndices))
        }
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
        return compactLyricCenterTransitionDistancePx(
            previousAnchor = previousAnchor,
            newAnchor = newAnchor,
            orderedRenderableIndices = orderedRenderableIndices,
            rowHeightsPx = rowHeightsPx,
            fallbackHeightPx = fallbackHeight,
            spacingPx = spacingPx,
        )
    }

    fun beginForwardPull(anchorIndex: Int, pullDistancePx: Float, frameTimeMs: Long): Boolean {
        return beginTransition(anchorIndex, pullDistancePx, frameTimeMs, true)
    }

    fun beginJumpTransition(anchorIndex: Int, deltaPx: Float, frameTimeMs: Long, stagger: Boolean): Boolean {
        if (pendingJumpAnchor == anchorIndex) return false
        if (!beginTransition(anchorIndex, deltaPx, frameTimeMs, stagger)) return false
        pendingJumpAnchor = anchorIndex
        pendingJumpStagger = stagger
        Log.i("LyricLineMotion", "JUMP anchor=$anchorIndex delta=$deltaPx stagger=$stagger")
        return true
    }

    fun isJumpPending(anchorIndex: Int): Boolean = pendingJumpAnchor == anchorIndex

    fun beginTransition(anchorIndex: Int, deltaPx: Float, frameTimeMs: Long, stagger: Boolean = false): Boolean {
        // Fresh row measurements can request the same logical anchor while its presentation
        // spring is still moving. No new distance means no new target, not a cancellation.
        if (deltaPx == 0f) return false
        val started = engine.beginTransition(
            anchorIndex = anchorIndex,
            deltaPx = deltaPx,
            viewportHeightPx = viewportHeightPx.toFloat(),
            visibleIndices = orderedVisibleIndices,
            frameTimeMs = frameTimeMs,
            stagger = stagger,
        )
        if (started) {
            if (traceAnchor != anchorIndex) {
                traceAnchor = anchorIndex
                traceStartedMs = frameTimeMs
                traceStage = 0
                Log.i("LyricLineMotion", "BEGIN anchor=$anchorIndex delta=$deltaPx stagger=$stagger " +
                    "phases=${orderedVisibleIndices.take(6).joinToString { "$it:${engine.phaseOf(it)}" }}")
            }
            if (!running) {
                generation++
                lastVsyncMs = Long.MIN_VALUE
            }
            running = true
            applyFrame(engine.advance(frameTimeMs, orderedVisibleIndices))
        } else {
            cancel()
        }
        return started
    }

    fun cancel() {
        pendingJumpAnchor = -1
        if (running) Log.i("LyricLineMotion", "CANCEL anchor=$traceAnchor base=$stackOffsetPx")
        traceStage = 2
        traceAnchor = -1
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
        val gap = if (lastVsyncMs == Long.MIN_VALUE) 0L else frameTimeMs - lastVsyncMs
        if (gap > 32L && (lastGapReportMs == Long.MIN_VALUE || frameTimeMs - lastGapReportMs >= 1000L)) {
            Log.i("LyricLineMotion", "JANK anchor=$traceAnchor frameGapMs=$gap loop=$generation")
            lastGapReportMs = frameTimeMs
        }
        lastVsyncMs = maxOf(lastVsyncMs, frameTimeMs)
        applyFrame(engine.advance(frameTimeMs, orderedVisibleIndices))
        val elapsed = frameTimeMs - traceStartedMs
        if (traceStage < 2 && elapsed >= if (traceStage == 0) 80L else 200L) {
            Log.i("LyricLineMotion", "FRAME anchor=$traceAnchor elapsed=$elapsed base=$stackOffsetPx " +
                "rows=${orderedVisibleIndices.take(6).joinToString { "$it:${offsetPx(it)}:${engine.phaseOf(it)}" }}")
            traceStage++
        }
    }

    private fun applyFrame(frame: LyricPullFrame) {
        stackOffsetPx = frame.stackOffsetPx
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
    // One frame owner for this lyric surface. Retargeting never cancels a pending vsync.
    LaunchedEffect(state) {
        while (isActive) {
            snapshotFlow { state.running }.first { it }
            while (state.running) {
                val frameTimeMs = withFrameNanos { it / 1_000_000L }
                state.advance(frameTimeMs)
            }
        }
    }
    return state
}
