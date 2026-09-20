package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricZoomTransitionStateTest {
    @Test
    fun `pinch owns one full Reference scene pair and keeps frozen anchor`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 900f)
        state.updateRowWindowBounds(10, 200f, 260f)
        state.updateRowWindowBounds(11, 300f, 360f)
        state.updateRowWindowBounds(12, 400f, 460f)

        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        val frozenCenter = state.frozenAnchorCenterYPx()
        state.updatePinch(rawScale = 112f)

        // Reference lyric zoom uses the discrete normal -> zoomed scene pair for the gesture.
        assertEquals(100, state.segmentSourceScalePercent)
        assertEquals(130, state.segmentTargetScalePercent)
        assertEquals(0.4f, state.currentSegmentProgress(), 0.001f)
        assertEquals(frozenCenter, state.frozenAnchorCenterYPx(), 0.001f)
    }

    @Test
    fun `measured zoomed endpoint creates leaving holder fade group`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 500f)
        state.updateRowWindowBounds(10, 120f, 180f)
        state.updateRowWindowBounds(11, 220f, 280f)
        state.updateRowWindowBounds(12, 320f, 380f)
        state.updateRowWindowBounds(13, 420f, 480f)
        // Runway row, not attached at capture.
        state.updateRowWindowBounds(14, 520f, 580f)
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        state.updatePinch(rawScale = 115f) // 50% normal -> zoomed

        val runway = listOf(10, 11, 12, 13, 14)
        state.setLayoutExpectedIndices(130, runway)
        state.updateLayoutSceneWindowBounds(
            130,
            mapOf(
                // Row 10 was source-visible but is outside the larger target scene.
                10 to LyricZoomRowBounds(-40f, 70f),
                11 to LyricZoomRowBounds(190f, 300f),
                12 to LyricZoomRowBounds(320f, 430f),
                13 to LyricZoomRowBounds(455f, 565f),
                14 to LyricZoomRowBounds(590f, 700f),
            ),
        )
        assertTrue(state.layoutReady(130))
        state.prepareTransitionFrame(runway)

        assertEquals(LyricZoomRowOwnership.SOURCE_ONLY, state.rowOwnership(10))
        assertEquals(LyricZoomRowOwnership.SHARED, state.rowOwnership(11))
        val leaving = state.singleHolderTransform(10, runway)
        assertTrue(leaving.alpha in 0.45f..0.55f)
        // The full-size leaving holder is moved by the contracting stack instead of popping away.
        assertTrue(kotlin.math.abs(leaving.translationYPx) > 0.01f)
    }

    @Test
    fun `measured small endpoint creates entering holder fade group`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 500f)
        state.updateRowWindowBounds(9, 20f, 80f) // runway, outside capture viewport
        state.updateRowWindowBounds(10, 120f, 180f)
        state.updateRowWindowBounds(11, 220f, 280f)
        state.updateRowWindowBounds(12, 320f, 380f)
        state.updateRowWindowBounds(13, 420f, 480f)
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        state.updatePinch(rawScale = 87.5f) // 50% normal -> small

        val runway = listOf(9, 10, 11, 12, 13)
        state.setLayoutExpectedIndices(75, runway)
        state.updateLayoutSceneWindowBounds(
            75,
            mapOf(
                // Smaller scene makes row 9 newly visible above the captured source set.
                9 to LyricZoomRowBounds(105f, 150f),
                10 to LyricZoomRowBounds(165f, 210f),
                11 to LyricZoomRowBounds(225f, 270f),
                12 to LyricZoomRowBounds(285f, 330f),
                13 to LyricZoomRowBounds(345f, 390f),
            ),
        )
        assertTrue(state.layoutReady(75))
        state.prepareTransitionFrame(runway)

        assertEquals(LyricZoomRowOwnership.TARGET_ONLY, state.rowOwnership(9))
        val entering = state.singleHolderTransform(9, runway)
        assertTrue(entering.alpha in 0.45f..0.55f)
        assertTrue(kotlin.math.abs(entering.translationYPx) > 0.01f)
    }

    @Test
    fun `handoff uses selected full scene and clears temporary owner`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 900f)
        state.updateRowWindowBounds(10, 200f, 260f)
        state.updateRowWindowBounds(11, 300f, 360f)
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        state.updatePinch(112f)
        state.beginSettling(130)
        state.setSettleFraction(1f)
        assertEquals(1f, state.currentSegmentProgress(), 0.001f)

        state.prepareTargetLayoutHandoff(130)
        assertTrue(state.handoffPrepared)
        state.cancel()
        assertFalse(state.active)
        assertFalse(state.handoffPrepared)
    }

    @Test
    fun `release progress can be driven directly from the latched finger frame`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 900f)
        state.updateRowWindowBounds(10, 200f, 260f)
        state.updateRowWindowBounds(11, 300f, 360f)
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        state.updatePinch(109f)
        state.beginSettling(130)
        assertEquals(0.30f, state.currentSegmentProgress(), 0.001f)
        state.setSettledProgress(0.75f)
        assertEquals(0.75f, state.currentSegmentProgress(), 0.001f)
    }

    @Test
    fun `capture snapshot is immediately ready while target scene measures`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 900f)
        state.updateRowWindowBounds(10, 200f, 260f)
        state.updateRowWindowBounds(11, 300f, 360f)
        state.updateRowWindowBounds(12, 400f, 460f)

        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        assertTrue(state.layoutReady(100))
        state.updatePinch(rawScale = 103f)
        assertEquals(100, state.segmentSourceScalePercent)
        assertEquals(130, state.segmentTargetScalePercent)
        assertFalse(state.layoutReady(130))
        // Until target geometry is complete, the live source layout remains the visible owner.
        assertFalse(state.transitionOwnsText())

        val before = state.capturedHolderTransform(10, baseFontSizeSp = 32, primaryFontSizeRange = 24..44)
        state.updatePinch(rawScale = 106f)
        val after = state.capturedHolderTransform(10, baseFontSizeSp = 32, primaryFontSizeRange = 24..44)
        assertTrue(after.scale > before.scale)
        assertTrue(kotlin.math.abs(after.translationYPx - before.translationYPx) > 0.01f)
    }

    @Test
    fun `gesture capture derives visible holders directly from viewport geometry`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 900f)
        state.updateRowWindowBounds(1, -300f, -200f)
        state.updateRowWindowBounds(10, 220f, 280f)
        state.updateRowWindowBounds(11, 320f, 380f)
        state.updateRowWindowBounds(99, 1_200f, 1_260f)

        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        assertTrue(state.layoutReady(100))
        assertEquals(11, state.frozenAnchorIndex())
        assertTrue(state.layoutRowBounds(100, 10) != null)
        assertTrue(state.layoutRowBounds(100, 11) != null)
        assertTrue(state.layoutRowBounds(100, 1) == null)
        assertTrue(state.layoutRowBounds(100, 99) == null)
    }
    @Test
    fun `captured live holder starts leaving fade before target measure is ready`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 500f)
        state.updateRowWindowBounds(10, 80f, 130f)
        state.updateRowWindowBounds(11, 220f, 280f)
        state.updateRowWindowBounds(12, 340f, 400f)
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))

        // Halfway to the real 130% scene. Row 10 projects fully above the viewport at the
        // endpoint, so Reference-style source ownership must already be fading even though the
        // hidden target layout has not reported any geometry yet.
        val before = state.capturedHolderTransform(
            index = 10,
            baseFontSizeSp = 28,
            primaryFontSizeRange = 24..40,
        )
        state.updatePinch(rawScale = 115f)
        assertFalse(state.layoutReady(130))
        val transform = state.capturedHolderTransform(
            index = 10,
            baseFontSizeSp = 28,
            primaryFontSizeRange = 24..40,
        )
        assertTrue(transform.alpha < before.alpha)
    }

    @Test
    fun `single holder keeps damped edge motion after full zoom scene is reached`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 700f)
        state.updateRowWindowBounds(10, 180f, 240f)
        state.updateRowWindowBounds(11, 300f, 360f)
        state.updateRowWindowBounds(12, 420f, 480f)
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        val runway = listOf(10, 11, 12)
        state.setLayoutExpectedIndices(130, runway)
        state.updateLayoutSceneWindowBounds(
            130,
            mapOf(
                10 to LyricZoomRowBounds(140f, 220f),
                11 to LyricZoomRowBounds(290f, 370f),
                12 to LyricZoomRowBounds(440f, 520f),
            ),
        )
        state.updatePinch(130f)
        state.prepareTransitionFrame(runway)
        val atEndpoint = state.singleHolderTransform(12, runway)

        // Raw scale above 130 carries scaleGestureOwner-style damped overshoot. The logical scene remains 130,
        // but the attached holder must still move/scale under the fingers.
        state.updatePinch(138f)
        val beyondEndpoint = state.singleHolderTransform(12, runway)
        assertTrue(beyondEndpoint.scale > atEndpoint.scale)
        assertTrue(kotlin.math.abs(beyondEndpoint.translationYPx - atEndpoint.translationYPx) > 0.01f)
    }

    @Test
    fun `idle prewarmed target is ready on the first pinch frame`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 500f)
        state.updateRowWindowBounds(10, 120f, 180f)
        state.updateRowWindowBounds(11, 220f, 280f)
        state.updateRowWindowBounds(12, 320f, 380f)
        state.updatePrewarmedScene(
            scalePercent = 130,
            anchorIndex = 11,
            anchorCenterYPx = 250f,
            boundsByIndex = mapOf(
                10 to LyricZoomRowBounds(60f, 145f),
                11 to LyricZoomRowBounds(205f, 295f),
                12 to LyricZoomRowBounds(355f, 445f),
                13 to LyricZoomRowBounds(505f, 595f),
            ),
        )
        assertTrue(state.prewarmedSceneReady(130, 11))
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        state.updatePinch(103f)
        // begin() imports and anchor-aligns the idle target before scaleGestureOwner-style pointer progress starts.
        assertTrue(state.layoutReady(130))
        val runway = listOf(10, 11, 12, 13)
        state.prepareTransitionFrame(runway)
        assertTrue(state.overlayOwnsText())
    }

    @Test
    fun `captured source holders remain owners while overlay contains target only rows`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 500f)
        // Runway row above the captured viewport.
        state.updateRowWindowBounds(9, 20f, 80f)
        // Captured source holders.
        state.updateRowWindowBounds(10, 120f, 180f)
        state.updateRowWindowBounds(11, 220f, 280f)
        state.updateRowWindowBounds(12, 320f, 380f)
        state.updateRowWindowBounds(13, 420f, 480f)
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        state.updatePinch(87.5f)

        val runway = listOf(9, 10, 11, 12, 13)
        state.setLayoutExpectedIndices(75, runway)
        state.updateLayoutSceneWindowBounds(
            75,
            mapOf(
                9 to LyricZoomRowBounds(105f, 150f),
                10 to LyricZoomRowBounds(165f, 210f),
                11 to LyricZoomRowBounds(225f, 270f),
                12 to LyricZoomRowBounds(285f, 330f),
                13 to LyricZoomRowBounds(345f, 390f),
            ),
        )
        state.prepareTransitionFrame(runway)

        assertFalse(state.isFrozenSourceRow(9))
        assertTrue(state.isFrozenSourceRow(10))
        assertTrue(state.isFrozenSourceRow(11))
        assertTrue(state.isFrozenSourceRow(12))
        assertEquals(listOf(9), state.targetOnlyLineIndices(runway))

        // Shared source rows are transformed directly from their captured holder; they are not
        // represented by the target-only overlay.
        val shared = state.sourceHolderTransform(11)
        assertTrue(shared.alpha > 0f)
        assertTrue(kotlin.math.abs(shared.translationYPx) > 0.01f || kotlin.math.abs(shared.scale - 1f) > 0.001f)
    }

    @Test
    fun `source only captured row fades on its live holder`() {
        val state = LyricZoomTransitionState()
        state.updateViewportWindowBounds(100f, 500f)
        state.updateRowWindowBounds(10, 120f, 180f)
        state.updateRowWindowBounds(11, 220f, 280f)
        state.updateRowWindowBounds(12, 320f, 380f)
        assertTrue(state.begin(baseScale = 100, anchorIndex = 11))
        state.updatePinch(115f)

        val runway = listOf(10, 11, 12)
        state.setLayoutExpectedIndices(130, runway)
        state.updateLayoutSceneWindowBounds(
            130,
            mapOf(
                10 to LyricZoomRowBounds(-80f, 40f),
                11 to LyricZoomRowBounds(190f, 310f),
                12 to LyricZoomRowBounds(350f, 470f),
            ),
        )
        state.prepareTransitionFrame(runway)

        assertEquals(LyricZoomRowOwnership.SOURCE_ONLY, state.rowOwnership(10))
        assertTrue(state.targetOnlyLineIndices(runway).isEmpty())
        val leaving = state.sourceHolderTransform(10)
        assertTrue(leaving.alpha in 0.45f..0.55f)
    }

}
