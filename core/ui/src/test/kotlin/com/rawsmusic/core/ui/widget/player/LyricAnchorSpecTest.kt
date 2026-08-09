package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricAnchorSpecTest {
    @Test
    fun followAnchorUsesViewportQuarterIncludingNegativeStartPadding() {
        assertEquals(
            125f,
            LyricAnchorSpec.targetOffsetPx(
                viewportStartOffset = -100,
                viewportEndOffset = 800
            ),
            0.0001f
        )
    }

    @Test
    fun trailingSpaceAllowsFinalLineToReachAnchor() {
        assertEquals(
            750f,
            LyricAnchorSpec.requiredTrailingPaddingPx(1_000f),
            0.0001f
        )
        assertEquals(0f, LyricAnchorSpec.requiredTrailingPaddingPx(0f), 0.0001f)
    }
}
