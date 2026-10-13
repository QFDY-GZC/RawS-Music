package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationSelectionTest {
    @Test
    fun fourItemEffectsSearchSettingsConfigurationKeepsExactSceneIndexes() {
        val tabs = listOf(
            NavScene.HOME,
            NavScene.AUDIO_EFFECTS,
            NavScene.SEARCH,
            NavScene.SETTINGS,
        )

        tabs.forEachIndexed { expectedIndex, scene ->
            assertEquals(
                expectedIndex,
                resolveBottomNavigationSelectedIndex(
                    tabScenes = tabs,
                    currentScene = scene,
                    backPreviewScene = null,
                    backStack = listOf(NavScene.HOME, scene).distinct(),
                ),
            )
        }
    }

    @Test
    fun searchKeepsSongsOwnerWhenSearchTabIsNotConfigured() {
        val tabs = listOf(NavScene.HOME, NavScene.SONGS, NavScene.AUDIO_EFFECTS)

        assertEquals(
            1,
            resolveBottomNavigationSelectedIndex(
                tabScenes = tabs,
                currentScene = NavScene.SEARCH,
                backPreviewScene = null,
                backStack = listOf(NavScene.HOME, NavScene.SONGS, NavScene.SEARCH),
            )
        )
    }

    @Test
    fun configuredSearchTabAlwaysWins() {
        val tabs = listOf(NavScene.HOME, NavScene.SONGS, NavScene.SEARCH)

        assertEquals(
            2,
            resolveBottomNavigationSelectedIndex(
                tabScenes = tabs,
                currentScene = NavScene.SEARCH,
                backPreviewScene = null,
                backStack = listOf(NavScene.HOME, NavScene.SONGS, NavScene.SEARCH),
            )
        )
    }

    @Test
    fun searchBackPreviewUsesItsOriginalOwner() {
        val tabs = listOf(NavScene.HOME, NavScene.SONGS, NavScene.ALBUMS)

        assertEquals(
            1,
            resolveBottomNavigationSelectedIndex(
                tabScenes = tabs,
                currentScene = NavScene.ALBUM_DETAIL,
                backPreviewScene = NavScene.SEARCH,
                backStack = listOf(
                    NavScene.HOME,
                    NavScene.SONGS,
                    NavScene.SEARCH,
                    NavScene.ALBUM_DETAIL,
                ),
            )
        )
    }

    @Test
    fun searchFallsBackHomeOnlyWhenNoOwningEntryIsAvailable() {
        val tabs = listOf(NavScene.HOME, NavScene.AUDIO_EFFECTS)

        assertEquals(
            0,
            resolveBottomNavigationSelectedIndex(
                tabScenes = tabs,
                currentScene = NavScene.SEARCH,
                backPreviewScene = null,
                backStack = listOf(NavScene.HOME, NavScene.ALBUMS, NavScene.SEARCH),
            )
        )
    }
}
