package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs
import kotlin.math.max

internal enum class LyricPullItemPhase {
    Initial,
    WaitingForPull,
    Pulling,
    Done
}

internal data class LyricPullFrame(
    val offsetsPx: Map<Int, Float>,
    val running: Boolean
)

/**
 * Per-item lyric reflow state machine expressed in screen-space offsets.
 *
 * Each visible lyric row follows the shared 550 ms scroll while retaining its own pull phase
 * and timing. LazyColumn owns the base coordinates in Compose, so this engine keeps the
 * per-item lifecycle and emits the complementary placement offset consumed by each row.
 */
internal class LyricPullEngine {
    private data class ItemState(
        var phase: LyricPullItemPhase = LyricPullItemPhase.Initial,
        var baseOffsetPx: Float = 0f,
        var amplitudePx: Float = 0f,
        var currentOffsetPx: Float = 0f,
        var startTimeMs: Long = 0L,
        var durationMs: Int = 0
    )

    private val items = mutableMapOf<Int, ItemState>()
    private var globalStartTimeMs: Long = 0L
    private var globalPullDistancePx: Float = 0f
    private var globalDurationMs: Int = 0
    private var perItemDelayMs: Int = LyricPullSpec.MAX_ITEM_DELAY_MS
    private var anchorIndex: Int = -1
    private var active: Boolean = false

    fun reset(): LyricPullFrame {
        items.values.forEach { it.reset() }
        active = false
        globalStartTimeMs = 0L
        globalPullDistancePx = 0f
        globalDurationMs = 0
        return LyricPullFrame(emptyMap(), running = false)
    }

    fun beginForwardPull(
        anchorIndex: Int,
        pullDistancePx: Float,
        viewportHeightPx: Float,
        visibleIndices: Collection<Int>,
        frameTimeMs: Long
    ): Boolean {
        val distance = abs(pullDistancePx)
        if (anchorIndex < 0 || distance <= 0f || !distance.isFinite() || viewportHeightPx <= 0f) {
            reset()
            return false
        }

        val elapsedFromPreviousStart = if (active) {
            max(0L, frameTimeMs - globalStartTimeMs)
        } else {
            0L
        }
        val duration = LyricPullSpec.DURATION_MS
        val delay = LyricPullSpec.itemDelayMs(distance, viewportHeightPx)

        visibleIndices.sorted().forEach { index ->
            val item = items.getOrPut(index) { ItemState() }
            if (index <= anchorIndex) {
                item.reset()
                return@forEach
            }

            when (item.phase) {
                LyricPullItemPhase.Initial,
                LyricPullItemPhase.Done -> {
                    item.phase = LyricPullItemPhase.WaitingForPull
                    item.baseOffsetPx = 0f
                    item.amplitudePx = distance
                    item.currentOffsetPx = 0f
                    item.durationMs = duration
                    item.startTimeMs = 0L
                }

                LyricPullItemPhase.WaitingForPull,
                LyricPullItemPhase.Pulling -> {
                    // Retarget from the current intermediate placement without jumping back
                    // to the old baseline before accepting the next ordinary line change.
                    item.baseOffsetPx = item.currentOffsetPx
                    item.amplitudePx = item.baseOffsetPx + distance
                    if (item.phase != LyricPullItemPhase.Pulling) {
                        item.durationMs = (
                            item.durationMs + duration - elapsedFromPreviousStart.toInt()
                            ).coerceAtLeast(1)
                    }
                    item.startTimeMs = frameTimeMs + delay * abs(index - anchorIndex).toLong()
                }
            }
        }

        this.anchorIndex = anchorIndex
        globalStartTimeMs = frameTimeMs
        globalPullDistancePx = distance
        globalDurationMs = duration
        perItemDelayMs = delay
        active = true
        return true
    }

