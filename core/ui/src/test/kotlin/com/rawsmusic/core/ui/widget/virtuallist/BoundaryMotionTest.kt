package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundaryMotionTest {
    @Test
    fun `baseline implementation base overshoot style resolves to zero`() {
        assertEquals(0f, Reference_BASE_MAX_OVERSHOOT_PX, 0.0001f)
        assertEquals(0f, ReferenceResolvedMaxStretchPx(), 0.0001f)
        assertEquals(32f, ReferenceResolvedMaxStretchPx(32f), 0.0001f)
        assertEquals(1f, ReferenceVerticalStretchScale(100f, 0f, 1000f), 0.0001f)
    }

    @Test
    fun `edge rebound remaining displacement follows reverse cubic`() {
        assertEquals(1f, ReferenceBoundaryRemainingFraction(0f), 0.0001f)
        assertEquals(0.125f, ReferenceBoundaryRemainingFraction(0.5f), 0.0001f)
        assertEquals(0f, ReferenceBoundaryRemainingFraction(1f), 0.0001f)
    }

    @Test
    fun `r1 duration grows beyond minimum`() {
        assertEquals(350, ReferenceBoundaryReboundDurationMs(0.2f))
        assertEquals(500, ReferenceBoundaryReboundDurationMs(1f))
        assertEquals(1000, ReferenceBoundaryReboundDurationMs(2f))
    }

    @Test
    fun `resistance is bounded and monotonic`() {
        val quarter = ReferenceNormalizedBoundaryResistance(0.25f)
        val full = ReferenceNormalizedBoundaryResistance(1f)
        assertTrue(quarter > 0f)
        assertTrue(quarter < full)
        assertEquals(1f, full, 0.0001f)
    }

    @Test
    fun `shared item resistance matches recovered point three inverse pair`() {
        // SharedContentGesture.mo2958(0.09) = (0.09 / 0.3)^2 / 0.3 = 0.3.
        assertEquals(0.09f, ReferenceSharedItemBoundaryResistance(0.3f), 0.0001f)
    }

    @Test
    fun `header item direct swipe travels one quarter of logical width`() {
        assertEquals(250f, ReferenceHeaderItemHorizontalTranslationPx(1f, 1000f), 0.0001f)
        assertEquals(100f, ReferenceHeaderItemHorizontalTranslationPx(0.4f, 1000f), 0.0001f)
    }
}
