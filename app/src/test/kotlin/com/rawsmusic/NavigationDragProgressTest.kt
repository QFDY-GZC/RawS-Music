package com.rawsmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDragProgressTest {
    @Test fun rawReverseDistanceIsPreservedSeparatelyFromMainProgress() {
        assertEquals(-0.12f, navigationDragRawProgress(-100f, 1f, 20f, 1000f), 0.00001f)
        assertEquals(0f, navigationDragProgress(-100f, 1f, 20f, 1000f), 0f)
    }
    @Test fun leftStartedDragStaysAtZeroBeyondOrigin() {
        for (dx in listOf(0f, 3.247f, 90f, 250f)) {
            assertEquals(0f, navigationDragProgress(dx, -1f, 28f, 1216f), 0.00001f)
        }
    }

    @Test fun rightStartedDragStaysAtZeroBeyondOrigin() {
        for (dx in listOf(0f, -11.063f, -90f, -250f)) {
            assertEquals(0f, navigationDragProgress(dx, 1f, 28f, 1216f), 0.00001f)
        }
    }

    @Test fun continuousReverseAndResumeDoesNotRebaseTheOrigin() {
        val displacements = listOf(520f, 270f, 20f, 0f, -150f, -50f, 20f, 270f)
        val expected = listOf(0.5f, 0.25f, 0f, 0f, 0f, 0f, 0f, 0.25f)
        for (direction in listOf(-1f, 1f)) {
            displacements.zip(expected).forEach { (distance, progress) ->
                assertEquals(progress, navigationDragProgress(distance * direction, direction, 20f, 1000f), 0.00001f)
            }
        }
    }

    @Test fun forwardLimitAndTouchSlopRemainClamped() {
        for (direction in listOf(-1f, 1f)) {
            assertEquals(0f, navigationDragProgress(10f * direction, direction, 20f, 1000f), 0f)
            assertEquals(1f, navigationDragProgress(1500f * direction, direction, 20f, 1000f), 0f)
            assertEquals(0.9f, navigationDragProgress(920f * direction, direction, 20f, 1000f), 0.00001f)
        }
    }
}
