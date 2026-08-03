package com.rawsmusic.core.ui.widget.player

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FullCoverLaunchGeometryTest {
    @Test
    fun clockwisePortraitBoundsMapIntoLandscapeRoot() {
        val mapped = assertNotNull(
            resolveLandscapeLaunchSourceBounds(
                sourceBounds = Rect(100f, 500f, 700f, 1100f),
                sourceViewportWidthPx = 1080f,
                sourceViewportHeightPx = 2344f,
                targetViewportWidthPx = 2344f,
                targetViewportHeightPx = 1080f,
                targetRotationDegrees = 90,
            )
        )
        assertEquals(1244f, mapped.left, 0.01f)
        assertEquals(100f, mapped.top, 0.01f)
        assertEquals(1844f, mapped.right, 0.01f)
        assertEquals(700f, mapped.bottom, 0.01f)
    }

    @Test
    fun counterClockwisePortraitBoundsMapIntoLandscapeRoot() {
        val mapped = assertNotNull(
            resolveLandscapeLaunchSourceBounds(
                sourceBounds = Rect(100f, 500f, 700f, 1100f),
                sourceViewportWidthPx = 1080f,
                sourceViewportHeightPx = 2344f,
                targetViewportWidthPx = 2344f,
                targetViewportHeightPx = 1080f,
                targetRotationDegrees = 270,
            )
        )
        assertEquals(500f, mapped.left, 0.01f)
        assertEquals(380f, mapped.top, 0.01f)
        assertEquals(1100f, mapped.right, 0.01f)
        assertEquals(980f, mapped.bottom, 0.01f)
    }
}
