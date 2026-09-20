package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineFloatUpGeometryTest {
    @Test
    fun cursorDoesNotRestartAtWordBoundary() {
        val geometry = LyricLineFloatUpGeometry(
            listOf(
                LyricTimedSlot(0f, 500f, true),
                LyricTimedSlot(500f, 1000f, true),
            )
        )
        geometry.updatePlacement(0, 0f, 50f, 0)
        geometry.updatePlacement(1, 50f, 50f, 0)

        val before = geometry.fractionForLocalCenter(1, 5f, 499f, 28f)
        val after = geometry.fractionForLocalCenter(1, 5f, 501f, 28f)
        assertTrue(abs(before - after) < 0.03f)
    }

    @Test
    fun completedLineIsFullyLifted() {
        val geometry = LyricLineFloatUpGeometry(
            listOf(LyricTimedSlot(0f, 500f, true))
        )
        geometry.updatePlacement(0, 0f, 60f, 0)
        assertEquals(1f, geometry.fractionForLocalCenter(0, 30f, 600f, 28f), 0.0001f)
    }
    @Test
    fun `only slots intersecting moving lyric band require frame sampling`() {
        val geometry = LyricLineFloatUpGeometry(
            listOf(
                LyricTimedSlot(0f, 500f, true),
                LyricTimedSlot(500f, 1000f, true),
                LyricTimedSlot(1000f, 1500f, true),
                LyricTimedSlot(1500f, 2000f, true),
            )
        )
        geometry.updatePlacement(0, 0f, 60f, 0)
        geometry.updatePlacement(1, 60f, 60f, 0)
        geometry.updatePlacement(2, 120f, 60f, 0)
        geometry.updatePlacement(3, 180f, 60f, 0)

        // Cursor is in word 2. The far-behind and far-ahead plateaus are exact and must not
        // subscribe to the render clock; only geometry overlapping the 3x text-scale band stays hot.
        val modes = (0..3).map { index -> geometry.frameSamplingMode(index, 950f, 28f) }
        assertEquals(LyricFrameSamplingMode.STATIC_UP, modes[0])
        assertEquals(LyricFrameSamplingMode.ANIMATED, modes[1])
        assertEquals(LyricFrameSamplingMode.ANIMATED, modes[2])
        assertEquals(LyricFrameSamplingMode.STATIC_DOWN, modes[3])
    }

    @Test
    fun `before and after line use static plateaus`() {
        val geometry = LyricLineFloatUpGeometry(
            listOf(LyricTimedSlot(100f, 500f, true))
        )
        geometry.updatePlacement(0, 0f, 60f, 0)
        assertEquals(
            LyricFrameSamplingMode.STATIC_DOWN,
            geometry.frameSamplingMode(0, 0f, 28f),
        )
        assertEquals(
            LyricFrameSamplingMode.STATIC_UP,
            geometry.frameSamplingMode(0, 600f, 28f),
        )
    }


    @Test
    fun `large timed line keeps cursor continuous across binary-search ownership changes`() {
        val slots = (0 until 128).map { index ->
            LyricTimedSlot(index * 100f, index * 100f + 100f, true)
        }
        val geometry = LyricLineFloatUpGeometry(slots)
        slots.indices.forEach { index ->
            geometry.updatePlacement(index, index * 20f, 20f, 0)
        }

        val before = geometry.fractionForLocalCenter(64, 10f, 6399f, 28f)
        val after = geometry.fractionForLocalCenter(64, 10f, 6401f, 28f)
        assertTrue(abs(before - after) < 0.04f)
        assertEquals(
            LyricFrameSamplingMode.ANIMATED,
            geometry.frameSamplingMode(64, 6401f, 28f),
        )
    }
}