    fun advance(frameTimeMs: Long, visibleIndices: Collection<Int>): LyricPullFrame {
        if (!active) return LyricPullFrame(emptyMap(), running = false)

        val offsets = linkedMapOf<Int, Float>()
        var anyActiveItem = false
        var previousVisibleOffset = 0f

        visibleIndices.sorted().forEach { index ->
            if (index <= anchorIndex) {
                items[index]?.reset()
                offsets[index] = 0f
                previousVisibleOffset = 0f
                return@forEach
            }

            val item = items.getOrPut(index) {
                ItemState(
                    phase = LyricPullItemPhase.WaitingForPull,
                    baseOffsetPx = 0f,
                    amplitudePx = globalPullDistancePx,
                    currentOffsetPx = 0f,
                    durationMs = globalDurationMs
                )
            }

            when (item.phase) {
                LyricPullItemPhase.Initial -> {
                    item.phase = LyricPullItemPhase.WaitingForPull
                    item.baseOffsetPx = 0f
                    item.amplitudePx = globalPullDistancePx
                    item.currentOffsetPx = 0f
                    item.durationMs = globalDurationMs
                }

                LyricPullItemPhase.Done -> Unit
                LyricPullItemPhase.WaitingForPull,
                LyricPullItemPhase.Pulling -> Unit
            }

            if (item.phase == LyricPullItemPhase.WaitingForPull) {
                val scheduledStart = globalStartTimeMs +
                    perItemDelayMs * abs(index - anchorIndex).toLong()
                if (frameTimeMs >= scheduledStart) {
                    item.phase = LyricPullItemPhase.Pulling
                    item.startTimeMs = scheduledStart
                }
            }

            val globalLinearProgress = if (globalDurationMs > 0) {
                ((frameTimeMs - globalStartTimeMs).toFloat() / globalDurationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            val globalInterpolation = LyricPullSpec.interpolate(
                progress = globalLinearProgress,
                movementPx = globalPullDistancePx
            )
            val globalContributionPx = globalPullDistancePx * globalInterpolation

            if (item.phase == LyricPullItemPhase.Pulling) {
                val linearProgress = if (item.durationMs > 0) {
                    ((frameTimeMs - item.startTimeMs).toFloat() / item.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val interpolation = LyricPullSpec.interpolate(
                    progress = linearProgress,
                    movementPx = globalPullDistancePx
                )
                item.currentOffsetPx = (
                    item.baseOffsetPx + globalContributionPx - item.amplitudePx * interpolation
                    ).coerceAtLeast(0f)
                if (linearProgress >= 1f) {
                    item.currentOffsetPx = 0f
                    item.baseOffsetPx = 0f
                    item.amplitudePx = 0f
                    item.phase = LyricPullItemPhase.Done
                }
            } else if (item.phase == LyricPullItemPhase.WaitingForPull) {
                item.currentOffsetPx = item.baseOffsetPx + globalContributionPx
            }

            // Clamp every row against the previous row's actual bottom. With LazyColumn's
            // baseline gap already reserved, the equivalent invariant is a non-decreasing trailing
            // compensation offset: a later row can never overtake the row before it.
            val clampedOffset = max(item.currentOffsetPx, previousVisibleOffset)
            offsets[index] = clampedOffset
            previousVisibleOffset = clampedOffset

            if (item.phase == LyricPullItemPhase.WaitingForPull ||
                item.phase == LyricPullItemPhase.Pulling
            ) {
                anyActiveItem = true
            }
        }

        if (!anyActiveItem) {
            active = false
            globalStartTimeMs = 0L
        }
        return LyricPullFrame(offsets, running = anyActiveItem)
    }

    internal fun phaseOf(index: Int): LyricPullItemPhase =
        items[index]?.phase ?: LyricPullItemPhase.Initial

    private fun ItemState.reset() {
        phase = LyricPullItemPhase.Initial
        baseOffsetPx = 0f
        amplitudePx = 0f
        currentOffsetPx = 0f
        startTimeMs = 0L
        durationMs = 0
    }
}
