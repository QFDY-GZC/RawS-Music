package com.rawsmusic.core.ui.widget.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeTextVerticalLayoutTest {
    @Test
    fun descenderIsPulledInsideFixedCanvas() {
        val layout = resolveNativeTextVerticalLayout(
            containerTop = 0f,
            containerBottom = 29f,
            canvasTop = 0f,
            canvasBottom = 29f,
            ascent = -23f,
            descent = 6f,
            fontTop = -27f,
            fontBottom = 7f,
            inkTop = -21f,
            inkBottom = 7f,
            antiAliasSlackPx = 1f,
        )
        assertEquals(21.5f, layout.baseline)
        assertTrue(layout.baseline + 7f <= 28.5f)
        assertEquals(0f, layout.clipTop)
        assertEquals(29f, layout.clipBottom)
    }

    @Test
    fun fittingTextKeepsExistingAscentDescentCentering() {
        val layout = resolveNativeTextVerticalLayout(
            containerTop = 10f,
            containerBottom = 34f,
            canvasTop = 0f,
            canvasBottom = 80f,
            ascent = -16f,
            descent = 4f,
            fontTop = -18f,
            fontBottom = 5f,
            inkTop = -15f,
            inkBottom = 4f,
        )
        assertEquals(28f, layout.baseline)
    }

    @Test
    fun virtualListSceneRectDoesNotBecomeVerticalClipOwner() {
        val layout = resolveNativeTextVerticalLayout(
            containerTop = 40f,
            containerBottom = 58f,
            canvasTop = 0f,
            canvasBottom = 96f,
            ascent = -17f,
            descent = 5f,
            fontTop = -20f,
            fontBottom = 6f,
            inkTop = -16f,
            inkBottom = 6f,
        )
        assertEquals(0f, layout.clipTop)
        assertEquals(96f, layout.clipBottom)
        assertTrue(layout.baseline + 6f < 96f)
    }
}
