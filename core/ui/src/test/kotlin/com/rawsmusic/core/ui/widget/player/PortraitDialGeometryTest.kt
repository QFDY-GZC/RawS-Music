package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitDialGeometryTest {
    @Test
    fun nineLanesShareOneVerticalAxisAndRemainOrdered() {
        val transforms = (-PORTRAIT_DIAL_VISIBLE_RADIUS..PORTRAIT_DIAL_VISIBLE_RADIUS).map { lane ->
            resolvePortraitDialLaneTransform(lane.toFloat(), 1080f, 2400f)
        }
        val centre = transforms[PORTRAIT_DIAL_VISIBLE_RADIUS]

        assertEquals(9, transforms.size)
        assertEquals(1f, centre.scale, 0.0001f)
        assertEquals(1f, centre.alpha, 0.0001f)
        transforms.forEach { transform ->
            assertEquals(0f, transform.translationXPx, 0.0001f)
            assertEquals(0f, transform.rotationZ, 0.0001f)
        }
        for (distance in 1..PORTRAIT_DIAL_VISIBLE_RADIUS) {
            val near = transforms[PORTRAIT_DIAL_VISIBLE_RADIUS + distance - 1]
            val outer = transforms[PORTRAIT_DIAL_VISIBLE_RADIUS + distance]
            assertTrue(outer.scale < near.scale)
            assertTrue(outer.alpha < near.alpha)
        }
    }

    @Test
    fun centreCardLeavesRoomForNinePortraitLanes() {
        val metrics = resolvePortraitDialMetrics(1080f, 2400f)
        val outer = resolvePortraitDialLaneTransform(4f, 1080f, 2400f)
        val outerBottom = 2400f * 0.5f + outer.translationYPx +
            metrics.cardSidePx * outer.scale * 0.5f

        assertTrue(metrics.cardSidePx <= 1080f * 0.58f + 0.001f)
        assertTrue(outerBottom < 2400f * 0.88f)
    }


    @Test
    fun renderedCentreMatchesTransitionAndTapTargetCentre() {
        val width = 1080f
        val height = 2400f
        val metrics = resolvePortraitDialMetrics(width, height)
        val centre = resolvePortraitDialLaneTransform(0f, width, height)

        assertEquals(metrics.centerXPx, width * 0.5f + centre.translationXPx, 0.001f)
        assertEquals(metrics.centerYPx, height * 0.5f + centre.translationYPx, 0.001f)
    }


    @Test
    fun homeDialSourceBoundsUseTheSameCentreAsTheFullScreenTarget() {
        val containerLeft = 80f
        val containerTop = 420f
        val width = 920f
        val height = 680f
        val metrics = resolvePortraitDialMetrics(width, height)
        val bounds = resolvePortraitDialCardBoundsInRoot(containerLeft, containerTop, width, height)

        assertEquals(containerLeft + metrics.centerXPx, bounds.leftPx + bounds.widthPx * 0.5f, 0.001f)
        assertEquals(containerTop + metrics.centerYPx, bounds.topPx + bounds.heightPx * 0.5f, 0.001f)
        assertEquals(metrics.cardSidePx, bounds.widthPx, 0.001f)
    }

    @Test
    fun farLaneTravelKeepsNineVisibleCardsInsidePreloadedBuffer() {
        assertEquals(8, PORTRAIT_DIAL_RENDER_RADIUS)
        val visibleAtFarCommit = (0..PORTRAIT_DIAL_RENDER_RADIUS).map { logicalOffset ->
            resolvePortraitDialLaneTransform(logicalOffset.toFloat() - 4f, 1080f, 2400f)
        }.filter { it.alpha > 0f }

        assertEquals(9, visibleAtFarCommit.size)
        assertEquals(0f, resolvePortraitDialLaneTransform(5f, 1080f, 2400f).alpha, 0.0001f)
    }

    @Test
    fun virtualCentreRemainsContinuousAcrossQueueWrap() {
        assertEquals(12, nearestPortraitDialVirtualIndex(9, 2, 10))
        assertEquals(-2, nearestPortraitDialVirtualIndex(1, 8, 10))
    }



    @Test
    fun sceneLaneRevealTravelsOutFromCentreAlongDepthTrack() {
        val hidden = resolvePortraitDialSceneLaneTransform(
            position = 3f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            revealProgress = 0f,
        )
        val shown = resolvePortraitDialSceneLaneTransform(
            position = 3f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            revealProgress = 1f,
        )
        val metrics = resolvePortraitDialMetrics(1080f, 2400f)
        val centreTranslationY = metrics.centerYPx - 1200f

        assertEquals(centreTranslationY, hidden.translationYPx, 0.001f)
        assertEquals(0f, hidden.alpha, 0.001f)
        assertTrue(shown.translationYPx > hidden.translationYPx)
        assertTrue(shown.scale < 1f)
        assertTrue(kotlin.math.abs(shown.rotationX) > 0f)
    }
    @Test
    fun fullscreenOuterLanesHaveMoreSeparationThanHomeDial() {
        val height = 2400f
        val homeNear = resolvePortraitDialLaneTransform(1f, 1080f, height)
        val fullNear = resolvePortraitDialFullscreenLaneTransform(1f, 1080f, height)
        val homeOuter = resolvePortraitDialLaneTransform(4f, 1080f, height)
        val fullOuter = resolvePortraitDialFullscreenLaneTransform(4f, 1080f, height)

        assertEquals(homeNear.translationYPx, fullNear.translationYPx, 0.001f)
        assertTrue(fullOuter.translationYPx > homeOuter.translationYPx)
        assertEquals(homeOuter.scale, fullOuter.scale, 0.001f)
        assertEquals(homeOuter.alpha, fullOuter.alpha, 0.001f)
    }


}
