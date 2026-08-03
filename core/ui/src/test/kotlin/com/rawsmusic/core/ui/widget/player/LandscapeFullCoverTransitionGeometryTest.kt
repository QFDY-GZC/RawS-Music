package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeFullCoverTransitionGeometryTest {
    @Test
    fun sharedArtworkStartsAtMeasuredPlayerBounds() {
        val frame = resolveLandscapeFullCoverTransitionFrame(
            sourceLeftPx = 80f,
            sourceTopPx = 60f,
            sourceWidthPx = 280f,
            sourceHeightPx = 280f,
            viewportWidthPx = 1600f,
            viewportHeightPx = 900f,
            progress = 0f,
        )

        assertEquals(80f, frame.sharedLeftPx, 0.001f)
        assertEquals(60f, frame.sharedTopPx, 0.001f)
        assertEquals(280f, frame.sharedWidthPx, 0.001f)
        assertEquals(1f, frame.sourceContentAlpha, 0.001f)
        assertEquals(0f, frame.targetContentAlpha, 0.001f)
    }

    @Test
    fun sharedArtworkEndsAtExactCarouselCentreLane() {
        val width = 1600f
        val height = 900f
        val metrics = resolveFullscreenArtworkCarouselMetrics(width, height)
        val frame = resolveLandscapeFullCoverTransitionFrame(
            sourceLeftPx = 80f,
            sourceTopPx = 60f,
            sourceWidthPx = 280f,
            sourceHeightPx = 280f,
            viewportWidthPx = width,
            viewportHeightPx = height,
            progress = 1f,
        )

        assertEquals(metrics.centerXPx - metrics.coverSidePx * 0.5f, frame.sharedLeftPx, 0.001f)
        assertEquals(metrics.centerYPx - metrics.coverSidePx * 0.5f, frame.sharedTopPx, 0.001f)
        assertEquals(metrics.coverSidePx, frame.sharedWidthPx, 0.001f)
        assertEquals(0f, frame.sourceContentAlpha, 0.001f)
        assertEquals(1f, frame.targetContentAlpha, 0.001f)
    }

    @Test
    fun targetOnlyLanesExpandMonotonically() {
        var previousReveal = -1f
        for (step in 0..20) {
            val progress = step / 20f
            val frame = resolveLandscapeFullCoverTransitionFrame(
                sourceLeftPx = 80f,
                sourceTopPx = 60f,
                sourceWidthPx = 280f,
                sourceHeightPx = 280f,
                viewportWidthPx = 1600f,
                viewportHeightPx = 900f,
                progress = progress,
            )
            assertTrue(frame.targetLaneRevealProgress + 0.0001f >= previousReveal)
            assertTrue(frame.targetContentScale in 0.62f..1f)
            previousReveal = frame.targetLaneRevealProgress
        }
    }

    @Test
    fun reversePathUsesTheSameGeometry() {
        val forward = resolveLandscapeFullCoverTransitionFrame(
            80f, 60f, 280f, 280f, 1600f, 900f, 0.42f,
        )
        val repeated = resolveLandscapeFullCoverTransitionFrame(
            80f, 60f, 280f, 280f, 1600f, 900f, 0.42f,
        )

        assertTrue(abs(forward.sharedLeftPx - repeated.sharedLeftPx) < 0.0001f)
        assertTrue(abs(forward.targetLaneRevealProgress - repeated.targetLaneRevealProgress) < 0.0001f)
    }
    @Test
    fun nonArtworkLayoutsKeepStep81ScaleEndpointsAndCrossfade() {
        val start = resolveLandscapeFullCoverTransitionFrame(
            80f, 60f, 280f, 280f, 1600f, 900f, 0f,
        )
        val middle = resolveLandscapeFullCoverTransitionFrame(
            80f, 60f, 280f, 280f, 1600f, 900f, 0.5f,
        )
        val end = resolveLandscapeFullCoverTransitionFrame(
            80f, 60f, 280f, 280f, 1600f, 900f, 1f,
        )

        assertEquals(1f, start.sourceContentScale, 0.001f)
        assertEquals(0.84f, end.sourceContentScale, 0.001f)
        assertEquals(0.62f, start.targetContentScale, 0.001f)
        assertEquals(1f, end.targetContentScale, 0.001f)
        assertTrue(middle.sourceContentAlpha in 0f..1f)
        assertTrue(middle.targetContentAlpha in 0f..1f)
        assertTrue(middle.sourceContentAlpha > 0f)
        assertTrue(middle.targetContentAlpha > 0f)
    }

    @Test
    fun predictiveBackMapsOneGestureDirectlyOntoSceneProgress() {
        assertEquals(1f, resolveFullCoverPredictiveProgress(1f, 0f), 0.001f)
        assertEquals(0.5f, resolveFullCoverPredictiveProgress(1f, 0.5f), 0.001f)
        assertEquals(0f, resolveFullCoverPredictiveProgress(1f, 1f), 0.001f)
        assertEquals(0.2f, resolveFullCoverPredictiveProgress(0.4f, 0.5f), 0.001f)
    }

    @Test
    fun homeSourceBoundsAreClippedToTheActuallyVisibleCanvasArea() {
        val clipped = resolveVisibleFullCoverSourceBounds(
            artworkLeftPx = 100f,
            artworkTopPx = 80f,
            artworkRightPx = 500f,
            artworkBottomPx = 480f,
            canvasLeftPx = 120f,
            canvasTopPx = 100f,
            canvasRightPx = 460f,
            canvasBottomPx = 360f,
        )!!
        assertEquals(120f, clipped.leftPx, 0.001f)
        assertEquals(100f, clipped.topPx, 0.001f)
        assertEquals(460f, clipped.rightPx, 0.001f)
        assertEquals(360f, clipped.bottomPx, 0.001f)
    }

}
