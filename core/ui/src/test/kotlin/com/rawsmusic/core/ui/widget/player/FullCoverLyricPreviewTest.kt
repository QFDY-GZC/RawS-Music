package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullCoverLyricPreviewTest {
    @Test
    fun `Chinese primary lyric selects three timed rows`() {
        assertTrue("与你重逢".containsChineseFullCoverLyricText())
        assertEquals(3, fullCoverLyricVisibleRowLimit("与你重逢"))
    }

    @Test
    fun `foreign primary lyric selects two-row presentation`() {
        assertFalse("Here comes the sun".containsChineseFullCoverLyricText())
        assertEquals(2, fullCoverLyricVisibleRowLimit("Here comes the sun"))
    }
}
