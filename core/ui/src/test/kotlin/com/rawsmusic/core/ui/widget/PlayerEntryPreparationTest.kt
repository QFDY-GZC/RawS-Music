package com.rawsmusic.core.ui.widget

import org.junit.Assert.*
import org.junit.Test

class PlayerEntryPreparationTest {
    @Test fun animationCannotStartBeforeLayout() {
        val gate = PlayerEntryPreparation()
        gate.begin(7)
        assertFalse(gate.consumeReady(7))
        gate.onLayout(7)
        assertTrue(gate.consumeReady(7))
        assertFalse(gate.consumeReady(7))
    }

    @Test fun previousLayoutCannotReleaseNewRequest() {
        val gate = PlayerEntryPreparation()
        gate.begin(1)
        gate.onLayout(1)
        gate.begin(2)
        gate.onLayout(1)
        assertFalse(gate.consumeReady(1))
        assertFalse(gate.consumeReady(2))
        gate.onLayout(2)
        assertTrue(gate.consumeReady(2))
    }

    @Test fun cancellationInvalidatesDelayedLayout() {
        val gate = PlayerEntryPreparation()
        gate.begin(1)
        gate.cancel()
        gate.onLayout(1)
        assertFalse(gate.consumeReady(1))
    }
}
