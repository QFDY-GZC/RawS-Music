package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListRetainedMarqueeExtentTest {
    @Test
    fun nonMarqueeKeepsHolderExtent() {
        assertEquals(
            320,
            virtualListRetainedTextNodeWidth(
                holderWidth = 320,
                rectLeft = 96,
                leftInsetPx = 0f,
                measuredWidthPx = 1200f,
                overflowPx = 900f,
                marqueeAllowed = false,
            )
        )
    }

    @Test
    fun marqueeRetainsCompleteTrailingGlyphExtent() {
        val width = virtualListRetainedTextNodeWidth(
            holderWidth = 320,
            rectLeft = 96,
            leftInsetPx = 16f,
            measuredWidthPx = 612.4f,
            overflowPx = 420f,
            marqueeAllowed = true,
        )
        assertTrue(width >= 96 + 16 + 613)
    }

    @Test
    fun noOverflowDoesNotGrowDisplayList() {
        assertEquals(
            480,
            virtualListRetainedTextNodeWidth(
                holderWidth = 480,
                rectLeft = 120,
                leftInsetPx = 0f,
                measuredWidthPx = 180f,
                overflowPx = 0f,
                marqueeAllowed = true,
            )
        )
    }
}
