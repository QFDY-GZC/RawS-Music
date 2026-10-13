package com.rawsmusic.core.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAdaptiveLayoutPolicyTest {
    @Test fun tabletLandscapeUsesInlineLyricsButPhoneAndTabletPortraitKeepTheirLyricPage() {
        assertTrue(usesInlinePlayerLyrics(720, 1280f, 720f))
        assertFalse(usesInlinePlayerLyrics(412, 915f, 412f))
        assertFalse(usesInlinePlayerLyrics(720, 720f, 1280f))
        assertFalse(usesInlinePlayerLyrics(720, 0f, 0f))
    }
}
