package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListPersistentRuntimeTest {
    @Test
    fun providerSwitchReusesOnePhysicalSlotAllocator() {
        val runtime = VirtualListPersistentRuntime()
        val slotPool = runtime.physicalSlotPool

        slotPool.beginFrame(listOf("home:hero"))
        val homeSlot = slotPool.slotIdForKey("home:hero")
        assertTrue(homeSlot >= 0)

        // One transition frame sees both provider roles through the same allocator.
        slotPool.beginFrame(listOf("home:hero"), keepOpen = true)
        slotPool.appendFrame(listOf("songs:row"), closeFrame = true)
        assertEquals(homeSlot, slotPool.slotIdForKey("home:hero"))
        assertTrue(slotPool.slotIdForKey("songs:row") >= 0)

        // Settling removes the source identity but does not replace the runtime object itself.
        slotPool.beginFrame(listOf("songs:row"))
        assertEquals(-1, slotPool.slotIdForKey("home:hero"))
        assertTrue(slotPool.slotIdForKey("songs:row") >= 0)
        assertSame(slotPool, runtime.physicalSlotPool)
        assertSame(runtime.physicalLayoutSlots, runtime.physicalLayoutSlots)
        assertSame(runtime.physicalHolderOwner, runtime.physicalHolderOwner)
    }
    @Test
    fun ordinaryCanonicalSlotSurvivesSettledTransitionAndSettledHandoff() {
        val runtime = VirtualListPersistentRuntime()
        val slotPool = runtime.physicalSlotPool
        val songKey = "song:42"

        // Settled category owns the canonical ordinary holder.
        slotPool.beginFrame(listOf(songKey))
        val settledSlot = slotPool.slotIdForKey(songKey)
        assertTrue(settledSlot >= 0)

        // Outer motion may also attach provider-specific custom holders, but the ordinary song must
        // keep its canonical physical slot until the complete retained/current frame closes.
        slotPool.beginFrame(listOf("home:custom"), keepOpen = true)
        slotPool.appendFrame(listOf(songKey), closeFrame = true)
        assertEquals(settledSlot, slotPool.slotIdForKey(songKey))

        // Target settled ownership reuses the same slot instead of releasing artwork/text holders.
        slotPool.beginFrame(listOf(songKey))
        assertEquals(settledSlot, slotPool.slotIdForKey(songKey))
    }

}
