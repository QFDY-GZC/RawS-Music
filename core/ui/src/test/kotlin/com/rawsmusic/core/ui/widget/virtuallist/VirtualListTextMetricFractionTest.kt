package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualListTextMetricFractionTest {
    @Test fun increasingAndDecreasingSizesKeepEndpointDirection() {
        assertEquals(0.25f, virtualListTextMetricFraction(15f, 14f, 18f), 0f)
        assertEquals(0.75f, virtualListTextMetricFraction(15f, 18f, 14f), 0f)
    }
    @Test fun unchangedSizeAndElasticOvershootRemainFinite() {
        assertEquals(0f, virtualListTextMetricFraction(14f, 14f, 14f), 0f)
        assertEquals(0f, virtualListTextMetricFraction(12f, 14f, 18f), 0f)
        assertEquals(1f, virtualListTextMetricFraction(20f, 14f, 18f), 0f)
    }

    @Test fun transitionTextUsesOneEndpointRasterSizeAndScalesTheGlyphRun() {
        assertEquals(18f, virtualListTransitionRasterTextSize(12f, 18f), 0f)
        assertEquals(12f / 18f, virtualListTransitionGlyphScale(12f, 18f), 0.0001f)
        assertEquals(1f, virtualListTransitionGlyphScale(18f, 18f), 0f)
    }

    @Test fun sceneMotionDoesNotRerecordGlyphRaster() {
        assertEquals(
            false,
            virtualListTextNeedsRasterRecord(
                textChanged = false,
                rasterSizeChanged = false,
                typefaceChanged = false,
                colorChanged = false,
                leftInsetChanged = false,
                ellipsizeChanged = false,
                widthChanged = true,
                ellipsize = false,
            ),
        )
        assertEquals(
            true,
            virtualListTextNeedsRasterRecord(
                textChanged = false,
                rasterSizeChanged = false,
                typefaceChanged = false,
                colorChanged = false,
                leftInsetChanged = false,
                ellipsizeChanged = false,
                widthChanged = true,
                ellipsize = true,
            ),
        )
        assertEquals(
            true,
            virtualListTextNeedsRasterRecord(
                textChanged = true,
                rasterSizeChanged = false,
                typefaceChanged = false,
                colorChanged = false,
                leftInsetChanged = false,
                ellipsizeChanged = false,
                widthChanged = false,
                ellipsize = false,
            ),
        )
    }
}
