package com.rawsmusic.core.ui.scene.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFontScaleGeometryTest {
    @Test
    fun oneXKeepsExistingHomeHolderGeometry() {
        assertEquals(54f, homeSectionHeaderHeightDp(1f), 0.001f)
        assertEquals(82f, homeCardRowExtraHeightDp(1f), 0.001f)
        assertEquals(74f, homeMostPlayedContentHeightDp(1f), 0.001f)
        assertEquals(82f, homeMostPlayedHolderHeightDp(1f), 0.001f)
    }

    @Test
    fun enlargedFontsGrowEveryTextBoundHolder() {
        val scale = 1.69f
        assertTrue(homeSectionHeaderHeightDp(scale) > homeSectionHeaderHeightDp(1f))
        assertTrue(homeCardRowExtraHeightDp(scale) > homeCardRowExtraHeightDp(1f))
        assertTrue(homeMostPlayedContentHeightDp(scale) > homeMostPlayedContentHeightDp(1f))
        assertTrue(homeMostPlayedHolderHeightDp(scale) > homeMostPlayedHolderHeightDp(1f))
    }

    @Test
    fun smallerFontScaleDoesNotShrinkExistingTouchAndSpacingGeometry() {
        assertEquals(54f, homeSectionHeaderHeightDp(0.8f), 0.001f)
        assertEquals(82f, homeCardRowExtraHeightDp(0.8f), 0.001f)
        assertEquals(74f, homeMostPlayedContentHeightDp(0.8f), 0.001f)
    }
}
