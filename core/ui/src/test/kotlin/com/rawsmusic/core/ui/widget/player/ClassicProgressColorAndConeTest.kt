package com.rawsmusic.core.ui.widget.player

import com.google.android.material.color.utilities.Hct
import org.junit.Assert.*
import org.junit.Test

class ClassicProgressColorAndConeTest {
    @Test fun interruptedHeadShapeUsesOneSharedMorphProgress() {
        val cone = ClassicConeAnimation()
        cone.transition(ClassicConeMode.Playing)
        repeat(10) { cone.advance(1f / 60f) }
        val before = cone.frame()
        cone.transition(ClassicConeMode.Dragging)
        cone.advance(1f / 60f)
        val after = cone.frame()
        val heightProgress = (after.height - before.height) / (0.2f - before.height)
        val coneProgress = (after.coneK - before.coneK) / (0f - before.coneK)
        val widthProgress = (after.w0 - before.w0) / (0.014f - before.w0)
        assertEquals(heightProgress, coneProgress, 0.0001f)
        assertEquals(heightProgress, widthProgress, 0.0001f)
    }

    @Test fun artworkColorsUsePerceptualToneEightyRatherThanAlpha() {
        for (seed in intArrayOf(0xfff87760.toInt(), 0xff3366ee.toInt(), 0xff00aa44.toInt(), -1, 0xff000000.toInt())) {
            val result = classicLightTone80(seed)
            assertEquals(255, result ushr 24)
            assertEquals(80.0, Hct.fromInt(result).tone, 0.35)
        }
    }

    @Test fun achromaticArtworkProducesNeutralToneEighty() {
        assertEquals(0xffc6c6c6.toInt(), classicLightTone80(0xff000000.toInt()))
    }

    @Test fun coneKeepsItsCurrentShapeAcrossPlayDragPauseTransitions() {
        val cone = ClassicConeAnimation()
        cone.transition(ClassicConeMode.Playing)
        repeat(150) { cone.advance(1f / 60f) }
        val playing = cone.frame()
        assertEquals(0.6f, playing.height, 0.0001f)
        assertEquals(0.2f, playing.coneK, 0.0001f)
        cone.transition(ClassicConeMode.Dragging)
        assertEquals(playing, cone.frame())
        repeat(100) { cone.advance(1f / 60f) }
        assertEquals(0f, cone.frame().coneK, 0.0001f)
        assertEquals(0.3f, cone.frame().alpha1, 0.0001f)
        val dragging = cone.frame()
        cone.transition(ClassicConeMode.Off)
        assertEquals(dragging, cone.frame())
        cone.advance(1f / 60f)
        assertTrue(cone.frame().alpha1 > 0f)
        repeat(120) { cone.advance(1f / 60f) }
        assertEquals(0f, cone.frame().alpha1, 0.0001f)
        assertFalse(cone.running)
    }
}
