package com.rawsmusic.core.ui.scene.pages

import androidx.compose.ui.unit.dp
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.resolveRawWindowLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionHeroWindowGeometryTest {
    @Test
    fun compactWindow_keepsLegacyFullHeaderArtwork() {
        val window = resolveRawWindowLayoutProfile(412.dp, 915.dp)
        val geometry = resolveCollectionHeroWindowGeometry(
            scene = NavScene.ALBUM_DETAIL,
            window = window,
            statusBarTop = 24.dp,
        )

        assertFalse(geometry.wide)
        assertEquals(0.dp, geometry.artworkLeft)
        assertEquals(0.dp, geometry.artworkTop)
        assertEquals(window.contentMaxWidth, geometry.artworkWidth)
        assertEquals(260.dp, geometry.headerHeight)
        assertEquals(geometry.headerHeight, geometry.artworkHeight)
    }

    @Test
    fun expandedWindow_usesBoundedSquareArtworkSubRect() {
        val window = resolveRawWindowLayoutProfile(1024.dp, 768.dp)
        val geometry = resolveCollectionHeroWindowGeometry(
            scene = NavScene.ALBUM_DETAIL,
            window = window,
            statusBarTop = 24.dp,
        )

        assertTrue(geometry.wide)
        assertEquals(16.dp, geometry.artworkLeft)
        assertEquals(36.dp, geometry.artworkTop)
        assertEquals(320.dp, geometry.artworkWidth)
        assertEquals(geometry.artworkWidth, geometry.artworkHeight)
        assertEquals(376.dp, geometry.headerHeight)
    }

    @Test
    fun expandedHumanCollection_usesSameStableSquareTarget() {
        val window = resolveRawWindowLayoutProfile(900.dp, 1200.dp)
        val geometry = resolveCollectionHeroWindowGeometry(
            scene = NavScene.ARTIST_DETAIL,
            window = window,
            statusBarTop = 28.dp,
        )

        assertTrue(geometry.wide)
        assertTrue(geometry.artworkWidth in 260.dp..320.dp)
        assertEquals(geometry.artworkWidth, geometry.artworkHeight)
        assertEquals(geometry.artworkTop + geometry.artworkHeight + 20.dp, geometry.headerHeight)
    }
}
