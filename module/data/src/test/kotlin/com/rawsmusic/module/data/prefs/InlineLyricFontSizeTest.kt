package com.rawsmusic.module.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineLyricFontSizeTest {
    @Test fun fullLyricSizesAreNotClampedToPreviewSizes() {
        assertEquals(24, normalizeInlineLyricFontSize(24))
        assertEquals(28, normalizeInlineLyricFontSize(28))
        assertEquals(40, normalizeInlineLyricFontSize(40))
    }

    @Test fun invalidStoredSizeUsesReadableDefault() {
        assertEquals(28, normalizeInlineLyricFontSize(-1))
        assertEquals(28, normalizeInlineLyricFontSize(200))
    }
}
