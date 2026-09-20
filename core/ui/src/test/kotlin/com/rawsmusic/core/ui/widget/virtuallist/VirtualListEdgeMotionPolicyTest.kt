package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListEdgeMotionPolicyTest {
    @Test
    fun `visual overshoot is signed logarithmic and capped at VirtualList max`() {
        assertEquals(0f, virtualListVisualOvershootPx(0f, 44f), 0.0001f)
        val small = virtualListVisualOvershootPx(22f, 44f)
        val large = virtualListVisualOvershootPx(66f, 44f)
        assertTrue(small in 0f..large)
        assertEquals(44f, large, 0.75f)
        assertEquals(-large, virtualListVisualOvershootPx(-66f, 44f), 0.0001f)
    }

    @Test
    fun `holder deformation expands top edge and compresses bottom edge`() {
        val fraction = 1f
        assertEquals(1.04f, virtualListHolderScaleY(44f, 44f, fraction), 0.0001f)
        assertEquals(0.96f, virtualListHolderScaleY(-44f, 44f, fraction), 0.0001f)
        assertEquals(30f, virtualListHolderTranslationY(44f, 44f, 30f, fraction), 0.0001f)
        assertEquals(-30f, virtualListHolderTranslationY(-44f, 44f, 30f, fraction), 0.0001f)
    }

    @Test
    fun `holder fraction grows toward lower viewport edge`() {
        val top = virtualListHolderEdgeFraction(0f, 100f, 1000)
        val bottom = virtualListHolderEdgeFraction(800f, 1000f, 1000)
        assertTrue(top < bottom)
        assertEquals(1f, bottom, 0.0001f)
    }

    @Test
    fun `rebound uses recovered five hundred millisecond floor`() {
        assertEquals(500, virtualListEdgeReboundDurationMs(44f))
        assertEquals(1000, virtualListEdgeReboundDurationMs(1500f))
    }
}

