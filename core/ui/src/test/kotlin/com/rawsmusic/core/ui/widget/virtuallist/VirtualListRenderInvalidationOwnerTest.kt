package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListRenderInvalidationOwnerTest {
    @Test
    fun oldShellDetachCannotClearIncomingOwner() {
        val lease = VirtualListRenderInvalidationOwner()
        val settled = Any()
        val transition = Any()
        var settledInvalidations = 0
        var transitionInvalidations = 0

        lease.attach(settled) { settledInvalidations++ }
        lease.invalidate()
        assertEquals(1, settledInvalidations)

        lease.attach(transition) { transitionInvalidations++ }
        assertTrue(lease.isOwnedBy(transition))
        assertFalse(lease.isOwnedBy(settled))

        // Compose is allowed to detach the old shell after the new one attached.
        lease.detach(settled)
        lease.invalidate()
        assertEquals(1, transitionInvalidations)
        assertTrue(lease.isOwnedBy(transition))

        lease.detach(transition)
        lease.invalidate()
        assertEquals(1, transitionInvalidations)
    }

    @Test
    fun typedInvalidationKeepsArtworkLocalToItsPhysicalSlot() {
        val lease = VirtualListRenderInvalidationOwner()
        val owner = Any()
        val received = ArrayList<VirtualListRenderInvalidation>()

        lease.attach(owner) { signal -> received += signal }
        lease.invalidate(VirtualListRenderInvalidation.artwork(slotId = 7))
        lease.invalidate(VirtualListRenderInvalidation.motion())
        lease.invalidate(VirtualListRenderInvalidation.content())

        assertEquals(
            listOf(
                VirtualListRenderInvalidation(VirtualListInvalidationKind.ARTWORK, 7),
                VirtualListRenderInvalidation(VirtualListInvalidationKind.MOTION, -1),
                VirtualListRenderInvalidation(VirtualListInvalidationKind.CONTENT, -1),
            ),
            received,
        )
    }
}
