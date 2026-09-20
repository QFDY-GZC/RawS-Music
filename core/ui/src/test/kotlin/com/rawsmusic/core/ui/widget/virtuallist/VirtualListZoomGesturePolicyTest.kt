package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListZoomGesturePolicyTest {
    @Test
    fun newTransitionPairCannotAdvanceUntilItsPopulationIsPublished() {
        val gate = VirtualListTransitionPreparationGate()
        val first = gate.beginPair()

        assertFalse(gate.isPrepared(first))
        gate.markPrepared(first)
        assertTrue(gate.isPrepared(first))

        val second = gate.beginPair()
        assertFalse(gate.isPrepared(second))
        gate.markPrepared(first)
        assertFalse(gate.isPrepared(second))
        gate.markPrepared(second)
        assertTrue(gate.isPrepared(second))
    }

    @Test
    fun secondPinchUsesCurrentSpanMotionInsteadOfTouchDownTotalSign() {
        // The new gesture first compresses by 1%, then reverses and expands by 0.6%. Total span is
        // still below touch-down (-0.4%), but scaleGestureOwner-style capture must already switch to zoom-in.
        assertTrue(virtualListPinchExpandsNow(rawDelta = -0.004f, spanDeltaRatio = 0.006f))
        assertFalse(virtualListPinchExpandsNow(rawDelta = 0.004f, spanDeltaRatio = -0.006f))
    }

    @Test
    fun settledEndpointHandoffWaitsForFirstRealSecondPinchMotion() {
        assertFalse(shouldCanonicalizeSettledVirtualListEndpointForNewPinch(false, 0.01f))
        assertFalse(shouldCanonicalizeSettledVirtualListEndpointForNewPinch(true, 0f))
        assertFalse(shouldCanonicalizeSettledVirtualListEndpointForNewPinch(true, 0.0000005f))
        assertTrue(shouldCanonicalizeSettledVirtualListEndpointForNewPinch(true, 0.00001f))
        assertTrue(shouldCanonicalizeSettledVirtualListEndpointForNewPinch(true, -0.00001f))
    }

    @Test
    fun settledEndpointCanHandDirectlyToAdjacentZoomOwner() {
        assertEquals(
            ComposeVirtualListDisplayMode.GRID_3,
            adjacentVirtualListMode(ComposeVirtualListDisplayMode.GRID_4, zoomIn = true),
        )
        assertEquals(
            ComposeVirtualListDisplayMode.LIST_ZOOMED,
            adjacentVirtualListMode(ComposeVirtualListDisplayMode.GRID_4, zoomIn = false),
        )
        assertEquals(null, adjacentVirtualListMode(ComposeVirtualListDisplayMode.GRID_2, zoomIn = true))
        assertEquals(null, adjacentVirtualListMode(ComposeVirtualListDisplayMode.LIST_SMALL, zoomIn = false))
    }

    @Test
    fun frameDeltaContinuesInterruptedTransitionWithoutBaseDistanceLatch() {
        assertEquals(0.012f, virtualListOrientedPinchFrameDelta(0.012f, true), 0.0001f)
        assertEquals(0.012f, virtualListOrientedPinchFrameDelta(-0.012f, false), 0.0001f)
        assertEquals(-0.012f, virtualListOrientedPinchFrameDelta(-0.012f, true), 0.0001f)
    }

    @Test
    fun lowVelocityCommitsOnlyPastPointThree() {
        assertFalse(resolveVirtualListZoomRelease(0.30f, 0f, 0f, true).confirm)
        assertTrue(resolveVirtualListZoomRelease(0.3001f, 0f, 0f, true).confirm)
    }

    @Test
    fun physicalVelocityUsesZoomDirectionAtFiveHundredDpPerSecond() {
        assertTrue(resolveVirtualListZoomRelease(0.05f, 500f, 0f, true).confirm)
        assertFalse(resolveVirtualListZoomRelease(0.95f, -500f, 0f, true).confirm)
        assertTrue(resolveVirtualListZoomRelease(0.05f, -500f, 0f, false).confirm)
    }

    @Test
    fun progressVelocityIsForwardedOnlyNearChosenEndpointAndQuartered() {
        assertEquals(
            1.5f,
            resolveVirtualListZoomRelease(0.85f, 800f, 6f, true).forwardedProgressVelocityPerSecond,
            0.001f,
        )
        assertEquals(
            0f,
            resolveVirtualListZoomRelease(0.50f, 800f, 6f, true).forwardedProgressVelocityPerSecond,
            0.001f,
        )
        assertEquals(
            -1.5f,
            resolveVirtualListZoomRelease(0.15f, -800f, -6f, true).forwardedProgressVelocityPerSecond,
            0.001f,
        )
    }

    @Test
    fun noVelocitySettleUsesReferenceRemainingDurationAndDirectionFloors() {
        val nearCommit = resolveVirtualListZoomSettleSpec(0.90f, true, 0f)
        assertEquals(VirtualListZoomSettleKind.AccelerateDecelerate, nearCommit.kind)
        assertEquals(100, nearCommit.durationMs)

        val nearCancel = resolveVirtualListZoomSettleSpec(0.10f, false, 0f)
        assertEquals(VirtualListZoomSettleKind.AccelerateDecelerate, nearCancel.kind)
        assertEquals(REFERENCE_ZOOM_CANCEL_MIN_SETTLE_MS, nearCancel.durationMs)

        assertEquals(250, resolveVirtualListZoomSettleSpec(0.50f, true, 0f).durationMs)
        assertEquals(250, resolveVirtualListZoomSettleSpec(0.50f, false, 0f).durationMs)
    }

    @Test
    fun validVelocitySettleUsesZ1ConstantProgressVelocityAndClamps() {
        val commit = resolveVirtualListZoomSettleSpec(0.90f, true, 1.5f)
        assertEquals(VirtualListZoomSettleKind.ConstantVelocity, commit.kind)
        assertEquals(2f, commit.progressVelocityPerSecond, 0.001f)
        assertEquals(51, commit.durationMs)

        val fastCommit = resolveVirtualListZoomSettleSpec(0.20f, true, 20f)
        assertEquals(8f, fastCommit.progressVelocityPerSecond, 0.001f)
        assertEquals(100, fastCommit.durationMs)

        val cancel = resolveVirtualListZoomSettleSpec(0.10f, false, -1f)
        assertEquals(VirtualListZoomSettleKind.ConstantVelocity, cancel.kind)
        assertEquals(-3.5f, cancel.progressVelocityPerSecond, 0.001f)
        assertEquals(29, cancel.durationMs)

        val fastCancel = resolveVirtualListZoomSettleSpec(0.80f, false, -20f)
        assertEquals(-8f, fastCancel.progressVelocityPerSecond, 0.001f)
        assertEquals(100, fastCancel.durationMs)
    }

    @Test
    fun wrongSignForwardedVelocityFallsBackToTimedAnimator() {
        assertEquals(
            VirtualListZoomSettleKind.AccelerateDecelerate,
            resolveVirtualListZoomSettleSpec(0.85f, true, -4f).kind,
        )
        assertEquals(
            VirtualListZoomSettleKind.AccelerateDecelerate,
            resolveVirtualListZoomSettleSpec(0.15f, false, 4f).kind,
        )
    }

    @Test
    fun q1EdgeResistanceIsBoundedAndDirectionallyAsymmetric() {
        val targetSmall = virtualListZoomVisualProgress(1.1f) - 1f
        val sourceSmall = -virtualListZoomVisualProgress(-0.1f)
        assertTrue(targetSmall > 0f)
        assertTrue(sourceSmall > targetSmall)
        val maxTarget = virtualListZoomVisualProgress(4f) - 1f
        val maxSource = -virtualListZoomVisualProgress(-0.9f)
        assertTrue(abs(maxTarget - 0.0894427f) < 0.0001f)
        assertTrue(abs(maxSource - 0.0894427f) < 0.0001f)
    }

    @Test
    fun endpointOverrunBecomesOneWholeLayoutScaleInsteadOfGeometryProgress() {
        val logicalTargetOverrun = 1.6f
        val visualTargetOverrun = virtualListZoomVisualProgress(logicalTargetOverrun)
        val targetScale = virtualListZoomEndpointGroupScale(logicalTargetOverrun, visualTargetOverrun, transitionZoomIn = true)
        assertTrue(visualTargetOverrun > 1f)
        assertTrue(targetScale > 1f)
        assertTrue(targetScale <= 1.0895f)

        val logicalSourceOverrun = -0.4f
        val visualSourceOverrun = virtualListZoomVisualProgress(logicalSourceOverrun)
        val sourceScale = virtualListZoomEndpointGroupScale(logicalSourceOverrun, visualSourceOverrun, transitionZoomIn = true)
        assertTrue(visualSourceOverrun < 0f)
        assertTrue(sourceScale < 1f)
        assertTrue(sourceScale >= 0.9105f)

        assertEquals(1f, virtualListZoomEndpointGroupScale(0.55f), 0.0001f)
    }

    @Test
    fun zoomOutEndpointReboundUsesPhysicalPinchDirection() {
        val sourceOverrun = -0.4f
        val sourceVisual = virtualListZoomVisualProgress(sourceOverrun)
        assertTrue(
            virtualListZoomEndpointGroupScale(
                logicalProgress = sourceOverrun,
                visualProgress = sourceVisual,
                transitionZoomIn = false,
            ) > 1f
        )

        val targetOverrun = 1.6f
        val targetVisual = virtualListZoomVisualProgress(targetOverrun)
        assertTrue(
            virtualListZoomEndpointGroupScale(
                logicalProgress = targetOverrun,
                visualProgress = targetVisual,
                transitionZoomIn = false,
            ) < 1f
        )
    }

    @Test
    fun interruptedOwnerResolvesWithSamePointThreeThreshold() {
        assertFalse(resolveInterruptedVirtualListZoomCommit(0.3f))
        assertTrue(resolveInterruptedVirtualListZoomCommit(0.31f))
    }
}
