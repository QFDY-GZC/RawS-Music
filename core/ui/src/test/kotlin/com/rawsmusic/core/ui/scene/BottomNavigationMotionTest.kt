package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavigationMotionTest {
    @Test
    fun bottomNavigationMarksTopLevelSwitchForScaleMotion() {
        val state = NavigationState()

        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        assertEquals(NavScene.AUDIO_EFFECTS, state.currentScene)
        assertEquals(NavigationMotionHint.BOTTOM_NAVIGATION, state.navigationMotionHint)
        assertEquals(listOf(NavScene.HOME, NavScene.AUDIO_EFFECTS), state.backStack)
    }

    @Test
    fun bottomNavigationHomeKeepsMotionHintWhileResettingStack() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.SONGS)
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        state.navigateFromBottomNavigation(NavScene.HOME)

        assertEquals(NavScene.HOME, state.currentScene)
        assertEquals(listOf(NavScene.HOME), state.backStack)
        assertEquals(NavigationMotionHint.BOTTOM_NAVIGATION, state.navigationMotionHint)
    }

    @Test
    fun pageInternalNavigationClearsBottomNavigationMotionHint() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        state.navigateTo(NavScene.PEQ)

        assertEquals(NavScene.PEQ, state.currentScene)
        assertEquals(NavigationMotionHint.DEFAULT, state.navigationMotionHint)
    }

    @Test
    fun backGestureClearsBottomNavigationMotionHint() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        assertTrue(state.startBackDrag())
        assertEquals(NavigationMotionHint.DEFAULT, state.navigationMotionHint)
        assertEquals(NavigationMotionHint.BOTTOM_NAVIGATION, state.backNavigationMotionHint)
        assertFalse(state.startBackDrag())
    }

    @Test
    fun programmaticBackFromBottomNavigationUsesReverseScaleMotion() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.SONGS)
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        assertTrue(state.navigateBackAnimated())

        assertEquals(NavScene.SONGS, state.backPreviewScene)
        assertEquals(NavigationMotionHint.BOTTOM_NAVIGATION, state.backNavigationMotionHint)
    }

    @Test
    fun audioEffectChildBackStillUsesSettingsMotion() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)
        state.navigateTo(NavScene.PEQ)

        assertTrue(state.navigateBackAnimated())

        assertEquals(NavScene.AUDIO_EFFECTS, state.backPreviewScene)
        assertEquals(NavigationMotionHint.DEFAULT, state.backNavigationMotionHint)
    }
}
