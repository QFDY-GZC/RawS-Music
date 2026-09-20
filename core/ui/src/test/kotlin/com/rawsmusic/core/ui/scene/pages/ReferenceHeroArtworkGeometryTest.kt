package com.rawsmusic.core.ui.scene.pages

import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceHeroArtworkGeometryTest {
    @Test
    fun referenceHeaderArtworkIsEdgeToEdgeAt1080px() {
        assertEquals(1080f, ReferenceHeroArtworkGeometry.visibleSidePx(1080f), 0.001f)
        assertEquals(0f, ReferenceHeroArtworkGeometry.visibleInsetPx(), 0.001f)
    }
}
