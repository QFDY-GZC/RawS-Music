package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertSame
import org.junit.Test

class VirtualListTransitionInputsTest {
    @Test fun publicationWaitsForCommitOrCancellation() {
        val inputs = VirtualListTransitionInputs<List<Int>>()
        val original = listOf(1, 2)
        val update = listOf(3, 4)
        assertSame(original, inputs.resolve(false, original))
        repeat(120) { assertSame(original, inputs.resolve(true, update)) }
        assertSame(update, inputs.resolve(false, update))
        assertSame(update, inputs.resolve(true, original))
    }

    @Test fun coldDestinationUsesItsOwnInitialProvider() {
        val inputs = VirtualListTransitionInputs<List<Int>>()
        val initial = listOf(8)
        assertSame(initial, inputs.resolve(true, initial))
        assertSame(initial, inputs.resolve(true, listOf(9)))
    }
}
