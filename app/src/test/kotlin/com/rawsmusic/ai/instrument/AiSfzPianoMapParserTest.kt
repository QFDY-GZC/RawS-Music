package com.rawsmusic.ai.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiSfzPianoMapParserTest {
    @Test
    fun parsesVelocityGroupsAndConvertsLoopEndToExclusive() {
        val sfz = """
            <global> ampeg_release=0.4
            <group> lovel=1 hivel=80 loop_mode=loop_continuous
            <region> sample=samples/A0vL.flac key=21 pitch_keycenter=21 loop_start=100 loop_end=999
            <group> lovel=81 hivel=127 loop_mode=no_loop
            <region> sample=samples/A0vH.flac lokey=21 hikey=23 pitch_keycenter=21
        """.trimIndent()

        val regions = AiSfzPianoMapParser.parse(sfz)
        assertEquals(2, regions.size)
        assertEquals("samples/A0vL.flac", regions[0].samplePath)
        assertEquals(1, regions[0].velocityMin)
        assertEquals(80, regions[0].velocityMax)
        assertEquals(100L, regions[0].loopStartFrame)
        assertEquals(1000L, regions[0].loopEndFrameExclusive)
        assertEquals(81, regions[1].velocityMin)
        assertEquals(127, regions[1].velocityMax)
        assertNull(regions[1].loopStartFrame)
        assertNull(regions[1].loopEndFrameExclusive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeSamplePath() {
        AiSfzPianoMapParser.parse("<region> sample=../escape.flac key=60")
    }
}
