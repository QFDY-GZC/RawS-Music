package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTimelineGesturePolicyTest {
    @Test
    fun verticalDragFromTimelineBelongsToScene() {
        assertEquals(
            PlayerTimelineGestureAxis.VerticalScene,
            resolvePlayerTimelineGestureAxis(dx = 5f, dy = -32f, touchSlop = 8f),
        )
    }

    @Test
    fun horizontalDragBelongsToSeek() {
        assertEquals(
            PlayerTimelineGestureAxis.HorizontalSeek,
            resolvePlayerTimelineGestureAxis(dx = 32f, dy = -5f, touchSlop = 8f),
        )
    }

    @Test
    fun ambiguousDiagonalWaitsForOwnership() {
        assertEquals(
            PlayerTimelineGestureAxis.Undecided,
            resolvePlayerTimelineGestureAxis(dx = 18f, dy = -17f, touchSlop = 8f),
        )
    }

    @Test
    fun classicSeekDownIsLimitedToVisibleTrackBand() {
        // 28px test container, track visually centered at 18px, 12px total touch band.
        assertEquals(true, isPlayerTimelineSeekDownAllowed(18f, 28f, 4f, 6f))
        assertEquals(true, isPlayerTimelineSeekDownAllowed(23.9f, 28f, 4f, 6f))
        assertEquals(false, isPlayerTimelineSeekDownAllowed(25f, 28f, 4f, 6f))
        assertEquals(false, isPlayerTimelineSeekDownAllowed(4f, 28f, 4f, 6f))
    }
    @Test
    fun secondsTimelineTapUsesPhysicalPlayheadAsZeroDelta() {
        val current = 120f
        val width = 300f
        val step = 10f
        assertEquals(115f, resolveSecondTimelineTapSecond(current, 100f, width, step, 300f), 0.001f)
        assertEquals(120f, resolveSecondTimelineTapSecond(current, 150f, width, step, 300f), 0.001f)
        assertEquals(125f, resolveSecondTimelineTapSecond(current, 200f, width, step, 300f), 0.001f)
    }

    @Test
    fun secondsTimelineTapClampsAtTrackEdges() {
        assertEquals(0f, resolveSecondTimelineTapSecond(2f, 0f, 300f, 10f, 300f), 0.001f)
        assertEquals(300f, resolveSecondTimelineTapSecond(298f, 300f, 300f, 10f, 300f), 0.001f)
    }

}
