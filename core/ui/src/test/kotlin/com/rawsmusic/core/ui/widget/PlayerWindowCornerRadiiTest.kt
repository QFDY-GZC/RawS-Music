package com.rawsmusic.core.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerWindowCornerRadiiTest {
    @Test
    fun expansionRetainsDifferentPhysicalCornersAndIsReversible() {
        val device = PlayerWindowCornerRadii(48f, 50f, 64f, 60f)
        assertEquals(PlayerWindowCornerRadii(40f, 40f, 40f, 40f), at(device, 0f))
        assertEquals(PlayerWindowCornerRadii(44f, 45f, 52f, 50f), at(device, 0.5f))
        assertEquals(device, at(device, 1f))
        val forward = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { at(device, it) }
        val backward = listOf(1f, 0.75f, 0.5f, 0.25f, 0f).map { at(device, it) }
        assertEquals(forward, backward.reversed())
    }

    @Test
    fun unavailableOrSquareCornersKeepExistingTransition() {
        assertEquals(PlayerWindowCornerRadii(20f, 20f, 20f, 20f), at(PlayerWindowCornerRadii.Zero, 0.5f))
        assertEquals(PlayerWindowCornerRadii.Zero, at(PlayerWindowCornerRadii.Zero, 1f))
    }

    @Test
    fun invalidRadiusAndOversizedRadiusCannotEscapeSurfaceBounds() {
        val actual = resolvePlayerSurfaceCornerRadii(
            40f, PlayerWindowCornerRadii(-1f, Float.NaN, Float.POSITIVE_INFINITY, 900f),
            1f, 200f, 80f,
        )
        assertEquals(PlayerWindowCornerRadii(0f, 0f, 0f, 40f), actual)
    }

    @Test
    fun xiaomiPortraitRadiiRotateToTheCorrectPhysicalEdges() {
        assertEquals(PlayerWindowCornerRadii(48f, 48f, 64f, 64f), rotatedPlayerWindowCorners(48f, 64f, 0))
        assertEquals(PlayerWindowCornerRadii(48f, 64f, 64f, 48f), rotatedPlayerWindowCorners(48f, 64f, 1))
        assertEquals(PlayerWindowCornerRadii(64f, 64f, 48f, 48f), rotatedPlayerWindowCorners(48f, 64f, 2))
        assertEquals(PlayerWindowCornerRadii(64f, 48f, 48f, 64f), rotatedPlayerWindowCorners(48f, 64f, 3))
    }

    @Test
    fun onlyCornersTouchingTheViewportAreApplied() {
        // A split window's lower corners are not physical screen corners.
        val corners = PlayerWindowCornerRadii(48f, 48f, 64f, 64f)
        assertEquals(PlayerWindowCornerRadii(48f, 48f, 0f, 0f), corners.touchingEdges(true, true, true, false))
        assertEquals(PlayerWindowCornerRadii.Zero, corners.touchingEdges(false, false, false, false))
        assertEquals(PlayerWindowCornerRadii(0f, 48f, 64f, 0f), corners.touchingEdges(false, true, true, true))
    }

    private fun at(corners: PlayerWindowCornerRadii, fraction: Float) =
        resolvePlayerSurfaceCornerRadii(40f, corners, fraction, 1080f, 2340f)
}
