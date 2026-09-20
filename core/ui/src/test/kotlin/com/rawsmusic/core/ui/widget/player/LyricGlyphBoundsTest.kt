package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricGlyphBoundsTest {
    @Test fun narrowNeighbourDoesNotSplitWideGlyph() {
        val slices = LyricFloatUpSpec.buildGlyphSlicesFromBounds(listOf(0f to 40f, 40f to 50f), 50f, false)
        assertEquals(40f, slices[0].rightPx, 0f)
        assertEquals(40f, slices[1].leftPx, 0f)
    }

    @Test fun overlappingLigaturesMoveAsOneUnit() {
        val slices = LyricFloatUpSpec.buildGlyphSlicesFromBounds(listOf(0f to 30f, 10f to 30f, 30f to 40f), 40f, false)
        assertEquals(2, slices.size)
        assertEquals(30f, slices[0].rightPx, 0f)
    }

    @Test fun rtlKeepsPhysicalCutsAndReversesLogicalCentres() {
        val slices = LyricFloatUpSpec.buildGlyphSlicesFromBounds(listOf(40f to 50f, 0f to 40f), 50f, true)
        assertEquals(40f, slices[0].rightPx, 0f)
        assertEquals(30f, slices[0].logicalCenterPx, 0f)
        assertEquals(5f, slices[1].logicalCenterPx, 0f)
    }
}
