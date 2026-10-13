package com.rawsmusic.core.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerArtworkOwnershipTest {
    @Test fun miniArtworkIsHiddenDuringOpeningAndReturnUntilMainIsSettled() {
        assertEquals(true, suppressMiniPlayerArtworkForHandoff(sceneIsMain = true, transitioning = true))
        assertEquals(true, suppressMiniPlayerArtworkForHandoff(sceneIsMain = false, transitioning = true))
        assertEquals(true, suppressMiniPlayerArtworkForHandoff(sceneIsMain = false, transitioning = false))
        assertEquals(false, suppressMiniPlayerArtworkForHandoff(sceneIsMain = true, transitioning = false))
    }
    @Test fun collapsedPlayerSurfaceDoesNotCoverTheRestoredMiniPlayer() {
        assertEquals(0f, resolvePlayerSurfaceOwnershipAlpha(0f), 0f)
        assertEquals(0.5f, resolvePlayerSurfaceOwnershipAlpha(0.04f), 0.0001f)
        assertEquals(1f, resolvePlayerSurfaceOwnershipAlpha(0.08f), 0f)
        assertEquals(1f, resolvePlayerSurfaceOwnershipAlpha(1f), 0f)
    }
    @Test fun sharedActorStaysOpaqueUntilAnimationReleasesOwnership() {
        for (expansion in listOf(1f, 0.5f, 0.08f, 0.04f, 0.001f, 0f)) {
            assertEquals(1f, resolvePlayerSharedArtworkAlpha(expansion), 0f)
        }
    }
}
