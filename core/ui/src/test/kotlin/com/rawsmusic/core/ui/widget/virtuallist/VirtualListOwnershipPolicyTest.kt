package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListOwnershipPolicyTest {
    @Test
    fun layoutBarrierCoalescesNestedChildRequestsUntilCommitEnds() {
        val barrier = VirtualListLayoutRequestBarrier()

        barrier.beginCommit()
        barrier.beginCommit()
        assertFalse(barrier.requestParentLayoutNow())
        assertFalse(barrier.endCommit())
        assertTrue(barrier.isCommitActive)
        assertTrue(barrier.endCommit())
        assertFalse(barrier.isCommitActive)

        // Outside the VirtualList-style critical section a real request is allowed immediately.
        assertTrue(barrier.requestParentLayoutNow())
    }

    @Test
    fun behaviorRebindingDoesNotAdvanceStructuralRevision() {
        val revision = VirtualListPublicationRevision()
        assertEquals(0, revision.value)

        revision.markBehaviorRebound()
        assertEquals(0, revision.value)

        revision.markStructureChanged()
        assertEquals(1, revision.value)
        revision.markBehaviorRebound()
        assertEquals(1, revision.value)
    }

    @Test
    fun prewarmReadinessRequiresSameEndpointAndSameResidentSlotIdentities() {
        val readiness = VirtualListPrewarmReadiness()
        val key = VirtualListPrewarmKey(
            structuralRevision = 3,
            rangeStart = 4,
            rangeEnd = 12,
            modeOrdinal = 2,
            viewportWidthPx = 1080,
            viewportHeightPx = 1800,
            scrollYPx = 320,
            custom = false,
        )
        val ownership = mapOf(1 to "song:11", 2 to "song:12")

        assertTrue(readiness.markPrepared(key, ownership))
        assertFalse(readiness.markPrepared(key, ownership))
        assertTrue(readiness.isReady(key, ownership))
        assertFalse(readiness.isReady(key.copy(structuralRevision = 4), ownership))
        assertFalse(readiness.isReady(key, mapOf(1 to "song:99", 2 to "song:12")))
        assertFalse(readiness.isReady(key, mapOf(1 to "song:11")))
    }

    @Test
    fun prewarmReadinessKeepsMatchingSlotsReadyWhenOnlyPartOfTheRingRebounds() {
        val readiness = VirtualListPrewarmReadiness()
        val key = VirtualListPrewarmKey(
            structuralRevision = 7,
            rangeStart = 20,
            rangeEnd = 56,
            modeOrdinal = 3,
            viewportWidthPx = 1080,
            viewportHeightPx = 1800,
            scrollYPx = 640,
            custom = false,
        )
        readiness.markPrepared(
            key,
            mapOf(
                0 to "song:20",
                1 to "song:21",
                2 to "song:22",
                3 to "song:23",
            ),
        )

        val residentOwnership = mapOf(
            0 to "song:20",
            1 to "song:21",
            2 to "song:99",
            3 to "song:23",
        )
        assertEquals(setOf(0, 1, 3), readiness.readySlotIds(key, residentOwnership))
        assertEquals(
            emptySet<Int>(),
            readiness.readySlotIds(key.copy(scrollYPx = 641), residentOwnership),
        )
    }

    @Test
    fun invalidatingOnePreparedSlotDoesNotDiscardTheOtherResidentSlots() {
        val readiness = VirtualListPrewarmReadiness()
        val key = VirtualListPrewarmKey(
            structuralRevision = 9,
            rangeStart = 0,
            rangeEnd = 4,
            modeOrdinal = 1,
            viewportWidthPx = 1080,
            viewportHeightPx = 1800,
            scrollYPx = 0,
            custom = false,
        )
        val ownership = mapOf(4 to "song:4", 5 to "song:5", 6 to "song:6")
        readiness.markPrepared(key, ownership)

        readiness.invalidateSlot(5)

        assertEquals(setOf(4, 6), readiness.readySlotIds(key, ownership))
        assertFalse(readiness.isReady(key, ownership))
    }

    @Test
    fun providerPhysicalKeySharesOnlyWithinTheSameProviderInstance() {
        class EqualProvider(private val id: Int) {
            override fun equals(other: Any?): Boolean = other is EqualProvider && other.id == id
            override fun hashCode(): Int = id
        }

        val firstProvider = EqualProvider(1)
        val equalButDifferentProvider = EqualProvider(1)
        val first = VirtualListProviderItemKey(firstProvider, "song:42")

        assertEquals(first, VirtualListProviderItemKey(firstProvider, "song:42"))
        assertFalse(first == VirtualListProviderItemKey(equalButDifferentProvider, "song:42"))
        assertFalse(first == VirtualListProviderItemKey(firstProvider, "song:43"))
    }

    @Test
    fun providerPoolIdentitySharesOnlyWithinTheSameProviderInstance() {
        val firstProvider = Any()
        val secondProvider = Any()

        val first = VirtualListProviderItemKey(firstProvider, "song:1")
        val firstSibling = VirtualListProviderItemKey(firstProvider, "song:2")
        val second = VirtualListProviderItemKey(secondProvider, "song:1")

        assertTrue(first.sharesPhysicalPoolWith(firstSibling))
        assertFalse(first.sharesPhysicalPoolWith(second))
        assertFalse(first.sharesPhysicalPoolWith("song:1"))
    }

    @Test
    fun contentPublicationGateSkipsExactRepeatButReopensAfterInvalidation() {
        val gate = VirtualListContentPublicationGate<String>()

        assertTrue(gate.differsFromPublished("endpoint-a"))
        // Querying alone must not consume a deferred signature.
        assertTrue(gate.differsFromPublished("endpoint-a"))
        gate.commit("endpoint-a")
        assertFalse(gate.differsFromPublished("endpoint-a"))
        assertTrue(gate.differsFromPublished("endpoint-b"))
        assertTrue(gate.differsFromPublished("endpoint-b"))
        gate.commit("endpoint-b")
        assertFalse(gate.differsFromPublished("endpoint-b"))

        gate.invalidate()
        assertTrue(gate.differsFromPublished("endpoint-b"))
    }

    @Test
    fun outerPublicationSeparatesResidentContentFromTransitionLayoutLifetime() {
        assertEquals(
            VirtualListOuterPublicationAction.LAYOUT_ONLY_REBIND,
            resolveVirtualListOuterPublicationAction(
                outerAlreadyBound = false,
                contentChanged = true,
                residentContentReady = true,
            ),
        )
        assertEquals(
            VirtualListOuterPublicationAction.FULL_CONTENT_BIND,
            resolveVirtualListOuterPublicationAction(
                outerAlreadyBound = false,
                contentChanged = false,
                residentContentReady = false,
            ),
        )
        assertEquals(
            VirtualListOuterPublicationAction.LAYOUT_ONLY_REBIND,
            resolveVirtualListOuterPublicationAction(
                outerAlreadyBound = false,
                contentChanged = false,
                residentContentReady = true,
            ),
        )
        assertEquals(
            VirtualListOuterPublicationAction.SHELL_ONLY_REUSE,
            resolveVirtualListOuterPublicationAction(
                outerAlreadyBound = true,
                contentChanged = false,
                residentContentReady = true,
            ),
        )
        assertEquals(
            VirtualListOuterPublicationAction.LAYOUT_ONLY_REBIND,
            resolveVirtualListOuterPublicationAction(
                outerAlreadyBound = true,
                contentChanged = true,
                residentContentReady = true,
            ),
        )
    }

    @Test
    fun firstOuterAcquisitionMayBindButActiveMotionCannotRebind() {
        assertEquals(
            VirtualListOuterPublicationAction.FULL_CONTENT_BIND,
            resolveVirtualListOuterPublicationAction(
                outerAlreadyBound = false,
                contentChanged = true,
                residentContentReady = false,
                motionActive = true,
            ),
        )
        assertEquals(
            VirtualListOuterPublicationAction.DEFER_UNTIL_PREPARED,
            resolveVirtualListOuterPublicationAction(
                outerAlreadyBound = true,
                contentChanged = true,
                residentContentReady = false,
                motionActive = true,
            ),
        )
    }

    @Test
    fun transitionSessionIdentityIncludesPreparedPairGeneration() {
        assertTrue(
            virtualListTransitionSessionMatches(
                existingSource = ComposeVirtualListDisplayMode.LIST_NORMAL,
                existingTarget = ComposeVirtualListDisplayMode.LIST_ZOOMED,
                existingPairGeneration = 7,
                requestedSource = ComposeVirtualListDisplayMode.LIST_NORMAL,
                requestedTarget = ComposeVirtualListDisplayMode.LIST_ZOOMED,
                requestedPairGeneration = 7,
            )
        )
        assertFalse(
            virtualListTransitionSessionMatches(
                existingSource = ComposeVirtualListDisplayMode.LIST_NORMAL,
                existingTarget = ComposeVirtualListDisplayMode.LIST_ZOOMED,
                existingPairGeneration = 7,
                requestedSource = ComposeVirtualListDisplayMode.LIST_NORMAL,
                requestedTarget = ComposeVirtualListDisplayMode.LIST_ZOOMED,
                requestedPairGeneration = 8,
            )
        )
    }
}
