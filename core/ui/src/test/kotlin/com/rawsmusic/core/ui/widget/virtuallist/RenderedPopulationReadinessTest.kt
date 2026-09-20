package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.*
import org.junit.Test

class RenderedPopulationReadinessTest {
    @Test fun elapsedFramesCannotMakeAnUndrawnPopulationReady() {
        val gate = RenderedPopulationReadiness<String>()
        repeat(240) { assertFalse(gate.isReady("albums")) }
        gate.acknowledgeDraw("albums")
        assertTrue(gate.isReady("albums"))
        assertFalse(gate.isReady("artists"))
    }

    @Test fun invalidationRequiresAnotherDraw() {
        val gate = RenderedPopulationReadiness<String>()
        gate.acknowledgeDraw("songs")
        gate.invalidate()
        assertFalse(gate.isReady("songs"))
        gate.acknowledgeDraw("songs")
        assertTrue(gate.isReady("songs"))
    }

    @Test fun propertyFramesDoNotReconcileChildrenButSuspensionDoes() {
        assertFalse(shouldReconcilePresentationStructure(3, 3, 20, 20))
        assertTrue(shouldReconcilePresentationStructure(3, 4, 20, 20))
        assertTrue(shouldReconcilePresentationStructure(3, 3, 20, 0))
        assertTrue(shouldReconcilePresentationStructure(3, 3, 0, 20))
    }
}
