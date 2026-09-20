package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineRenderDispatcherTest {
    @Test
    fun `ordinary line forwards steady frames only to moving highlight and lyric band`() {
        val clock = LyricDirectRenderClock()
        val geometry = LyricLineFloatUpGeometry(
            listOf(
                LyricTimedSlot(beginMs = 0f, endMs = 500f, timed = true),
                LyricTimedSlot(beginMs = 500f, endMs = 1_000f, timed = true),
            )
        )
        geometry.updatePlacement(0, leftPx = 0f, widthPx = 100f, lineIndex = 0)
        geometry.updatePlacement(1, leftPx = 120f, widthPx = 100f, lineIndex = 0)
        val dispatcher = LyricLineRenderDispatcher(clock, geometry)

        val segment0 = LyricTarget(0, 20f)
        val segment1 = LyricTarget(1, 20f)
        val hi0 = HighlightTarget(0L, 500L)
        val hi1 = HighlightTarget(500L, 1_000L)
        dispatcher.addFrameTarget(segment0)
        dispatcher.addFrameTarget(segment1)
        dispatcher.addHighlightTarget(hi0)
        dispatcher.addHighlightTarget(hi1)
        segment0.calls = 0; segment1.calls = 0; hi0.calls = 0; hi1.calls = 0

        dispatcher.onLyricFrame(250f)
        assertTrue(segment0.calls > 0)
        assertEquals(0, segment1.calls)
        assertEquals(1, hi0.calls)
        assertEquals(0, hi1.calls)

        // Another display frame inside the same first-word phase must not wake the future word.
        segment0.calls = 0; segment1.calls = 0; hi0.calls = 0; hi1.calls = 0
        dispatcher.onLyricFrame(260f)
        assertTrue(segment0.calls > 0)
        assertEquals(0, segment1.calls)
        assertEquals(1, hi0.calls)
        assertEquals(0, hi1.calls)
    }

    @Test
    fun `boundary frame settles old word and activates next without waking all nodes`() {
        val clock = LyricDirectRenderClock()
        val geometry = LyricLineFloatUpGeometry(
            listOf(
                LyricTimedSlot(beginMs = 0f, endMs = 500f, timed = true),
                LyricTimedSlot(beginMs = 500f, endMs = 1_000f, timed = true),
                LyricTimedSlot(beginMs = 1_000f, endMs = 1_500f, timed = true),
            )
        )
        repeat(3) { index -> geometry.updatePlacement(index, index * 120f, 100f, 0) }
        val dispatcher = LyricLineRenderDispatcher(clock, geometry)
        val targets = (0..2).map { LyricTarget(it, 20f) }
        targets.forEach(dispatcher::addFrameTarget)
        targets.forEach { it.calls = 0 }

        dispatcher.onLyricFrame(499f)
        val before = targets.map { it.calls }
        targets.forEach { it.calls = 0 }
        dispatcher.onLyricFrame(501f)
        val after = targets.map { it.calls }

        assertTrue(before.count { it > 0 } <= 2)
        assertTrue(after.count { it > 0 } <= 2)
        assertEquals(0, after[2])
    }

    private class LyricTarget(
        override val segmentIndex: Int,
        override val textScalePx: Float,
    ) : LyricLineFrameTarget {
        var calls: Int = 0
        override fun onLyricLineFrame(positionMs: Float, mode: LyricFrameSamplingMode) {
            calls++
        }
    }

    private class HighlightTarget(
        override val highlightBeginMs: Long,
        override val highlightEndMs: Long,
    ) : LyricLineHighlightFrameTarget {
        var calls: Int = 0
        override fun onHighlightLineFrame(positionMs: Float) {
            calls++
        }
    }
}
