package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavigationBranchTest {
    @Test
    fun switchingTabsKeepsRecentTopLevelHistory() {
        val state = NavigationState()

        state.navigateFromBottomNavigation(NavScene.SONGS)
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        assertEquals(NavScene.AUDIO_EFFECTS, state.currentScene)
        assertEquals(
            listOf(NavScene.HOME, NavScene.SONGS, NavScene.AUDIO_EFFECTS),
            state.backStack,
        )
    }

    @Test
    fun backReturnsToPreviousBottomNavigationEntryThenHome() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.SONGS)
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        assertTrue(state.navigateBack())
        assertEquals(NavScene.SONGS, state.currentScene)
        assertEquals(listOf(NavScene.HOME, NavScene.SONGS), state.backStack)

        assertTrue(state.navigateBack())
        assertEquals(NavScene.HOME, state.currentScene)
        assertEquals(listOf(NavScene.HOME), state.backStack)
        assertFalse(state.navigateBack())
    }

    @Test
    fun repeatedTabSwitchingKeepsEachEntryOnlyOnce() {
        val state = NavigationState()

        repeat(4) {
            state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)
            state.navigateFromBottomNavigation(NavScene.SONGS)
        }

        assertEquals(NavScene.SONGS, state.currentScene)
        assertEquals(
            listOf(NavScene.HOME, NavScene.AUDIO_EFFECTS, NavScene.SONGS),
            state.backStack,
        )
    }

    @Test
    fun repeatedTabSwitchingDoesNotCreateABackLoop() {
        val state = NavigationState()
        repeat(4) {
            state.navigateFromBottomNavigation(NavScene.SONGS)
            state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)
        }

        assertTrue(state.navigateBack())
        assertEquals(NavScene.SONGS, state.currentScene)
        assertEquals(listOf(NavScene.HOME, NavScene.SONGS), state.backStack)

        assertTrue(state.navigateBack())
        assertEquals(NavScene.HOME, state.currentScene)
        assertFalse(state.navigateBack())
    }

    @Test
    fun selectingBottomEntryFromDetailDropsDetailButKeepsRootHistory() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.SONGS)
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)
        state.navigateTo(NavScene.PEQ)

        state.navigateFromBottomNavigation(NavScene.SONGS)

        assertEquals(NavScene.SONGS, state.currentScene)
        assertEquals(
            listOf(NavScene.HOME, NavScene.AUDIO_EFFECTS, NavScene.SONGS),
            state.backStack,
        )
    }

    @Test
    fun selectingCurrentRootFromDetailReturnsToRootWithoutDuplicate() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.SONGS)
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)
        state.navigateTo(NavScene.PEQ)

        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        assertEquals(NavScene.AUDIO_EFFECTS, state.currentScene)
        assertEquals(
            listOf(NavScene.HOME, NavScene.SONGS, NavScene.AUDIO_EFFECTS),
            state.backStack,
        )
    }

    @Test
    fun internalPagesStillAppendAboveCurrentBottomNavigationRoot() {
        val state = NavigationState()
        state.navigateFromBottomNavigation(NavScene.AUDIO_EFFECTS)

        state.navigateTo(NavScene.PEQ)

        assertEquals(NavScene.PEQ, state.currentScene)
        assertEquals(
            listOf(NavScene.HOME, NavScene.AUDIO_EFFECTS, NavScene.PEQ),
            state.backStack,
        )
    }
}
