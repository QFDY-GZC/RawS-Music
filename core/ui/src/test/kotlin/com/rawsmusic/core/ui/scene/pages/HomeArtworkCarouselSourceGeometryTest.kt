package com.rawsmusic.core.ui.scene.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeArtworkCarouselSourceGeometryTest {
    @Test
    fun `settled canvas centre cover matches renderer geometry`() {
        val bounds = requireNotNull(
            resolveHomeCanvasCenterArtworkLocalBounds(
                canvasWidthPx = 392f,
                canvasHeightPx = 340f,
            )
        )

        assertEquals(74.48f, bounds.leftPx, 0.01f)
        assertEquals(74.48f, bounds.topPx, 0.01f)
        assertEquals(317.52f, bounds.rightPx, 0.01f)
        assertEquals(317.52f, bounds.bottomPx, 0.01f)
        assertEquals(243.04f, bounds.widthPx, 0.01f)
        assertEquals(243.04f, bounds.heightPx, 0.01f)
    }

    @Test
    fun `cover clips to actual fixed header height`() {
        val bounds = requireNotNull(
            resolveHomeCanvasCenterArtworkLocalBounds(
                canvasWidthPx = 500f,
                canvasHeightPx = 300f,
            )
        )

        assertEquals(95f, bounds.leftPx, 0.01f)
        assertEquals(95f, bounds.topPx, 0.01f)
        assertEquals(405f, bounds.rightPx, 0.01f)
        assertEquals(300f, bounds.bottomPx, 0.01f)
        assertEquals(205f, bounds.heightPx, 0.01f)
    }

    @Test
    fun `invalid canvas has no launch bounds`() {
        assertNull(resolveHomeCanvasCenterArtworkLocalBounds(0f, 340f))
        assertNull(resolveHomeCanvasCenterArtworkLocalBounds(392f, 1f))
    }
    @Test
    fun `source corner matches the actually rendered home style`() {
        assertEquals(12.96f, resolveHomeArtworkSourceCornerRadiusDp(
            HomeArtworkCarouselStyle.CurrentCarousel
        ), 0.001f)
        assertEquals(20f, resolveHomeArtworkSourceCornerRadiusDp(
            HomeArtworkCarouselStyle.VerticalDial
        ), 0.001f)
    }

}
