package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullscreenArtworkCarouselInteractionTest {
    @Test
    fun everyVisibleSideLaneCanBeHitAtItsRenderedCenter() {
        val metrics = resolveFullscreenArtworkCarouselMetrics(2400f, 1080f)
        for (logicalOffset in -FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS..
            FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS
        ) {
            if (logicalOffset == 0) continue
            val transform = resolveFullscreenArtworkLaneTransform(logicalOffset.toFloat(), metrics)
            assertEquals(
                logicalOffset,
                resolveFullscreenArtworkTappedLane(
                    positionX = metrics.centerXPx + transform.translationX,
                    positionY = metrics.centerYPx,
                    metrics = metrics,
                    progress = 0f,
                ),
            )
        }
    }

    @Test
    fun centreAndBlankSpaceRemainOutsideSideLaneHitPolicy() {
        val metrics = resolveFullscreenArtworkCarouselMetrics(2400f, 1080f)
        assertNull(
            resolveFullscreenArtworkTappedLane(
                positionX = metrics.centerXPx,
                positionY = metrics.centerYPx,
                metrics = metrics,
                progress = 0f,
            )
        )
        assertNull(
            resolveFullscreenArtworkTappedLane(
                positionX = 4f,
                positionY = 4f,
                metrics = metrics,
                progress = 0f,
            )
        )
    }

    @Test
    fun farLaneSelectionIsExpandedIntoContinuousSingleRailCommits() {
        assertEquals(listOf(1), fullscreenCarouselSelectionSteps(1))
        assertEquals(listOf(1, 1, 1, 1), fullscreenCarouselSelectionSteps(4))
        assertEquals(listOf(-1, -1, -1), fullscreenCarouselSelectionSteps(-3))
        assertEquals(emptyList<Int>(), fullscreenCarouselSelectionSteps(0))
    }
    @Test
    fun multiRailSelectionKeepsIntegerBoundaryGeometryContinuous() {
        val before = resolveFullscreenCarouselSelectionFrame(
            startIndex = 7,
            laneOffset = 4,
            travelledRails = 0.999f,
            queueSize = 20,
        )
        val boundary = resolveFullscreenCarouselSelectionFrame(
            startIndex = 7,
            laneOffset = 4,
            travelledRails = 1f,
            queueSize = 20,
        )
        val after = resolveFullscreenCarouselSelectionFrame(
            startIndex = 7,
            laneOffset = 4,
            travelledRails = 1.001f,
            queueSize = 20,
        )
        assertEquals(7, before.centerIndex)
        assertEquals(0.999f, before.progress, 0.0001f)
        assertEquals(8, boundary.centerIndex)
        assertEquals(0f, boundary.progress, 0.0001f)
        assertEquals(8, after.centerIndex)
        assertEquals(0.001f, after.progress, 0.0001f)
    }

    @Test
    fun negativeFarSelectionWrapsAndFinishesAtNeutralProgress() {
        val final = resolveFullscreenCarouselSelectionFrame(
            startIndex = 1,
            laneOffset = -4,
            travelledRails = 4f,
            queueSize = 10,
        )
        assertEquals(7, final.centerIndex)
        assertEquals(0f, final.progress, 0.0001f)
    }

}
