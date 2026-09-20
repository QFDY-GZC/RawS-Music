package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListScrollPositionOwnerTest {
    @Test
    fun bucketWrapKeepsOldAndNewLayoutsAtTheSameVisualPosition() {
        val contentTop = 600f
        for (exact in listOf(199.5f, 200f, 201.25f, 399.75f, 400f, 401f)) {
            for (base in listOf(0, 200, 400)) {
                val childTop = contentTop - base
                assertEquals(contentTop - exact,
                    childTop + virtualListLayoutScrollTranslation(exact, base), 0.001f)
            }
        }
    }

    @Test
    fun returnToScrolledHomeMatchesExactTransitionEndpoint() {
        val contentTop = 900f
        val rememberedScroll = 437.5f
        val settledBase = 400
        assertEquals(contentTop - rememberedScroll,
            contentTop - settledBase +
                virtualListLayoutScrollTranslation(rememberedScroll, settledBase), 0.001f)
    }

    @Test
    fun exactAndRenderRemainderHaveSeparateOwnership() {
        val owner = VirtualListScrollPositionOwner()
        var exactNotifications = 0
        var drawInvalidations = 0
        val exactListener: () -> Unit = { exactNotifications += 1 }
        val renderListener: () -> Unit = { drawInvalidations += 1 }
        owner.addExactPositionListener(exactListener)
        owner.addRenderInvalidationListener(renderListener)

        assertTrue(owner.updateExact(12.75f))
        assertEquals(12.75f, owner.currentPx, 0f)
        assertEquals(1, exactNotifications)
        assertEquals(0, drawInvalidations)
        assertFalse(owner.updateExact(12.75f))
        assertEquals(1, exactNotifications)

        assertTrue(owner.updateRenderRemainder(12))
        assertEquals(12, owner.currentRenderRemainderPx)
        assertEquals(1, exactNotifications)
        assertEquals(1, drawInvalidations)
        assertFalse(owner.updateRenderRemainder(12))
        assertEquals(1, drawInvalidations)

        owner.removeExactPositionListener(exactListener)
        owner.removeRenderInvalidationListener(renderListener)
        assertTrue(owner.updateExact(20f))
        assertTrue(owner.updateRenderRemainder(3))
        assertEquals(1, exactNotifications)
        assertEquals(1, drawInvalidations)
    }

    @Test
    fun exactListenerSeesNewAuthoritativeValue() {
        val owner = VirtualListScrollPositionOwner(5f)
        var observed = -1f
        val listener: () -> Unit = { observed = owner.currentPx }
        owner.addExactPositionListener(listener)

        owner.updateExact(42.5f)

        assertEquals(42.5f, observed, 0f)
    }
}
