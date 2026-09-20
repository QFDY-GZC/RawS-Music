package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualListOuterDualLayoutResolverTest {
    @Test
    fun complementaryRoleAlphaMapsToCurrentLayoutWeight() {
        assertEquals(0f, virtualListOuterCurrentLayoutFractionFromAlphas(0f, 1f), 0f)
        assertEquals(0.25f, virtualListOuterCurrentLayoutFractionFromAlphas(0.25f, 0.75f), 0f)
        assertEquals(0.5f, virtualListOuterCurrentLayoutFractionFromAlphas(0.5f, 0.5f), 0f)
        assertEquals(1f, virtualListOuterCurrentLayoutFractionFromAlphas(1f, 0f), 0f)
    }

    @Test
    fun fractionNormalizesNonIdealAlphaPair() {
        assertEquals(0.75f, virtualListOuterCurrentLayoutFractionFromAlphas(0.6f, 0.2f), 0.0001f)
        assertEquals(1f, virtualListOuterCurrentLayoutFractionFromAlphas(0f, 0f), 0f)
    }
}
