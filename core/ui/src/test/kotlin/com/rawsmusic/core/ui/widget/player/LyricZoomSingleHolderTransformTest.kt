package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricZoomSingleHolderTransformTest {
    @Test
    fun targetOnlyHolderKeepsFullVisualScaleWhileLaneOpens() {
        val target = LyricZoomRowBounds(200f, 300f)
        val current = LyricZoomRowBounds(240f, 260f)
        val transform = LyricZoomSpec.singleHolderTransform(
            sourceBounds = target,
            targetBounds = target,
            currentBounds = current,
            viewportTopPx = 0f,
            viewportBottomPx = 600f,
            progress = 0.2f,
            ownership = LyricZoomRowOwnership.TARGET_ONLY,
        )
        assertEquals(1f, transform.scale, 0.0001f)
        assertEquals(0.2f, transform.alpha, 0.0001f)
    }
}
