package com.rawsmusic.module.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerHiResBadgeSourceTest {
    @Test
    fun builtinUsesNativeBaselineAndScale() {
        val normal = resolvePlayerHiResBadgeDisplaySize(null, 1f)
        val doubled = resolvePlayerHiResBadgeDisplaySize(null, 2f)

        assertEquals(58f, normal.widthDp, 0.001f)
        assertEquals(28f, normal.heightDp, 0.001f)
        assertEquals(116f, doubled.widthDp, 0.001f)
        assertEquals(56f, doubled.heightDp, 0.001f)
    }

    @Test
    fun importedLandscapeKeepsSourceAspectRatio() {
        val size = resolvePlayerHiResBadgeDisplaySize(
            customSource = PlayerHiResBadgeSourceInfo(2000, 500),
            sizeScale = 1f,
        )

        assertEquals(58f, size.widthDp, 0.001f)
        assertEquals(14.5f, size.heightDp, 0.001f)
    }

    @Test
    fun importedPortraitKeepsSourceAspectRatio() {
        val size = resolvePlayerHiResBadgeDisplaySize(
            customSource = PlayerHiResBadgeSourceInfo(500, 2000),
            sizeScale = 0.5f,
        )

        assertEquals(7.25f, size.widthDp, 0.001f)
        assertEquals(29f, size.heightDp, 0.001f)
    }
}
