package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentSceneBackgroundMotionPolicyTest {
    @Test
    fun libraryTransitionDoesNotWakeAnIdleRawFlowClock() {
        assertFalse(
            persistentRawFlowMotionEnabled(
                normalMotionActive = false,
                sceneTransitionActive = true,
            )
        )
    }

    @Test
    fun normalRawFlowMotionRemainsLiveThroughTransition() {
        assertTrue(
            persistentRawFlowMotionEnabled(
                normalMotionActive = true,
                sceneTransitionActive = true,
            )
        )
    }
}
