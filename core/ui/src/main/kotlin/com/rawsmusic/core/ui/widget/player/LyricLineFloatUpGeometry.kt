package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Stable

import kotlin.math.max

/**
 * Line-level lyric float-up geometry.
 *
 * One moving cursor is used for the complete rendered lyric line. Timed words must therefore
 * not restart the spatial cursor at x=0 independently. This holder intentionally is not Compose
 * Snapshot state: layout updates child rectangles, while the shared Choreographer render clock
 * invalidates only the active DrawModifierNode(s) once per vsync. No layout/recomposition is needed
 * for cursor motion.
 */

internal enum class LyricFrameSamplingMode {
    STATIC_DOWN,
    ANIMATED,
    STATIC_UP,
}

@Stable
internal class LyricLineFloatUpGeometry(
    timedSlots: List<LyricTimedSlot>,
) {
    private data class Placement(
        val leftPx: Float,
        val rightPx: Float,
        val lineIndex: Int,
    )

    private sealed interface CursorSample {
        data object BeforeAll : CursorSample
        data object AfterAll : CursorSample
        data class OnLine(
            val xPx: Float,
            val lineIndex: Int,
            val lineEndPx: Float,
        ) : CursorSample
    }

    private val slots = timedSlots.toList()
    private val placements = arrayOfNulls<Placement>(slots.size)
    private val wordIndices = slots.indices.filter { slots[it].timed }.toIntArray()
    private val lineEndPx = HashMap<Int, Float>()
    private var lineEndsDirty = true

    // Every timed child reads the same Float render position on one frame. Cache the resolved
    // line cursor so the O(words) timing search happens once per frame, not once per glyph.
    private var cachedPositionMs = Float.NaN
    private var cachedSample: CursorSample = CursorSample.BeforeAll

    fun updatePlacement(
        slotIndex: Int,
        leftPx: Float,
        widthPx: Float,
        lineIndex: Int,
    ) {
        if (slotIndex !in placements.indices) return
        val safeWidth = widthPx.coerceAtLeast(0f)
        val next = Placement(
            leftPx = leftPx,
            rightPx = leftPx + safeWidth,
            lineIndex = lineIndex.coerceAtLeast(0),
        )
        if (placements[slotIndex] != next) {
            placements[slotIndex] = next
            cachedPositionMs = Float.NaN
            lineEndsDirty = true
        }
    }

    /**
     * Coarse subscription gate for the frame clock. Only slots intersecting the moving
     * 3x-text-scale transition band need a per-vsync draw subscription. Slots fully behind/ahead of
     * the cursor are exact 1/0 plateaus and can stay on a static graphics layer.
     *
     * This is deliberately evaluated from the ordinary player callback in composition. Placement
     * geometry is plain state; after the first layout, later callback recompositions see the real
     * word rectangle. Missing placement is conservatively ANIMATED for one startup pass.
     */
    fun frameSamplingMode(
        slotIndex: Int,
        positionMs: Float,
        textScalePx: Float,
    ): LyricFrameSamplingMode {
        if (slotIndex !in slots.indices || !positionMs.isFinite() || !textScalePx.isFinite() || textScalePx <= 0f) {
            return LyricFrameSamplingMode.STATIC_DOWN
        }
        val placement = placements.getOrNull(slotIndex) ?: return LyricFrameSamplingMode.ANIMATED
        val halfWindowPx = textScalePx * LyricFloatUpSpec.WINDOW_TEXT_SCALES * 0.5f
        return when (val sample = sampleCursor(positionMs)) {
            CursorSample.BeforeAll -> LyricFrameSamplingMode.STATIC_DOWN
            CursorSample.AfterAll -> LyricFrameSamplingMode.STATIC_UP
            is CursorSample.OnLine -> when {
                placement.lineIndex < sample.lineIndex -> LyricFrameSamplingMode.STATIC_UP
                placement.lineIndex > sample.lineIndex -> LyricFrameSamplingMode.STATIC_DOWN
                placement.rightPx <= sample.xPx - halfWindowPx -> LyricFrameSamplingMode.STATIC_UP
                placement.leftPx >= sample.xPx + halfWindowPx -> LyricFrameSamplingMode.STATIC_DOWN
                else -> LyricFrameSamplingMode.ANIMATED
            }
        }
    }

    fun fractionForLocalCenter(
        slotIndex: Int,
        localCenterPx: Float,
        positionMs: Float,
        textScalePx: Float,
    ): Float {
        if (slotIndex !in slots.indices || !positionMs.isFinite()) return 0f
        val slotPlacement = placements.getOrNull(slotIndex) ?: return 0f
        return when (val sample = sampleCursor(positionMs)) {
            CursorSample.BeforeAll -> 0f
            CursorSample.AfterAll -> 1f
            is CursorSample.OnLine -> when {
                slotPlacement.lineIndex < sample.lineIndex -> 1f
                slotPlacement.lineIndex > sample.lineIndex -> 0f
                else -> LyricFloatUpSpec.spatialFraction(
                    centerPx = slotPlacement.leftPx + localCenterPx,
                    cursorPx = sample.xPx,
                    textScalePx = textScalePx,
                    lineEndPx = max(sample.lineEndPx, sample.xPx),
                )
            }
        }
    }

    private fun sampleCursor(positionMs: Float): CursorSample {
        if (positionMs == cachedPositionMs) return cachedSample
        cachedPositionMs = positionMs
        cachedSample = resolveCursor(positionMs)
        return cachedSample
    }

    private fun resolveCursor(positionMs: Float): CursorSample {
        if (wordIndices.isEmpty()) return CursorSample.BeforeAll
        val firstIndex = wordIndices.first()
        val lastIndex = wordIndices.last()
        val first = slots[firstIndex]
        val last = slots[lastIndex]
        if (positionMs < first.beginMs) return CursorSample.BeforeAll
        if (positionMs >= last.endMs) return CursorSample.AfterAll

        // Timed slots are already ordered by the lyric parser. Find the last word whose begin is at
        // or before the cursor with a binary search instead of rescanning the whole active line on
        // every 90/120 Hz frame. wordTimingBounds() clips ordinary overlaps at the next begin, so
        // this ordinal is sufficient to classify active/previous/next ownership.
        var low = 0
        var high = wordIndices.lastIndex
        var ownerOrdinal = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val timed = slots[wordIndices[mid]]
            if (timed.beginMs <= positionMs) {
                ownerOrdinal = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val ownerIndex = wordIndices.getOrNull(ownerOrdinal) ?: -1
        val ownerTimed = slots.getOrNull(ownerIndex)
        val activeWordIndex = if (ownerTimed != null && positionMs < ownerTimed.endMs) ownerIndex else -1
        val previousWordIndex = if (ownerIndex >= 0 && activeWordIndex < 0) ownerIndex else -1
        val nextOrdinal = when {
            activeWordIndex >= 0 -> ownerOrdinal + 1
            previousWordIndex >= 0 -> ownerOrdinal + 1
            else -> 0
        }
        val nextWordIndex = wordIndices.getOrNull(nextOrdinal) ?: -1

        val cursor = when {
            activeWordIndex >= 0 -> {
                val timed = slots[activeWordIndex]
                val placement = placements[activeWordIndex] ?: return CursorSample.BeforeAll
                val duration = (timed.endMs - timed.beginMs).coerceAtLeast(1f)
                val p = ((positionMs - timed.beginMs) / duration).coerceIn(0f, 1f)
                placement.lineIndex to (
                    placement.leftPx + (placement.rightPx - placement.leftPx) * p
                    )
            }
            previousWordIndex >= 0 && nextWordIndex >= 0 -> {
                val prev = placements[previousWordIndex] ?: return CursorSample.BeforeAll
                val next = placements[nextWordIndex] ?: return CursorSample.BeforeAll
                val prevTimed = slots[previousWordIndex]
                val nextTimed = slots[nextWordIndex]
                if (prev.lineIndex == next.lineIndex) {
                    val gapDuration = (nextTimed.beginMs - prevTimed.endMs).coerceAtLeast(1f)
                    val p = ((positionMs - prevTimed.endMs) / gapDuration).coerceIn(0f, 1f)
                    prev.lineIndex to (prev.rightPx + (next.leftPx - prev.rightPx) * p)
                } else {
                    // Never sweep horizontally across a physical wrap. Finish the previous visual
                    // row; the next row takes ownership exactly at its own begin timestamp.
                    prev.lineIndex to prev.rightPx
                }
            }
            previousWordIndex >= 0 -> {
                val prev = placements[previousWordIndex] ?: return CursorSample.BeforeAll
                prev.lineIndex to prev.rightPx
            }
            nextWordIndex >= 0 -> {
                val next = placements[nextWordIndex] ?: return CursorSample.BeforeAll
                next.lineIndex to next.leftPx
            }
            else -> return CursorSample.BeforeAll
        }

        val cursorLine = cursor.first
        val resolvedLineEndPx = lineEndFor(cursorLine).coerceAtLeast(cursor.second)
        return CursorSample.OnLine(
            xPx = cursor.second,
            lineIndex = cursorLine,
            lineEndPx = resolvedLineEndPx,
        )
    }

    private fun lineEndFor(lineIndex: Int): Float {
        if (lineEndsDirty) {
            lineEndPx.clear()
            for (placement in placements) {
                if (placement != null) {
                    val previous = lineEndPx[placement.lineIndex] ?: 0f
                    if (placement.rightPx > previous) lineEndPx[placement.lineIndex] = placement.rightPx
                }
            }
            lineEndsDirty = false
        }
        return lineEndPx[lineIndex] ?: 0f
    }
}

internal data class LyricTimedSlot(
    val beginMs: Float = 0f,
    val endMs: Float = 0f,
    val timed: Boolean = false,
)
