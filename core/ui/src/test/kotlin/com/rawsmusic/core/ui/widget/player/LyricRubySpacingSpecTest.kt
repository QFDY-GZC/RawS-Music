package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricRubySpacingSpecTest {
    @Test fun rubySharesTheInternalLineGap() {
        assertEquals(7, LyricRubySpacingSpec.flowGapPx(25, 18))
        assertEquals(25, LyricRubySpacingSpec.flowGapPx(25, 0))
        assertEquals(0, LyricRubySpacingSpec.flowGapPx(6, 18))
    }
    @Test fun annotationConsumesTheLeadingParagraphPaddingOnly() {
        assertEquals(2, LyricRubySpacingSpec.leadingPaddingPx(20, 18))
        assertEquals(20, LyricRubySpacingSpec.leadingPaddingPx(20, 0))
        assertEquals(0, LyricRubySpacingSpec.leadingPaddingPx(12, 18))
    }
}
