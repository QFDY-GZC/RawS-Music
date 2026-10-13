package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.*
import org.junit.Test

class VirtualListMarqueeMotionTest {
    @Test fun oneSidedTransitionContinuesTextClockWithoutInternalLayoutInterpolation() {
        assertTrue(shouldAdvanceVirtualListText(false, true))
        assertTrue(shouldAdvanceVirtualListText(true, false))
        assertFalse(shouldAdvanceVirtualListText(false, false))
    }
    @Test fun zoomGeometryKeepsAdvancingOutwardWithoutAnotherInitialHold() {
        val motion = VirtualListMarqueeMotion()
        motion.tick(0); motion.tick(1500); motion.tick(2500)
        var previous = motion.offset
        for (step in 1..10) {
            val next = motion.update(2500L + step * 16, 100f + step,
                50f, true, true, true, preserveProgress = true)
            assertTrue("zoom frame $step stopped at $next", next > previous)
            previous = next
        }
    }

    @Test fun zoomGeometryKeepsReturnDirection() {
        val motion = VirtualListMarqueeMotion()
        motion.tick(0); motion.tick(1500); motion.tick(3500); motion.tick(6500); motion.tick(7500)
        var previous = motion.offset
        for (step in 1..10) {
            val next = motion.update(7500L + step * 16, 100f + step,
                50f, true, true, true, preserveProgress = true)
            assertTrue("return frame $step stopped at $next", next < previous)
            previous = next
        }
    }

    @Test fun zoomDoesNotExtendInitialWaitOrSuppressNewTextReset() {
        val motion = VirtualListMarqueeMotion()
        motion.tick(0)
        motion.update(1400, 120f, 50f, true, true, true, true)
        motion.update(1500, 130f, 50f, true, true, true, true)
        assertTrue(motion.update(1700, 140f, 50f, true, true, true, true) > 0f)
        motion.reset()
        assertEquals(0f, motion.update(1800, 140f, 50f, true, true, true, true), 0f)
    }

    private fun VirtualListMarqueeMotion.tick(time: Long, distance: Float = 100f,
        visible: Boolean = true, changed: Boolean = false, enabled: Boolean = true) =
        update(time, distance, 50f, enabled, visible, changed)

    @Test fun startsRelativeToBindingNotGlobalClock() {
        val motion = VirtualListMarqueeMotion()
        assertEquals(0f, motion.tick(100_000), 0f)
        assertEquals(0f, motion.tick(101_499), 0f)
        motion.tick(101_500)
        assertEquals(50f, motion.tick(102_500), 0.001f)
    }

    @Test fun resizingKeepsOffsetAndRestartsInitialHold() {
        val motion = VirtualListMarqueeMotion()
        motion.tick(0); motion.tick(1500); motion.tick(2500)
        assertEquals(50f, motion.tick(2600, 150f, changed = true), 0.001f)
        assertEquals(50f, motion.tick(4099, 150f), 0.001f)
        motion.tick(4100, 150f)
        assertTrue(motion.tick(4600, 150f) > 50f)
    }

    @Test fun shrinkingClampsOffsetAndNoOverflowResets() {
        val motion = VirtualListMarqueeMotion()
        motion.tick(0); motion.tick(1500); motion.tick(2500)
        assertEquals(20f, motion.tick(2600, 20f, changed = true), 0f)
        assertEquals(0f, motion.tick(2700, 10f), 0f)
    }

    @Test fun hiddenTextReturnsToOrigin() {
        val motion = VirtualListMarqueeMotion()
        motion.tick(0); motion.tick(1500); motion.tick(2500)
        assertEquals(0f, motion.tick(2600, visible = false), 0f)
        assertEquals(0f, motion.tick(4000), 0f)
        assertEquals(50f, motion.tick(5000), 0.001f)
    }

    @Test fun endpointHoldsAndReturnAreIndependent() {
        val motion = VirtualListMarqueeMotion()
        motion.tick(0); motion.tick(1500)
        assertEquals(100f, motion.tick(3500), 0f)
        assertEquals(100f, motion.tick(6499), 0f)
        motion.tick(6500)
        assertEquals(50f, motion.tick(7500), 0.001f)
        assertEquals(0f, motion.tick(8500), 0f)
    }

    @Test fun retainedHolderAdoptsPhaseWithoutSharingMutableState() {
        val source = VirtualListMarqueeMotion()
        source.tick(0); source.tick(1500); source.tick(2500)
        val target = VirtualListMarqueeMotion().apply { copyFrom(source) }
        source.reset()
        assertEquals(50f, target.tick(2500), 0.001f)
        assertEquals(0f, source.offset, 0f)
    }

    @Test fun disabledOrNewTextRestartsAtOrigin() {
        val motion = VirtualListMarqueeMotion()
        motion.tick(0); motion.tick(1500); motion.tick(2500)
        assertEquals(0f, motion.tick(2600, enabled = false), 0f)
        assertEquals(0f, motion.tick(2700), 0f)
        motion.reset()
        assertEquals(0f, motion.tick(100_000), 0f)
    }
}
