package com.rawsmusic.core.ui.scene.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTabletCarouselGeometryTest {
    @Test fun sideCoversShareNearCentrePlaneWithOnlySubtleRecession() {
        assertEquals(0.62f, tabletCarouselPlaneScale(0f, 0.62f), 0.00001f)
        assertEquals(0.5828f, tabletCarouselPlaneScale(1f, 0.62f), 0.00001f)
        assertEquals(0.558f, tabletCarouselPlaneScale(4f, 0.62f), 0.00001f)
        assertEquals(tabletCarouselPlaneScale(1f, 0.62f), tabletCarouselPlaneScale(-1f, 0.62f), 0f)
        assertTrue(kotlin.math.abs(tabletCarouselPlaneScale(0.999f, 0.62f) -
            tabletCarouselPlaneScale(1.001f, 0.62f)) < 0.001f)
    }
    @Test fun configuredRailPositionsAreSymmetricAndOuterNeighboursUseEqualSpacing() {
        val centers = (-4..4).map { tabletCarouselTranslationX(it.toFloat(), 1600f, 700f, 24f) }
        assertEquals(0f, centers[4], 0f)
        assertEquals(362f, centers[5], 0.001f)
        for (index in 5..7) assertEquals(124f, centers[index + 1] - centers[index], 0.001f)
        assertEquals(-centers[0], centers[8], 0f)
    }
    @Test fun bufferIsHiddenUntilItMovesIntoVisibleRailWithoutRebaseJump() {
        assertEquals(11, homeCarouselLaneRadius(true) * 2 + 1)
        assertEquals(7, homeCarouselLaneRadius(false) * 2 + 1)
        assertEquals(0f, tabletCarouselLaneAlpha(5f), 0f)
        assertEquals(0.5f, tabletCarouselLaneAlpha(4.5f), 0.001f)
        assertEquals(1f, tabletCarouselLaneAlpha(4f), 0f)
        assertEquals(734f, tabletCarouselTranslationX(5f - 1f, 1600f, 700f, 24f), 0.001f)
        assertEquals(-181f, tabletCarouselTranslationX(-0.5f, 1600f, 700f, 24f), 0.001f)
    }
    @Test fun confirmedMissingArtworkUsesDefaultButLoadingAndRealArtworkDoNot() {
        assertTrue(homeCarouselUsesDefaultArtwork(true, false, true))
        assertEquals(false, homeCarouselUsesDefaultArtwork(true, true, true))
        assertEquals(false, homeCarouselUsesDefaultArtwork(true, false, false))
        assertEquals(false, homeCarouselUsesDefaultArtwork(false, false, true))
    }
}
