package com.rawsmusic.core.ui.widget.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticArtworkSourcePolicyTest {
    @Test fun paletteThumbnailCannotSupplyMaximumDetail() {
        assertFalse(StaticArtworkSourcePolicy.canReuse(96, 96))
        assertFalse(StaticArtworkSourcePolicy.canReuse(600, 600))
    }

    @Test fun playbackArtworkCanBeSharedWithoutAnotherDecode() {
        assertTrue(StaticArtworkSourcePolicy.canReuse(1200, 1200))
        assertTrue(StaticArtworkSourcePolicy.canReuse(1600, 1600))
    }

    @Test fun narrowArtworkIsJudgedByItsLongEdge() {
        assertTrue(StaticArtworkSourcePolicy.canReuse(1200, 800))
        assertFalse(StaticArtworkSourcePolicy.canReuse(0, 1200))
    }
}
