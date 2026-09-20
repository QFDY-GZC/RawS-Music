package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricZoomEdgeFadeSpecTest {
    @Test
    fun sharedViewportCenterStaysOpaque() {
        assertEquals(1f, LyricZoomEdgeFadeSpec.alphaForCenter(400f, 800f, 80f), 0.0001f)
    }

    @Test
    fun exitingHolderGetsAnOffscreenExtensionAndFadesToZero() {
        val exit = LyricZoomEdgeFadeSpec.extendedCenterPx(
            endpointCenterPx = 820f,
            referenceCenterPx = 720f,
            viewportHeightPx = 800f,
            rowHeightPx = 80f,
        )
        assertTrue(exit >= 880f)
        assertEquals(0f, LyricZoomEdgeFadeSpec.alphaForCenter(exit, 800f, 80f), 0.0001f)
    }

    @Test
    fun enteringHolderFadesInAccordingToPhysicalPosition() {
        val a0 = LyricZoomEdgeFadeSpec.alphaForCenter(880f, 800f, 80f)
        val a1 = LyricZoomEdgeFadeSpec.alphaForCenter(840f, 800f, 80f)
        val a2 = LyricZoomEdgeFadeSpec.alphaForCenter(800f, 800f, 80f)
        assertTrue(a0 < a1)
        assertTrue(a1 < a2)
        assertEquals(1f, a2, 0.0001f)
    }
}
