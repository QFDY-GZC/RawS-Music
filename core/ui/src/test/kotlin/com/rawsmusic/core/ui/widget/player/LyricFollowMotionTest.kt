package com.rawsmusic.core.ui.widget.player
import org.junit.Assert.*
import org.junit.Test
class LyricFollowMotionTest {
    @Test fun quickLinesRetargetTheSameSpringInsteadOfSwitchingItsFrequency() {
        assertEquals(lyricFollowMotion(250L), lyricFollowMotion(4000L))
        assertEquals(100f, lyricFollowMotion(250L).stiffness, .001f)
    }
    @Test fun interludesKeepTheVerifiedPositionSpring() {
        assertEquals(lyricFollowMotion(1000L), lyricFollowMotion(1000L, true))
    }
}
