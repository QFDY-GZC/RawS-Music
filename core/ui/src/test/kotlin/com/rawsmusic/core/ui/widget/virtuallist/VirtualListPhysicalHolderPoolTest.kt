package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListPhysicalHolderPoolTest {
    @Test
    fun layoutResCarriesSettledRenderRemainderIntoExactViewportPosition() {
        val base = ComposeItemPosition(
            bounds = IntRect(12, 100, 212, 196),
            alpha = 0.75f,
            scaleX = 1f,
            scaleY = 1f,
        )
        val layout = VirtualListPhysicalLayoutRes(
            index = 4,
            mode = ComposeVirtualListDisplayMode.LIST_NORMAL,
            params = ListZoomLevels.params.getValue(ListZoomIndex.NORMAL),
            position = base,
            drawContentEnabled = true,
            artworkVisible = true,
            renderRemainderPx = 37,
        )

        val exact = layout.exactViewportPosition()
        assertEquals(IntRect(12, 63, 212, 159), exact.bounds)
        assertEquals(base.alpha, exact.alpha)
        assertEquals(base.scaleX, exact.scaleX)
        assertEquals(base.scaleY, exact.scaleY)
    }
    @Test
    fun preflightReservationSurvivesVisibleSourceCleanupUntilTargetAttaches() {
        val pool = VirtualListPhysicalSlotPool(initialCapacity = 2)
        pool.beginFrame(listOf("source"))
        val sourceSlot = pool.slotIdForKey("source")

        pool.reserveKeys(listOf("target"))
        val reservedTargetSlot = pool.slotIdForKey("target")
        assertEquals(true, reservedTargetSlot >= 0)

        // The still-visible source provider closes a frame before GenericPivot promotion. Reserved
        // destination identity must not be reclaimed during that cleanup pass.
        pool.beginFrame(listOf("source"))
        assertEquals(sourceSlot, pool.slotIdForKey("source"))
        assertEquals(reservedTargetSlot, pool.slotIdForKey("target"))

        // Real target attachment consumes the reservation and keeps the exact physical slot.
        pool.beginFrame(listOf("source"), keepOpen = true)
        pool.appendFrame(listOf("target"), closeFrame = true)
        assertEquals(reservedTargetSlot, pool.slotIdForKey("target"))
    }

    @Test
    fun cancelledPreflightReservationIsReclaimedByNextVisibleFrame() {
        val pool = VirtualListPhysicalSlotPool(initialCapacity = 2)
        pool.beginFrame(listOf("source"))
        pool.reserveKeys(listOf("cancelled-target"))
        assertEquals(true, pool.slotIdForKey("cancelled-target") >= 0)

        pool.releaseReservedKeys(listOf("cancelled-target"))
        pool.beginFrame(listOf("source"))
        assertEquals(-1, pool.slotIdForKey("cancelled-target"))
    }

    @Test
    fun reclaimedKeyDoesNotDestroyThePhysicalSlotResidency() {
        val pool = VirtualListPhysicalSlotPool(initialCapacity = 1)
        pool.beginFrame(listOf("first"))
        val slot = pool.slotIdForKey("first")
        assertTrue(pool.isResidentSlot(slot))

        pool.beginFrame(emptyList())
        assertEquals(-1, pool.slotIdForKey("first"))
        assertTrue(pool.isResidentSlot(slot))
        assertFalse(pool.isSlotBound(slot))

        pool.beginFrame(listOf("second"))
        assertEquals(slot, pool.slotIdForKey("second"))
        assertTrue(pool.isSlotBound(slot))
    }

    @Test
    fun differentProviderPoolsDoNotRecycleTheSameResidentSlot() {
        val pool = VirtualListPhysicalSlotPool(initialCapacity = 1)
        val songsProvider = Any()
        val homeProvider = Any()

        val songsFirst = VirtualListProviderItemKey(songsProvider, "song:1")
        pool.beginFrame(listOf(songsFirst))
        val songsSlot = pool.slotIdForKey(songsFirst)

        // The SONGS key leaves the attached population, but its resident v0/d2 bank remains SONGS.
        pool.beginFrame(emptyList())

        val homeFirst = VirtualListProviderItemKey(homeProvider, "home:library-row")
        pool.beginFrame(listOf(homeFirst))
        val homeSlot = pool.slotIdForKey(homeFirst)
        assertNotEquals(songsSlot, homeSlot)

        // Returning to the original provider reuses its own resident bank instead of replacing the
        // HOME holder type in-place.
        pool.beginFrame(emptyList())
        val songsSecond = VirtualListProviderItemKey(songsProvider, "song:2")
        pool.beginFrame(listOf(songsSecond))
        assertEquals(songsSlot, pool.slotIdForKey(songsSecond))
    }

    @Test
    fun scopedInactiveKeysSurviveAnotherProviderAndReactivateInTheSameSlots() {
        val pool = VirtualListPhysicalSlotPool(initialCapacity = 1)
        val songsProvider = Any()
        val homeProvider = Any()
        val songs = (10..13).map { VirtualListProviderItemKey(songsProvider, "song:$it") }

        pool.beginFrame(songs)
        val originalSlots = songs.associateWith(pool::slotIdForKey)

        val home = VirtualListProviderItemKey(homeProvider, "home:library")
        pool.beginFrame(listOf(home))

        songs.forEach { key ->
            assertEquals(originalSlots.getValue(key), pool.slotIdForKey(key))
            assertFalse(pool.isSlotBound(originalSlots.getValue(key)))
        }

        pool.beginFrame(songs)
        songs.forEach { key ->
            assertEquals(originalSlots.getValue(key), pool.slotIdForKey(key))
            assertTrue(pool.isSlotBound(originalSlots.getValue(key)))
        }
    }

    @Test
    fun sameProviderWindowShiftPreservesOverlapAndRecyclesOnlyInactiveSlots() {
        val pool = VirtualListPhysicalSlotPool(initialCapacity = 1)
        val provider = Any()
        val first = (10..13).map { VirtualListProviderItemKey(provider, "song:$it") }
        val second = (8..11).map { VirtualListProviderItemKey(provider, "song:$it") }

        pool.beginFrame(first)
        val firstSlots = first.associateWith(pool::slotIdForKey)
        val capacity = pool.physicalCapacity

        pool.beginFrame(second)

        assertEquals(firstSlots.getValue(first[0]), pool.slotIdForKey(second[2]))
        assertEquals(firstSlots.getValue(first[1]), pool.slotIdForKey(second[3]))
        assertEquals(
            setOf(firstSlots.getValue(first[2]), firstSlots.getValue(first[3])),
            setOf(pool.slotIdForKey(second[0]), pool.slotIdForKey(second[1])),
        )
        assertEquals(capacity, pool.physicalCapacity)
    }

}
