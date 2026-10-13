package com.rawsmusic.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchMaterialTest {
    @Test fun staticForegroundDoesNotOverrideOpaqueControlsOwnContrast() {
        val root = generateSequence(java.nio.file.Paths.get("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.isDirectory(it.resolve("core/ui/src/main")) }
        val capsule = java.nio.file.Files.readString(root.resolve(
            "core/ui/src/main/kotlin/com/rawsmusic/core/ui/widget/player/AudioInfoCapsule.kt"))
        assertTrue("Classic capsule must retain its surface contrast in static mode",
            !capsule.contains("val contentColor = if (usesReferenceStaticForeground())"))
        val home = java.nio.file.Files.readString(root.resolve(
            "core/ui/src/main/kotlin/com/rawsmusic/core/ui/scene/pages/HomeFloatingHeaderHost.kt"))
        assertTrue("Opaque home buttons need their own onSurface foreground",
            home.contains("val actionContentColor = if (mainButtonsSurface == LibraryBottomButtonsSurface.SOLID)"))
    }
    @Test fun cancelLayerKeepsSubpixelSpringMotionInsteadOfSnappingBackAndForth() {
        org.junit.Assert.assertEquals(-0.23728f, SettingsSearchMaterial.cancelTranslation(1.011864f), 0.0001f)
        org.junit.Assert.assertEquals(-0.52586f, SettingsSearchMaterial.cancelTranslation(1.026293f), 0.0001f)
        org.junit.Assert.assertEquals(-0.49422f, SettingsSearchMaterial.cancelTranslation(1.024711f), 0.0001f)
        org.junit.Assert.assertEquals(940f + SettingsSearchMaterial.cancelTranslation(0.63f),
            SettingsSearchMaterial.cancelLeft(1000f, 60f, 0.63f), 0.0001f)
    }
    @Test fun materialWidthMatchesTheMeasuredSearchFieldDuringSpringOvershoot() {
        org.junit.Assert.assertEquals(895, SettingsSearchMaterial.searchWidth(1000, 80, 24, 1.01f))
        org.junit.Assert.assertEquals(1000, SettingsSearchMaterial.searchWidth(1000, 80, 24, -0.01f))
    }
    @Test fun cancelStartsAtTheExpandedBackgroundEdgeNotOneButtonWidthInside() {
        org.junit.Assert.assertEquals(960f, SettingsSearchMaterial.cancelLeft(1000f, 60f, 0f), 0.001f)
        org.junit.Assert.assertEquals(940f, SettingsSearchMaterial.cancelLeft(1000f, 60f, 1f), 0.001f)
    }
    private val search = SettingsSearchMaterial.Capsule(0f, 6f, 200f, 46f)

    @Test fun nearCapsulesHaveAContinuousNeckNotTwoDisconnectedSurfaces() {
        val cancel = SettingsSearchMaterial.Capsule(203f, 6f, 263f, 46f)
        assertTrue("The 3dp gap must still belong to the connected material",
            SettingsSearchMaterial.distance(search, cancel, 201.5f, 26f, 1f) < 0f)
    }

    @Test fun fullTwelveDpSeparationLeavesTheGapTransparent() {
        val cancel = SettingsSearchMaterial.Capsule(212f, 6f, 272f, 46f)
        assertTrue(SettingsSearchMaterial.distance(search, cancel, 206f, 26f, 1f) > 0f)
    }

    @Test fun materialDoesNotExpandPastTheOutsideEnds() {
        val cancel = SettingsSearchMaterial.Capsule(203f, 6f, 263f, 46f)
        assertTrue(SettingsSearchMaterial.distance(search, cancel, -2f, 26f, 1f) > 0f)
        assertTrue(SettingsSearchMaterial.distance(search, cancel, 265f, 26f, 1f) > 0f)
    }
}
