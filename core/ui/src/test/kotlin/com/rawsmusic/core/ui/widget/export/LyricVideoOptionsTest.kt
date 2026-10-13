package com.rawsmusic.core.ui.widget.export

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricVideoOptionsTest {
    @Test fun fourKPortraitUsesNative2160ShortEdge() {
        assertEquals(LyricVideoSize(2160, 3840), lyricVideoSizeFor(LyricVideoAspect.PORTRAIT, 2160))
    }

    @Test fun twoKLandscapeUsesNative1440ShortEdge() {
        assertEquals(LyricVideoSize(2560, 1440), lyricVideoSizeFor(LyricVideoAspect.LANDSCAPE, 1440))
    }

    @Test fun squareKeepsBothEdgesEqual() {
        assertEquals(LyricVideoSize(2160, 2160), lyricVideoSizeFor(LyricVideoAspect.SQUARE, 2160))
    }
}
