package com.rawsmusic.core.ui.widget.flow

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class RawFlowBackgroundTransitionTest {
    @Test
    fun `prepared secondary uses the live Reference AA ratio directly`() {
        assertEquals(0f, ReferenceFlowAaMixProgress(0f, 0f), 1e-6f)
        assertEquals(0.25f, ReferenceFlowAaMixProgress(0.25f, 0f), 1e-6f)
        assertEquals(0.5f, ReferenceFlowAaMixProgress(0.5f, 0f), 1e-6f)
        assertEquals(1f, ReferenceFlowAaMixProgress(1f, 0f), 1e-6f)
    }

    @Test
    fun `late secondary joins without a color jump and still reaches target at commit`() {
        val readyAt = 0.40f
        assertEquals(0f, ReferenceFlowAaMixProgress(0.40f, readyAt), 1e-6f)
        assertEquals(0f, ReferenceFlowAaMixProgress(0.30f, readyAt), 1e-6f)
        assertEquals(0.5f, ReferenceFlowAaMixProgress(0.70f, readyAt), 1e-6f)
        assertEquals(1f, ReferenceFlowAaMixProgress(1f, readyAt), 1e-6f)
    }

    @Test
    fun `commit promotes the visible secondary endpoint without a post-commit palette refresh`() {
        val outgoing = listOf(Color.Red, Color.Black)
        val incomingVisible = listOf(Color.Blue, Color.Green)
        val laterCachedUpgrade = listOf(Color.Cyan, Color.Magenta)
        val slots = RawFlowAaLiveSlots()

        slots.syncCurrent("a", outgoing)
        slots.prepareSecondary("b", incomingVisible, outgoing)
        slots.updateSecondaryDuringMotion("b", incomingVisible, 0f)
        assertEquals(incomingVisible, slots.secondaryEndpoint)

        // Queue commit changes current identity to the prepared secondary slot.
        slots.syncCurrent("b", laterCachedUpgrade)
        assertEquals(incomingVisible, slots.currentEndpoint)

        // A same-key async/cache upgrade after commit is future prepare data only; it must not
        // repaint the now-stable FLOW scene.
        slots.updateCurrentIfIdle("b", laterCachedUpgrade)
        assertEquals(incomingVisible, slots.currentEndpoint)
        slots.syncCurrent("b", laterCachedUpgrade)
        assertEquals(incomingVisible, slots.currentEndpoint)
    }

    @Test
    fun `cold secondary fallback is not locked after commit and accepts real current palette`() {
        val outgoing = listOf(Color.Red, Color.Black)
        val incomingResolved = listOf(Color.Blue, Color.Green)
        val slots = RawFlowAaLiveSlots()

        slots.syncCurrent("a", outgoing)
        // No prepared target palette yet: the secondary slot only inherits the outgoing colors.
        slots.prepareSecondary("b", preparedEndpoint = null, fallback = outgoing)
        assertEquals(outgoing, slots.secondaryEndpoint)

        // Commit must promote ownership without permanently freezing that synthetic fallback.
        slots.syncCurrent("b", outgoing)
        assertEquals(outgoing, slots.currentEndpoint)
        slots.updateCurrentIfIdle("b", incomingResolved)
        assertEquals(incomingResolved, slots.currentEndpoint)
    }
    @Test
    fun `adaptive palette count cannot change FLOW renderer topology at commit`() {
        val five = listOf(Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta)
        val two = listOf(Color.Yellow, Color.Black)

        val outgoingSlots = expandReferenceFlowDrawSlots(five)
        val incomingSlots = expandReferenceFlowDrawSlots(two)

        assertEquals(5, outgoingSlots.size)
        assertEquals(5, incomingSlots.size)
        assertEquals(Color.Yellow, incomingSlots[0])
        assertEquals(Color.Black, incomingSlots[1])
        assertEquals(Color.Black, incomingSlots[2])
        assertEquals(Color.Black, incomingSlots[3])
        assertEquals(Color.Black, incomingSlots[4])

        // At artwork ratio=1 every draw slot is already the incoming endpoint. Promoting that endpoint
        // must therefore keep the exact same five-slot topology on the first committed frame.
        assertEquals(incomingSlots, expandReferenceFlowDrawSlots(two))
    }

}
