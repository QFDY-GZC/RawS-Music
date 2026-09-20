package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SceneChromeEndpointTest {
    @Test fun cancelledPreviewReleasesItsLiveClock() {
        val state = SceneTransitionFrameState(NavScene.SONGS)
        var progress = 0.4f
        state.update(SceneTransitionFrame(true, progress, NavScene.SONGS, NavScene.HOME, true, { progress }))
        state.update(SceneTransitionFrame(false, 1f, NavScene.SONGS, NavScene.SONGS, false))
        progress = 1f
        assertFalse(state.active)
        assertEquals(NavScene.SONGS, state.fromScene)
        assertEquals(NavScene.SONGS, state.toScene)
        assertEquals(1f, state.progress, 0f)
        progress = 0f
        assertEquals(1f, state.progress, 0f)
    }
}
