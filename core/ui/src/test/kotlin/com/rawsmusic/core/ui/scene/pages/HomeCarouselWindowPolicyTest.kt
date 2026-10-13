package com.rawsmusic.core.ui.scene.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCarouselWindowPolicyTest {
    @Test fun continuousPreviewRetainsFiveNeighbourLanes() {
        assertEquals(13, carouselPreviewIndex(99, 9, 19))
        assertEquals(5, carouselPreviewIndex(-99, 9, 19))
    }
    @Test fun exhaustedTraversalNeverWrapsToOppositeEnd() {
        assertEquals(0, carouselPreviewIndex(-1, 0, 10))
        assertEquals(9, carouselPreviewIndex(10, 9, 10))
    }
    @Test fun previewWithinBufferKeepsExactOrder() {
        for (index in 5..13) assertEquals(index, carouselPreviewIndex(index, 9, 19))
    }
}
