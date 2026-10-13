package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class VirtualListEdgeMotionPolicyTest {
    @Test
    fun `visual overshoot is logarithmic and capped at VirtualList max`() {
        assertEquals(0f, virtualListVisualOvershootPx(0f, 44f), 0.0001f)
        val small = virtualListVisualOvershootPx(22f, 44f)
        val large = virtualListVisualOvershootPx(66f, 44f)
        assertTrue(small in 0f..large)
        assertEquals(11f * ln(3f), small, 0.0001f)
        assertEquals(11f * ln(7f), large, 0.0001f)
        assertEquals(-large, virtualListVisualOvershootPx(-66f, 44f), 0.0001f)
        assertEquals(44f, virtualListVisualOvershootPx(10_000f, 44f), 0.0001f)
    }

    @Test
    fun `outward correction uses layout clamped edge before logarithmic rollback`() {
        val max = 44f
        val firstCandidate = max // Layout clamps the first huge move to +P.
        val first = virtualListCorrectExtendedEdgePx(
            candidateEdgePx = firstCandidate,
            inputDeltaPx = 1000f,
            rawAccumulatorPx = firstCandidate, // A new edge starts from its clamped displacement.
            maxOvershootPx = max,
        )
        assertEquals(11f * ln(5f), first, 0.0001f)

        // Same edge state: accumulate the complete next move. The logarithmic
        // target is above P, so the second layout call cannot push past the engine's +P clamp.
        val second = virtualListCorrectExtendedEdgePx(
            candidateEdgePx = max,
            inputDeltaPx = 1000f,
            rawAccumulatorPx = firstCandidate + 1000f,
            maxOvershootPx = max,
        )
        assertEquals(max, second, 0.0001f)
    }

    @Test
    fun `outward correction preserves direction flip hysteresis`() {
        val max = 44f
        val first = 11f * ln(5f)
        // Inward motion skips correction, so both visible and accumulated pull drop by 10px.
        val afterReverse = first - 10f
        val rawAfterReverse = 44f - 10f
        val candidate = afterReverse + 5f
        val corrected = virtualListCorrectExtendedEdgePx(
            candidateEdgePx = candidate,
            inputDeltaPx = 5f,
            rawAccumulatorPx = rawAfterReverse + 5f,
            maxOvershootPx = max,
        )
        assertEquals(11f * ln(1f + 39f / 11f), corrected, 0.0001f)
    }

    @Test
    fun `holder deformation expands top edge and compresses bottom edge`() {
        val fraction = 1f
        assertEquals(1.04f, virtualListHolderScaleY(44f, 44f, fraction), 0.0001f)
        assertEquals(0.96f, virtualListHolderScaleY(-44f, 44f, fraction), 0.0001f)
        assertEquals(30f, virtualListHolderTranslationY(44f, 44f, 30f, fraction), 0.0001f)
        assertEquals(-30f, virtualListHolderTranslationY(-44f, 44f, 30f, fraction), 0.0001f)
    }

    @Test
    fun `holder fraction follows visible row ordinal`() {
        assertEquals(0.1f, virtualListHolderEdgeFraction(0, 0, 9, 1), 0.0001f)
        assertEquals(0.5f, virtualListHolderEdgeFraction(4, 0, 9, 1), 0.0001f)
        assertEquals(1f, virtualListHolderEdgeFraction(9, 0, 9, 1), 0.0001f)

        // GRID_2: visible rows are 1,2,3,4. Both holders in one row share one exact factor.
        assertEquals(0.25f, virtualListHolderEdgeFraction(2, 2, 9, 2), 0.0001f)
        assertEquals(0.25f, virtualListHolderEdgeFraction(3, 2, 9, 2), 0.0001f)
        assertEquals(1f, virtualListHolderEdgeFraction(8, 2, 9, 2), 0.0001f)
        assertEquals(1f, virtualListHolderEdgeFraction(9, 2, 9, 2), 0.0001f)
    }

    @Test
    fun `rebound duration follows overshoot distance`() {
        assertEquals(500, virtualListEdgeReboundDurationMs(44f))
        assertEquals(1000, virtualListEdgeReboundDurationMs(1500f))
        assertEquals(2000, virtualListEdgeReboundDurationMs(3000f))
    }

    @Test
    fun `rebound uses distance dependent cubic solver tolerance`() {
        assertEquals(0.5f, virtualListEdgeReboundTimeScale(44f), 0.0001f)
        assertEquals(0.004f, virtualListEdgeReboundTolerance(0.5f), 0.000001f)
        assertEquals(0.5191192f, virtualListEdgeReboundProgress(100L, 44f), 0.000001f)
        assertEquals(0.8453277f, virtualListEdgeReboundProgress(250L, 44f), 0.000001f)
        assertEquals(1f, virtualListEdgeReboundProgress(500L, 44f), 0.000001f)
    }
}

