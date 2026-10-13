package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPopupSizingTest {
    @Test fun standardPopupUsesExistingViewportHeight() {
        assertEquals(740f, playerPopupTargetHeightPx(1000f, null), 0.001f)
    }

    @Test fun lyricPopupUsesCompactHeight() {
        assertEquals(250f, playerPopupTargetHeightPx(1000f, 250f), 0.001f)
    }

    @Test fun compactHeightNeverExceedsAvailableScreen() {
        assertEquals(148f, playerPopupTargetHeightPx(200f, 250f), 0.001f)
    }
}
