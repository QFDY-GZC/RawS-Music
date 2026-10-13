package com.rawsmusic.core.ui.widget.player

import com.rawsmusic.core.ui.widget.virtuallist.VirtualListMarqueeMotion
import org.junit.Assert.*
import org.junit.Test

class PlayerTitleTextPolicyTest {
    @Test fun visibleTitleKeepsScrollingThroughTrackTransition() {
        val motion = VirtualListMarqueeMotion()
        fun frame(time: Long, transitioning: Boolean) = motion.update(
            time, 100f, 50f, true, playerTitleMarqueeVisible(true, transitioning), false,
        )
        frame(0, false)
        frame(1500, false)
        val before = frame(2500, false)
        val during = frame(2516, true)
        assertTrue("offset must not reset at transition start", during > before)
        assertTrue(frame(2532, false) > during)
    }

    @Test fun parkedTitleDoesNotRunItsMarquee() {
        assertFalse(playerTitleMarqueeVisible(false, false))
        assertFalse(playerTitleMarqueeVisible(false, true))
    }

    @Test fun newTitleStartsAtOriginEvenWhenSharedClockIsFarIntoItsCycle() {
        val old = VirtualListMarqueeMotion()
        old.update(100000, 100f, 50f, true, true, false)
        old.update(101500, 100f, 50f, true, true, false)
        assertEquals(50f, old.update(102500, 100f, 50f, true, true, false), 0.001f)
        val incoming = VirtualListMarqueeMotion()
        assertEquals(0f, incoming.update(102500, 100f, 50f, true, true, false), 0f)
        assertEquals(0f, incoming.update(103999, 100f, 50f, true, true, false), 0f)
    }

    @Test fun largeFontUsesAStableFontBoxInsteadOfClippingSpecificTitles() {
        assertEquals(36f, playerTitleLineHeightPx(28f, -27f, 7f), 0f)
        assertEquals(28f, playerTitleLineHeightPx(28f, -18f, 5f), 0f)
    }
}
