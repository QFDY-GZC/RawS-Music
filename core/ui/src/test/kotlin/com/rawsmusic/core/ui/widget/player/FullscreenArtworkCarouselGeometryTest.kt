package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenArtworkCarouselGeometryTest {
    @Test
    fun keepsElevenLogicalLanesButOnlyNineVisibleAtRest() {
        assertEquals(11, FULLSCREEN_CAROUSEL_LANE_COUNT)
        assertEquals(9, FULLSCREEN_CAROUSEL_VISIBLE_LANE_COUNT)
        val metrics = resolveFullscreenArtworkCarouselMetrics(2400f, 1080f)
        val visible = (-FULLSCREEN_CAROUSEL_LANE_RADIUS..FULLSCREEN_CAROUSEL_LANE_RADIUS).count {
            resolveFullscreenArtworkLaneTransform(it.toFloat(), metrics).alpha > 0.001f
        }
        assertEquals(9, visible)
        assertEquals(0f, resolveFullscreenArtworkLaneTransform(-5f, metrics).alpha)
        assertEquals(0f, resolveFullscreenArtworkLaneTransform(5f, metrics).alpha)
    }

    @Test
    fun sideGeometryIsSymmetricAndBufferLaneEntersContinuously() {
        val metrics = resolveFullscreenArtworkCarouselMetrics(2400f, 1080f)
        val left = resolveFullscreenArtworkLaneTransform(-2f, metrics)
        val right = resolveFullscreenArtworkLaneTransform(2f, metrics)
        assertTrue(abs(left.translationX + right.translationX) < 0.01f)
        assertTrue(abs(left.rotationY + right.rotationY) < 0.01f)
        assertTrue(resolveFullscreenArtworkLaneTransform(4.5f, metrics).alpha in 0.34f..0.36f)
    }

    @Test
    fun nearRailKeepsArtworkDetailAndFarRailsCompressNonLinearly() {
        val metrics = resolveFullscreenArtworkCarouselMetrics(2400f, 1080f)
        val laneOne = resolveFullscreenArtworkLaneTransform(1f, metrics)
        val laneTwo = resolveFullscreenArtworkLaneTransform(2f, metrics)
        val laneThree = resolveFullscreenArtworkLaneTransform(3f, metrics)

        assertTrue(laneOne.rotationY in -25f..-23f)
        assertTrue(laneOne.scale in 0.81f..0.83f)
        assertTrue(laneTwo.translationX - laneOne.translationX < laneOne.translationX)
        assertTrue(laneThree.translationX - laneTwo.translationX < laneTwo.translationX - laneOne.translationX)
    }
}
