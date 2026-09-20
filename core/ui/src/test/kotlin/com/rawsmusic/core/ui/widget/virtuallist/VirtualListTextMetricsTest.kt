package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualListTextMetricsTest {
    @Test
    fun descenderOutsideSceneRectRemainsVisible() {
        val clip = resolveVirtualListTextVerticalClip(
            rectTop = 10f,
            rectBottom = 32f,
            canvasHeight = 80f,
            baseline = 28.5f,
            fontTop = -23f,
            fontBottom = 5.5f,
        )
        assertEquals(4.5f, clip.top)
        assertEquals(35f, clip.bottom)
        assertTrue(clip.bottom > 32f)
    }

    @Test
    fun metricsAlreadyInsideRectDoNotShrinkLane() {
        val clip = resolveVirtualListTextVerticalClip(
            rectTop = 10f,
            rectBottom = 34f,
            canvasHeight = 80f,
            baseline = 28f,
            fontTop = -16f,
            fontBottom = 4f,
        )
        assertEquals(10f, clip.top)
        assertEquals(34f, clip.bottom)
    }

    @Test
    fun expandedGlyphBoxNeverEscapesRowCanvas() {
        val clip = resolveVirtualListTextVerticalClip(
            rectTop = 54f,
            rectBottom = 70f,
            canvasHeight = 72f,
            baseline = 68f,
            fontTop = -18f,
            fontBottom = 6f,
        )
        assertEquals(49f, clip.top)
        assertEquals(72f, clip.bottom)
    }
}
