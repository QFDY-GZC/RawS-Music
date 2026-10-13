package com.rawsmusic.core.ui.widget.player
import org.junit.Assert.*
import org.junit.Test
class LyricPullSpecTest {
    @Test fun delaysUseAnimationRankWithTheFirstTwoTogether() {
        assertEquals(0L, LyricAmMotionSpec.delayMs(0))
        assertEquals(0L, LyricAmMotionSpec.delayMs(1))
        assertEquals(50L, LyricAmMotionSpec.delayMs(2))
        assertEquals(100L, LyricAmMotionSpec.delayMs(3))
    }
    @Test fun staticLayoutDefaultsMatchTheArchive() {
        assertEquals(.98f, LyricAmMotionSpec.INACTIVE_SCALE, .001f)
        assertEquals(25, LyricAmMotionSpec.LINE_SPACING_SP)
        assertEquals(39f, LyricAmMotionSpec.PARAGRAPH_SPACING_DP, .001f)
    }
}
