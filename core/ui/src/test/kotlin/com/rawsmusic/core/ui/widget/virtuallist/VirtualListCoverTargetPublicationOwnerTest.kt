package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListCoverTargetPublicationOwnerTest {
    @Test
    fun outgoingControllerCannotClearOrReclaimIncomingEndpointOwner() {
        val lease = VirtualListCoverTargetPublicationOwner()
        val outgoing = Any()
        val incoming = Any()
        val outgoingEpoch = lease.register()
        val incomingEpoch = lease.register()
        var outgoingClears = 0
        var incomingClears = 0

        lease.claim(outgoing, outgoingEpoch, { if (it == null) outgoingClears++ }, { })
        lease.claim(incoming, incomingEpoch, { if (it == null) incomingClears++ }, { })
        assertTrue(lease.isOwnedBy(incoming))
        assertFalse(lease.isOwnedBy(outgoing))

        // A late old-root scroll/layout callback cannot steal the endpoint back.
        lease.claim(outgoing, outgoingEpoch, { if (it == null) outgoingClears++ }, { })
        assertTrue(lease.isOwnedBy(incoming))

        lease.clear(outgoing, outgoingEpoch)
        assertEquals(0, outgoingClears)
        assertEquals(0, incomingClears)
        assertTrue(lease.isOwnedBy(incoming))

        lease.clear(incoming, incomingEpoch)
        assertEquals(1, incomingClears)

        lease.claim(outgoing, outgoingEpoch, { if (it == null) outgoingClears++ }, { })
        assertFalse(lease.isOwnedBy(outgoing))
    }

    @Test
    fun incomingRegisteredBeforeOldDetachCanStillClaimAfterDetach() {
        val lease = VirtualListCoverTargetPublicationOwner()
        val outgoing = Any()
        val incoming = Any()
        val outgoingEpoch = lease.register()
        lease.claim(outgoing, outgoingEpoch, { }, { })

        // Compose can create/register the incoming controller before old onDetach runs.
        val incomingEpoch = lease.register()
        lease.clear(outgoing, outgoingEpoch)
        assertFalse(lease.isOwnedBy(outgoing))

        lease.claim(incoming, incomingEpoch, { }, { })
        assertTrue(lease.isOwnedBy(incoming))
    }
}
