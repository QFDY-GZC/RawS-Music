package com.rawsmusic.core.ui.widget.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundArtworkSlotTest {
    @Test fun oldPixelsCannotBeUsedForTheNextSongsPalette() {
        val slot = BackgroundArtworkSlot("a", "pixels-a")
        assertNull(slot.forKey("b"))
    }

    @Test fun readyPixelsRemainAvailableForTheirOwnIdentity() {
        assertEquals("pixels-b", BackgroundArtworkSlot("b", "pixels-b").forKey("b"))
    }

    @Test fun missingCurrentPixelsDoNotBorrowThePreviousSongsPixels() {
        assertNull(BackgroundArtworkSlot("b", null as String?).forKey("b"))
        assertNull(BackgroundArtworkSlot("a", "pixels-a").forKey(null))
    }

    @Test fun rapidReverseOnlyAdmitsPixelsBelongingToTheRequestedSong() {
        val slots = listOf(
            BackgroundArtworkSlot("b", "pixels-b"),
            BackgroundArtworkSlot("g", "pixels-g"),
            BackgroundArtworkSlot("h", "pixels-h"),
        )
        assertEquals(listOf(null, null, "pixels-h"), slots.map { it.forKey("h") })
    }
}
