package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDragOwnershipPolicyTest {
    @Test fun establishedVerticalOwnerEndsParentSequenceBeforeReversal() {
        assertTrue(shouldYieldSceneDragToChild(true, false, dx = 4f, dy = 50f, touchSlop = 28f))
    }
    @Test fun parentRetainsItsOwnConsumedDrag() {
        assertFalse(shouldYieldSceneDragToChild(true, true, dx = 4f, dy = 50f, touchSlop = 28f))
    }
    @Test fun unclaimedMoveCanStartSceneDrag() {
        assertFalse(shouldYieldSceneDragToChild(consumed = false, dragging = false))
    }
    @Test fun ordinaryConsumedMoveDoesNotDisableHorizontalPageNavigation() {
        assertFalse(shouldYieldSceneDragToChild(consumed = true, dragging = false))
    }
    @Test fun consumedHorizontalMoveCanStillNavigatePages() {
        assertFalse(shouldYieldSceneDragToChild(true, false, dx = 60f, dy = 4f, touchSlop = 28f))
    }
    @Test fun consumedSubSlopMoveDoesNotRejectSequence() {
        assertFalse(shouldYieldSceneDragToChild(true, false, dx = 0f, dy = 10f, touchSlop = 28f))
    }
}
