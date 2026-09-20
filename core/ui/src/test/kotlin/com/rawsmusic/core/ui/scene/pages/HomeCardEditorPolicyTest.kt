package com.rawsmusic.core.ui.scene.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCardEditorPolicyTest {
    @Test
    fun twoColumnDropMapsHorizontalAndVerticalMotionToVisibleIndices() {
        assertEquals(0, resolveHomeCardDropDelta(20f, 30f, 200, 260))
        assertEquals(1, resolveHomeCardDropDelta(150f, 0f, 200, 260))
        assertEquals(-1, resolveHomeCardDropDelta(-150f, 0f, 200, 260))
        assertEquals(2, resolveHomeCardDropDelta(0f, 200f, 200, 260))
        assertEquals(-2, resolveHomeCardDropDelta(0f, -200f, 200, 260))
        assertEquals(3, resolveHomeCardDropDelta(150f, 220f, 200, 260))
        assertEquals(-3, resolveHomeCardDropDelta(-150f, -220f, 200, 260))
    }

    @Test
    fun artworkSamplingIsStableAcrossReorder() {
        val before = stableHomeCardArtworkIndex("albums", "library", 97)
        val after = stableHomeCardArtworkIndex("albums", "library", 97)
        assertEquals(before, after)
        assertEquals(true, before in 0 until 97)
    }
}
