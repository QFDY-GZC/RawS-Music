package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class PlayerPopupBackPolicyTest {
    @Test fun playlistOwnsBackEvenWhenMoreIsStillVisible() {
        assertFalse(playerPopupCanHandleBack(true, true))
    }

    @Test fun moreResumesOnlyAfterPlaylistHasUnmounted() {
        assertFalse(playerPopupCanHandleBack(true, true))
        assertTrue(playerPopupCanHandleBack(true, false))
    }

    @Test fun hiddenMoreNeverClaimsBack() {
        assertFalse(playerPopupCanHandleBack(false, false))
        assertFalse(playerPopupCanHandleBack(false, true))
    }
}
