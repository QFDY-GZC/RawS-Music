package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PortraitDialSelectionPolicyTest {
    @Test
    fun pendingOldEmissionIsIgnored() {
        assertEquals(
            PortraitDialExternalSyncAction.IgnoreStaleEmission,
            resolvePortraitDialExternalSyncAction(
                currentWrappedIndex = 4,
                resolvedPlayerIndex = 0,
                queueSize = 10,
                currentSongIdentity = "old",
                pendingSelectionIndex = 4,
                pendingSelectionIdentity = "new",
                pendingSelectionDeadlineMs = 5_000L,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun exactPlayerConfirmationOnlyConfirms() {
        assertEquals(
            PortraitDialExternalSyncAction.ConfirmPending,
            resolvePortraitDialExternalSyncAction(
                currentWrappedIndex = 4,
                resolvedPlayerIndex = 4,
                queueSize = 10,
                currentSongIdentity = "new",
                pendingSelectionIndex = 4,
                pendingSelectionIdentity = "new",
                pendingSelectionDeadlineMs = 5_000L,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun externalAdjacentChangeAnimatesWithoutTransportDispatch() {
        assertEquals(
            PortraitDialExternalSyncAction.Animate(1),
            resolvePortraitDialExternalSyncAction(
                currentWrappedIndex = 4,
                resolvedPlayerIndex = 5,
                queueSize = 10,
                currentSongIdentity = "automatic",
                pendingSelectionIndex = -1,
                pendingSelectionIdentity = null,
                pendingSelectionDeadlineMs = 0L,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun distantExternalChangeSnapsVisualOnly() {
        assertEquals(
            PortraitDialExternalSyncAction.Snap(8),
            resolvePortraitDialExternalSyncAction(
                currentWrappedIndex = 1,
                resolvedPlayerIndex = 8,
                queueSize = 10,
                currentSongIdentity = "automatic",
                pendingSelectionIndex = -1,
                pendingSelectionIdentity = null,
                pendingSelectionDeadlineMs = 0L,
                nowMs = 1_000L,
            ),
        )
    }
}
