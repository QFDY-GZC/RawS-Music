package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.*
import org.junit.Test

class VirtualListRevealRequestOwnerTest {
    @Test fun geometryChangesCannotReplayAppliedScrollWithoutArtwork() {
        val owner = VirtualListRevealRequestOwner()
        owner.bind(6)
        assertEquals(420, owner.scrollForLayout(0, 420))
        owner.markScrollApplied(6)
        owner.bind(6)
        assertEquals(-1, owner.pendingIndex)
        assertEquals(1200, owner.scrollForLayout(1200, 420))
        assertTrue(owner.targetReportPending)
    }

    @Test fun firstLayoutUsesRequestedPositionInsteadOfTop() {
        val owner = VirtualListRevealRequestOwner()
        owner.bind(30)
        assertEquals(1800, owner.scrollForLayout(0, 1800))
    }

    @Test fun unavailableGeometryKeepsRequestPending() {
        val owner = VirtualListRevealRequestOwner()
        owner.bind(30)
        assertEquals(900, owner.scrollForLayout(900, null))
        assertEquals(30, owner.pendingIndex)
        assertFalse(owner.targetReportPending)
    }

    @Test fun coverReportDoesNotRearmPositioning() {
        val owner = VirtualListRevealRequestOwner()
        owner.bind(6)
        owner.markScrollApplied(6)
        owner.markTargetReported(6)
        assertFalse(owner.targetReportPending)
        assertEquals(1500, owner.scrollForLayout(1500, 420))
    }

    @Test fun clearingThenRequestingSameIndexStartsNewTransaction() {
        val owner = VirtualListRevealRequestOwner()
        owner.bind(6)
        owner.markScrollApplied(6)
        owner.bind(-1)
        assertEquals(-1, owner.pendingIndex)
        owner.bind(6)
        assertEquals(6, owner.pendingIndex)
        assertFalse(owner.targetReportPending)
    }

    @Test fun staleCompletionCannotConsumeNewRequest() {
        val owner = VirtualListRevealRequestOwner()
        owner.bind(6)
        owner.bind(12)
        owner.markScrollApplied(6)
        owner.markTargetReported(6)
        assertEquals(12, owner.pendingIndex)
        assertFalse(owner.targetReportPending)
    }
}
