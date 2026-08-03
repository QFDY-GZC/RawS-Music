package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFullCoverBackRuntimeTest {
    @Test
    fun activityBridgeDeliversOneGestureToCurrentOwner() {
        val owner = Any()
        var starts = 0
        var progress = -1f
        var completions = 0
        HomeFullCoverBackRuntime.register(
            owner,
            HomeFullCoverBackRuntime.Callbacks(
                onStarted = { starts += 1 },
                onProgressed = { progress = it },
                onCancelled = {},
                onCompleted = { completions += 1 },
            ),
        )

        assertTrue(HomeFullCoverBackRuntime.start())
        HomeFullCoverBackRuntime.progress(0.4f)
        assertTrue(HomeFullCoverBackRuntime.complete())
        assertEquals(1, starts)
        assertEquals(0.4f, progress, 0.0001f)
        assertEquals(1, completions)

        HomeFullCoverBackRuntime.unregister(owner)
        assertFalse(HomeFullCoverBackRuntime.hasOwner())
    }

    @Test
    fun staleOwnerCannotUnregisterReplacement() {
        val oldOwner = Any()
        val newOwner = Any()
        HomeFullCoverBackRuntime.register(
            oldOwner,
            HomeFullCoverBackRuntime.Callbacks({}, { _ -> }, {}, {}),
        )
        HomeFullCoverBackRuntime.register(
            newOwner,
            HomeFullCoverBackRuntime.Callbacks({}, { _ -> }, {}, {}),
        )

        HomeFullCoverBackRuntime.unregister(oldOwner)
        assertTrue(HomeFullCoverBackRuntime.hasOwner())
        HomeFullCoverBackRuntime.unregister(newOwner)
    }
}
