package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumArtTiersVirtualListTest {
    private fun resolveList(side: Int): AlbumArtTiers.Target = AlbumArtTiers.resolve(
        requestedWidth = side,
        requestedHeight = side,
        allowHiRes = false,
        priority = BitmapRequest.Priority.LOADING_LIST,
        highResEnabled = true,
    )

    @Test
    fun denseGridAttachedCoverUses256LowWrapper() {
        assertEquals(256, resolveList(220).maxSide)
        assertEquals(256, resolveList(256).maxSide)
    }

    @Test
    fun grid3RangeKeeps384Tier() {
        assertEquals(384, resolveList(257).maxSide)
        assertEquals(384, resolveList(320).maxSide)
        assertEquals(384, resolveList(383).maxSide)
    }

    @Test
    fun smallAndLargeListRulesStayUnchanged() {
        assertEquals(128, resolveList(128).maxSide)
        assertEquals(192, resolveList(192).maxSide)
        assertEquals(512, resolveList(512).maxSide)
        assertEquals(768, resolveList(768).maxSide)
    }

    @Test
    fun playbackTierIsUnaffected() {
        val target = AlbumArtTiers.resolve(
            requestedWidth = 768,
            requestedHeight = 768,
            allowHiRes = true,
            priority = BitmapRequest.Priority.LOADING_WIDGET,
            highResEnabled = true,
        )
        assertEquals(1024, target.maxSide)
    }
}
