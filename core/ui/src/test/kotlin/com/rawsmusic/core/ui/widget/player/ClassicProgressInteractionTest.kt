package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class ClassicProgressInteractionTest {
    @Test fun releaseRestoresPlayingHeadEvenWhileFlingIsStillRunning() {
        val state = ClassicVisualInteraction()
        state.press()
        state.drag()
        state.release(true)
        assertFalse(state.pressed)
        assertFalse(state.dragging)
        assertTrue(state.flinging)
        assertEquals(ClassicConeMode.Playing, classicConeMode(true, state.dragging))
        state.finish()
        assertFalse(state.flinging)
    }

    @Test fun cancelledPressCannotLeaveHeadInDraggingMode() {
        val state = ClassicVisualInteraction()
        state.press()
        state.drag()
        state.finish()
        assertFalse(state.pressed)
        assertFalse(state.dragging)
        assertFalse(state.flinging)
        assertEquals(ClassicConeMode.Off, classicConeMode(false, state.dragging))
    }

    @Test fun stationaryHoldClaimsSeekWithoutJumpingToFinger() {
        val press = ClassicProgressPress(true, 1000L, 500L)
        assertFalse(press.shouldClaim(1499L, PlayerTimelineGestureAxis.Undecided, false))
        assertTrue(press.shouldClaim(1500L, PlayerTimelineGestureAxis.Undecided, false))
        assertFalse(press.shouldClaim(1500L, PlayerTimelineGestureAxis.VerticalScene, false))
        assertFalse(press.shouldClaim(1500L, PlayerTimelineGestureAxis.Undecided, true))
    }

    @Test fun holdOutsideTrackCannotStealPageGesture() {
        assertFalse(ClassicProgressPress(false, 0L, 500L)
            .shouldClaim(1000L, PlayerTimelineGestureAxis.Undecided, false))
    }

    @Test fun transientArtworkFailureKeepsLastValidColorAndRetries() {
        val retained = ClassicProgressColorOwner(0xffcccccc.toInt())
        retained.bind("a")
        retained.accept("a", 0xffee7777.toInt())
        retained.bind("b")
        retained.accept("b", null)
        assertEquals(0xffee7777.toInt(), retained.color)
        assertFalse(retained.ready)
        retained.accept("a", 0xff00ff00.toInt())
        assertEquals(0xffee7777.toInt(), retained.color)
        retained.accept("b", 0xff7799ee.toInt())
        assertEquals(0xff7799ee.toInt(), retained.color)
        assertTrue(retained.ready)
    }

    @Test fun glowHasItsOwnDeclaredRasterBoundsDuringPartialExpansion() {
        val geometry = classicGlowGeometry(900f, 84f, 12f)
        assertTrue(geometry.width >= 1500f)
        assertTrue(geometry.height >= 450f)
        assertEquals(300f, geometry.trackInsetX, 0f)
        assertTrue(geometry.centerY - 225f >= 0f)
        assertTrue(geometry.centerY + 225f <= geometry.height)
    }

    @Test fun rubberReleaseCrossesRestThenSettles() {
        val spring = ClassicProgressSpring(18f)
        spring.target = 0f
        var crossedRest = false
        repeat(100) {
            spring.advance(1f / 60f, 0.42f, bounce = 0.65f)
            if (spring.value < 0f) crossedRest = true
        }
        assertTrue(crossedRest)
        repeat(200) { spring.advance(1f / 60f, 0.42f, bounce = 0.65f) }
        assertEquals(0f, spring.value, 0.001f)
    }
}
