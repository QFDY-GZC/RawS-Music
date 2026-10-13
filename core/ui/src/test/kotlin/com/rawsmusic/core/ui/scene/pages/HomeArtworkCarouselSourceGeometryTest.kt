package com.rawsmusic.core.ui.scene.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeArtworkCarouselSourceGeometryTest {
    @Test fun reflectionCropNeverHasZeroHeightForExtremeAspectRatios() {
        val crop = requireNotNull(resolveHomeCanvasBitmapCrop(1200, 1200, 100000f, 1f))
        assertEquals(1, crop.bottom - crop.top)
    }
    @Test fun unlaidOutCanvasHasNoBitmapCrop() {
        assertNull(resolveHomeCanvasBitmapCrop(1200, 1200, 0f, 0f))
        assertNull(resolveHomeCanvasBitmapCrop(1200, 1200, Float.NaN, 10f))
    }
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
    fun `wide cover fits the actual header instead of clipping its lower half`() {
        val bounds = requireNotNull(
            resolveHomeCanvasCenterArtworkLocalBounds(
                canvasWidthPx = 500f,
                canvasHeightPx = 300f,
            )
        )

        assertEquals(138.4f, bounds.leftPx, 0.01f)
        assertEquals(68.4f, bounds.topPx, 0.01f)
        assertEquals(361.6f, bounds.rightPx, 0.01f)
        assertEquals(291.6f, bounds.bottomPx, 0.01f)
        assertEquals(223.2f, bounds.heightPx, 0.01f)
    }

    @Test
    fun `tablet width cannot enlarge the cover beyond the available height`() {
        val bounds = requireNotNull(resolveHomeCanvasCenterArtworkLocalBounds(2000f, 680f))
        assertEquals(505.92f, bounds.widthPx, 0.02f)
        assertEquals(505.92f, bounds.heightPx, 0.02f)
        assertEquals(155.04f, bounds.topPx, 0.02f)
        assertEquals(660.96f, bounds.bottomPx, 0.02f)
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
