package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGestureBindingPolicyTest {
    @Test fun outgoingDecodeCannotStartReverseTransitionBeforeMetadataBinding() {
        assertTrue(shouldParkOutgoingGestureCallback("A", "B", "A", "A", "B", true))
    }
    @Test fun currentDecodeAndAcknowledgedBindingAreNotParked() {
        assertFalse(shouldParkOutgoingGestureCallback("B", "B", "A", "A", "B", true))
        assertFalse(shouldParkOutgoingGestureCallback("A", "B", "B", "A", "B", true))
        assertFalse(shouldParkOutgoingGestureCallback("A", "B", "A", "A", "B", false))
    }
    @Test fun oldSnapshotCannotRewindPromotedGesture() {
        assertTrue(ignoreStalePromotedGestureBinding("A", "B", "A", "B", true, false))
    }
    @Test fun newTrackAcknowledgementAndThirdTrackRemainAllowed() {
        assertFalse(ignoreStalePromotedGestureBinding("B", "B", "A", "B", true, false))
        assertFalse(ignoreStalePromotedGestureBinding("C", "B", "A", "B", true, false))
    }
    @Test fun intentionalReturnAndExpiredLeaseRemainAllowed() {
        assertFalse(ignoreStalePromotedGestureBinding("A", "B", "A", "B", true, true))
        assertFalse(ignoreStalePromotedGestureBinding("A", "B", "A", "B", false, false))
        assertFalse(ignoreStalePromotedGestureBinding("A", "A", "A", "B", true, false))
    }
}
