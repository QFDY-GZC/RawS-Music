package com.rawsmusic.core.ui.scene.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderHierarchyCoverEndpointTest {
    @Test fun headerMeasurementAlreadyCarriesUnscaledSize() {
        // CollectionHero supplies positionInWindow plus coordinates.size (local pixels).
        for (scale in listOf(0.5f, 1.5f)) {
            val x = 500f + (20f - 500f) * scale
            val y = 900f + (40f - 900f) * scale
            val measured = FolderHierarchyCoverEndpoint(x, y, x + 1000f, y + 1000f, 16f, "cover", 1L)
            val canonical = measured.unscaledAround(scale, 500f, 900f)
            assertEquals(20f, canonical.left, 0.001f)
            assertEquals(40f, canonical.top, 0.001f)
            assertEquals(1000f, canonical.width, 0.001f)
            assertEquals(1000f, canonical.height, 0.001f)
        }
    }
}
