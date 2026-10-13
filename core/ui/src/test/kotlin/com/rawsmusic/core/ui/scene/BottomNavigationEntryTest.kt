package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationEntryTest {
    @Test
    fun fourItemSavedOrderIsPreservedExactly() {
        val tags = listOf("home", "audio_effects", "search", "settings")

        assertEquals(
            listOf(
                NavScene.HOME,
                NavScene.AUDIO_EFFECTS,
                NavScene.SEARCH,
                NavScene.SETTINGS,
            ),
            resolveBottomNavigationScenes(tags),
        )
    }

    @Test
    fun configurableEntriesHaveUniqueStableTags() {
        assertEquals(
            customizableBottomNavigationScenes.size,
            customizableBottomNavigationScenes.map { it.tag }.distinct().size,
        )
    }
}
