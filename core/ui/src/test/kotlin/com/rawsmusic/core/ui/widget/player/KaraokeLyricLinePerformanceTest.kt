package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeLyricLinePerformanceTest {
    @Test
    fun liftUsesTenPercentPeakAndZeroWhenDisabled() {
        assertEquals(10f, LyricFloatUpSpec.peakLiftPx(100f), 0f)
        assertEquals(
            0f,
            LyricFloatUpSpec.peakLiftPx(
                textScalePx = 100f,
                percentage = LyricFloatUpSpec.DISABLED_PERCENTAGE,
            ),
            0f,
        )
    }

    @Test
    fun liftIsSpatialHalfCosineInsteadOfPerGlyphTween() {
        val textScale = 20f
        val window = textScale * LyricFloatUpSpec.WINDOW_TEXT_SCALES
        val cursor = 100f
        val centerAtLeftEdge = cursor - window * 0.5f
        val centerAtWindowMiddle = cursor
        val centerAtRightEdge = cursor + window * 0.5f

        assertEquals(1f, LyricFloatUpSpec.spatialFraction(centerAtLeftEdge, cursor, textScale), 0.0001f)
        assertEquals(0.5f, LyricFloatUpSpec.spatialFraction(centerAtWindowMiddle, cursor, textScale), 0.0001f)
        assertEquals(0f, LyricFloatUpSpec.spatialFraction(centerAtRightEdge, cursor, textScale), 0.0001f)
    }

    @Test
    fun finalWordSettlesUpAsCursorReachesLineEnd() {
        val textScale = 20f
        val lineEnd = 100f
        val sampleCenter = 95f
        val beforeSettle = LyricFloatUpSpec.spatialFraction(
            centerPx = sampleCenter,
            cursorPx = 70f,
            textScalePx = textScale,
            lineEndPx = lineEnd,
        )
        val atEnd = LyricFloatUpSpec.spatialFraction(
            centerPx = sampleCenter,
            cursorPx = lineEnd,
            textScalePx = textScale,
            lineEndPx = lineEnd,
        )
        assertTrue(atEnd > beforeSettle)
        assertEquals(1f, atEnd, 0.0001f)
    }

    @Test
    fun rewindingBeforeWordStartImmediatelyRemovesLift() {
        val before = LyricFloatUpSpec.wordCenterFraction(
            positionMs = 900f,
            beginMs = 1_000L,
            durationMs = 1_000L,
            measuredWidthPx = 80f,
            textScalePx = 20f,
            settleAtEnd = false,
        )
        val during = LyricFloatUpSpec.wordCenterFraction(
            positionMs = 1_700f,
            beginMs = 1_000L,
            durationMs = 1_000L,
            measuredWidthPx = 80f,
            textScalePx = 20f,
            settleAtEnd = false,
        )
        assertEquals(0f, before, 0f)
        assertTrue(during > 0f)
    }

    @Test
    fun shapedGlyphSlicesPartitionPhysicalWidthAndMirrorLogicalCentersForRtl() {
        val ltr = LyricFloatUpSpec.buildGlyphSlices(
            physicalCentersPx = listOf(10f, 30f, 50f),
            widthPx = 60f,
            rtl = false,
        )
        assertEquals(0f, ltr[0].leftPx, 0f)
        assertEquals(20f, ltr[0].rightPx, 0f)
        assertEquals(40f, ltr[1].rightPx, 0f)
        assertEquals(60f, ltr[2].rightPx, 0f)
        assertEquals(30f, ltr[1].logicalCenterPx, 0f)

        val rtl = LyricFloatUpSpec.buildGlyphSlices(
            physicalCentersPx = listOf(10f, 30f, 50f),
            widthPx = 60f,
            rtl = true,
        )
        assertEquals(50f, rtl[0].logicalCenterPx, 0f)
        assertEquals(30f, rtl[1].logicalCenterPx, 0f)
        assertEquals(10f, rtl[2].logicalCenterPx, 0f)
    }
}
