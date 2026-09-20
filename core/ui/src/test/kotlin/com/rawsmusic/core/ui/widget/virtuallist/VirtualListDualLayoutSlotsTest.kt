package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test

class VirtualListDualLayoutSlotsTest {
    @Test fun unchangedPublicationReusesSnapshotButRoleRemovalInvalidatesIt() {
        val slots = VirtualListDualLayoutSlots<String, Int>()
        var frame = slots.beginFrame()
        slots.publish(frame, "song", VirtualListPhysicalLayoutRole.CURRENT, 10)
        slots.endFrame(frame)
        val first = slots.snapshots()
        frame = slots.beginFrame()
        slots.publish(frame, "song", VirtualListPhysicalLayoutRole.CURRENT, 10)
        slots.endFrame(frame)
        assertSame(first, slots.snapshots())
        frame = slots.beginFrame()
        slots.publish(frame, "song", VirtualListPhysicalLayoutRole.CURRENT, 20)
        slots.endFrame(frame)
        assertNotSame(first, slots.snapshots())
        assertEquals(10, first.single().current)
        slots.endFrame(slots.beginFrame())
        assertTrue(slots.snapshots().isEmpty())
        slots.clear()
        assertTrue(slots.snapshots().isEmpty())
    }

    @Test
    fun sameStableKeyOwnsCurrentAndRetainedLayouts() {
        val slots = VirtualListDualLayoutSlots<String, String>()
        val frame = slots.beginFrame()

        slots.publish(frame, "song:42", VirtualListPhysicalLayoutRole.RETAINED, "home-layout")
        slots.publish(frame, "song:42", VirtualListPhysicalLayoutRole.CURRENT, "album-layout")
        slots.endFrame(frame)

        assertEquals("home-layout", slots.retainedFor("song:42"))
        assertEquals("album-layout", slots.currentFor("song:42"))
        assertTrue(slots.hasBothLayouts("song:42"))
        assertEquals(1, slots.activeKeyCount)
        assertEquals(1, slots.dualLayoutKeyCount)
    }

    @Test
    fun roleLeavesWithoutDestroyingStillAttachedPhysicalIdentity() {
        val slots = VirtualListDualLayoutSlots<String, Int>()
        val first = slots.beginFrame()
        slots.publish(first, "song", VirtualListPhysicalLayoutRole.RETAINED, 10)
        slots.publish(first, "song", VirtualListPhysicalLayoutRole.CURRENT, 20)
        slots.endFrame(first)

        val second = slots.beginFrame()
        slots.publish(second, "song", VirtualListPhysicalLayoutRole.CURRENT, 21)
        slots.endFrame(second)

        assertNull(slots.retainedFor("song"))
        assertEquals(21, slots.currentFor("song"))
        assertFalse(slots.hasBothLayouts("song"))
        assertEquals(1, slots.activeKeyCount)
    }

    @Test
    fun logicalIdentityIsRemovedOnlyAfterBothRolesLeave() {
        val slots = VirtualListDualLayoutSlots<String, Int>()
        val first = slots.beginFrame()
        slots.publish(first, "song", VirtualListPhysicalLayoutRole.RETAINED, 1)
        slots.endFrame(first)
        assertEquals(1, slots.activeKeyCount)

        val second = slots.beginFrame()
        slots.endFrame(second)

        assertEquals(0, slots.activeKeyCount)
        assertNull(slots.retainedFor("song"))
        assertNull(slots.currentFor("song"))
    }

    @Test
    fun staleFrameCannotOverwriteNewerLayout() {
        val slots = VirtualListDualLayoutSlots<String, Int>()
        val first = slots.beginFrame()
        slots.publish(first, "song", VirtualListPhysicalLayoutRole.CURRENT, 1)

        val second = slots.beginFrame()
        slots.publish(second, "song", VirtualListPhysicalLayoutRole.CURRENT, 2)
        slots.publish(first, "song", VirtualListPhysicalLayoutRole.CURRENT, 99)
        slots.endFrame(second)

        assertEquals(2, slots.currentFor("song"))
    }
}
