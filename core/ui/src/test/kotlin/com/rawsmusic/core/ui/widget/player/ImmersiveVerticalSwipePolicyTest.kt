package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveVerticalSwipePolicyTest {
    @Test fun clickableConsumedDownDoesNotCancelVerticalRecognition() {
        assertFalse(immersiveVerticalChildOwnsMotion(true, 0f, 0f))
    }
    @Test fun consumedSeekMovementStillBelongsToChild() {
        assertTrue(immersiveVerticalChildOwnsMotion(true, 30f, 1f))
        assertFalse(immersiveVerticalChildOwnsMotion(false, 0f, 40f))
    }
    @Test fun upwardDragFromArtworkSelectsNext() {
        assertEquals(ImmersiveVerticalAction.Next, immersiveVerticalAction(true, true, false, true, -50f))
    }
    @Test fun downwardDragFromArtworkPreservesDismiss() {
        assertEquals(ImmersiveVerticalAction.Dismiss, immersiveVerticalAction(true, true, false, true, 50f))
    }
    @Test fun downwardDragOutsideArtworkSelectsPrevious() {
        assertEquals(ImmersiveVerticalAction.Previous, immersiveVerticalAction(true, true, false, false, 50f))
    }
    @Test fun otherPagesAndDisabledAndBlockedDoNotCapture() {
        assertEquals(ImmersiveVerticalAction.None, immersiveVerticalAction(false, true, false, false, -50f))
        assertEquals(ImmersiveVerticalAction.None, immersiveVerticalAction(true, false, false, false, -50f))
        assertEquals(ImmersiveVerticalAction.None, immersiveVerticalAction(true, true, true, false, -50f))
        assertEquals(ImmersiveVerticalAction.None, immersiveVerticalAction(true, true, false, false, 0f))
    }
    @Test fun crossingOriginCanExposeBothNeighboursWithoutLiftingFinger() {
        assertEquals(-0.5f, immersiveVerticalSignedProgress(-400f, 800f), 0f)
        assertEquals(0f, immersiveVerticalSignedProgress(0f, 800f), 0f)
        assertEquals(0.05f, immersiveVerticalSignedProgress(40f, 800f), 0f)
        assertEquals(-0.05f, immersiveVerticalSignedProgress(-40f, 800f), 0f)
        assertEquals(1f, immersiveVerticalSignedProgress(1200f, 800f), 0f)
        assertEquals(-1f, immersiveVerticalSignedProgress(-1200f, 800f), 0f)
    }
}
