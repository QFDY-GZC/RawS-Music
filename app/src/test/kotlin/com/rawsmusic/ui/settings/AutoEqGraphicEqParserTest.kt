package com.rawsmusic.ui.settings

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqGraphicEqParserTest {
    @Test
    fun parsesGraphicEqAndSamplesLogFrequencyCurve() {
        val parsed = parseAutoEqGraphicEq(
            "\uFEFFGraphicEQ: 20 -6.0; 100 -3.0; 1000 0.0; 10000 3.0; 20000 2.0;"
        )
        assertNotNull(parsed)
        parsed!!
        assertEquals(5, parsed.points.size)

        val preset = parsed.toGraphicEqPreset(10, "Imported")
        assertEquals(10, preset.bandCount)
        assertEquals(10, preset.gains.size)
        assertTrue(preset.gains.first() <= -5.5f)
        assertTrue(preset.gains.last() >= 1.5f)
    }

    @Test
    fun rejectsParametricAndMalformedGraphicText() {
        assertEquals(null, parseAutoEqGraphicEq("Filter 1: ON PK Fc 100 Hz Gain 2 dB Q 1.0"))
        assertEquals(null, parseAutoEqGraphicEq("GraphicEQ: nope"))
    }

    @Test
    fun repeatedFrequencyUsesLastPoint() {
        val parsed = parseAutoEqGraphicEq("GraphicEQ: 20 -2; 100 -1; 100 1; 20000 2")!!
        val hundred = parsed.points.first { abs(it.frequencyHz - 100f) < 0.01f }
        assertEquals(1f, hundred.gainDb, 0.0001f)
    }
}
