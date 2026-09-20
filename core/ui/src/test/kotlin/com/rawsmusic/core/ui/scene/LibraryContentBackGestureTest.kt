package com.rawsmusic.core.ui.scene

import org.junit.Assert.*
import org.junit.Test

class LibraryContentBackGestureTest {
    @Test fun childAndPendingReturnKeepProviderOwnership() {
        assertTrue(providerOwnsBack(true, false))
        assertTrue(providerOwnsBack(false, true))
        assertFalse(providerOwnsBack(false, false))
    }

    @Test fun leftAndRightGesturesUseSceneExternalPivots() {
        assertEquals(1500f, collectionBackPivotX(1000f, true, 1f), 0f)
        assertEquals(-500f, collectionBackPivotX(1000f, true, -1f), 0f)
        assertEquals(500f, collectionBackPivotX(1000f, false, 1f), 0f)
    }

    @Test fun flingReleaseMatchesSceneContinuation() {
        val commit = collectionReleaseMotion(0.9f, true, 5f)
        assertTrue(commit.carryVelocity)
        assertEquals(20, commit.durationMillis)
        val cancel = collectionReleaseMotion(0.1f, false, -5f)
        assertTrue(cancel.carryVelocity)
        assertEquals(20, cancel.durationMillis)
        assertFalse(collectionReleaseMotion(0.5f, true, 5f).carryVelocity)
    }
}
