package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.*
import org.junit.Test

class RetainedHeaderOwnershipTest {
    @Test fun parkedSourceRemainsVisibleUntilRetainedRoleIsReleased() {
        val owner = RetainedHeaderOwnership()
        owner.park("parent")
        assertTrue(owner.owns("parent"))
        owner.publish("parent")
        assertTrue(owner.owns("parent"))
        owner.publish(null)
        assertFalse(owner.owns("parent"))
    }

    @Test fun deeperNavigationNeverDrawsAllParkedAncestors() {
        val owner = RetainedHeaderOwnership()
        owner.park("parent")
        owner.park("child")
        assertFalse(owner.owns("parent"))
        assertTrue(owner.owns("child"))
        owner.publish("parent")
        assertTrue(owner.owns("parent"))
        assertFalse(owner.owns("child"))
        assertFalse(owner.owns(""))
    }
}
