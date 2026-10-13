package com.rawsmusic.core.ui.scene

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawWindowLayoutProfileTest {
    @Test
    fun compactPhone_preservesTwoColumnHomeAndCompactChrome() {
        val profile = resolveRawWindowLayoutProfile(412.dp, 915.dp)

        assertEquals(RawWindowWidthClass.Compact, profile.widthClass)
        assertEquals(BottomChromeLayoutClass.Compact, profile.bottomChromeLayoutClass)
        assertEquals(2, profile.homeCardColumns)
        assertFalse(profile.useWidePlayerLayout)
        assertFalse(profile.supportsMultiRegion)
        assertFalse(profile.supportsPersistentLibraryRail)
    }

    @Test
    fun mediumWindow_keepsSpaciousTwoColumnHomeWithoutForcingWidePlayer() {
        val profile = resolveRawWindowLayoutProfile(700.dp, 1000.dp)

        assertEquals(RawWindowWidthClass.Medium, profile.widthClass)
        assertEquals(2, profile.homeCardColumns)
        assertEquals(BottomChromeLayoutClass.Regular, profile.bottomChromeLayoutClass)
        assertFalse(profile.useWidePlayerLayout)
        assertTrue(profile.supportsMultiRegion)
        assertFalse(profile.supportsSupportingPane)
        assertFalse(profile.supportsPersistentLibraryRail)
    }

    @Test
    fun multiRegionCapability_startsAt640DpButNotAt600Dp() {
        assertFalse(resolveRawWindowLayoutProfile(600.dp, 900.dp).supportsMultiRegion)
        assertTrue(resolveRawWindowLayoutProfile(640.dp, 900.dp).supportsMultiRegion)
    }

    @Test
    fun expandedTablet_usesWidePlayerAndThreeHomeColumns() {
        val profile = resolveRawWindowLayoutProfile(1024.dp, 768.dp)

        assertEquals(RawWindowWidthClass.Expanded, profile.widthClass)
        assertEquals(3, profile.homeCardColumns)
        assertTrue(profile.useWidePlayerLayout)
        assertTrue(profile.supportsSupportingPane)
        assertFalse(profile.supportsPersistentLibraryRail)
        assertEquals(960.dp, profile.homeCardContentMaxWidth)
        assertEquals(760.dp, profile.settingsContentMaxWidth)
        assertEquals(960.dp, profile.settingsRootContentMaxWidth)
    }

    @Test
    fun shortMediumWindow_keepsHorizontalPlayerToAvoidVerticalOverflow() {
        val profile = resolveRawWindowLayoutProfile(700.dp, 450.dp)

        assertEquals(RawWindowHeightClass.Compact, profile.heightClass)
        assertTrue(profile.useWidePlayerLayout)
    }

    @Test
    fun smallLandscapePhone_usesHorizontalPlayerWithoutRequiringTabletWidth() {
        val profile = resolveRawWindowLayoutProfile(580.dp, 320.dp)
        assertTrue(profile.useWidePlayerLayout)
        assertFalse(resolveRawWindowLayoutProfile(320.dp, 580.dp).useWidePlayerLayout)
    }

    @Test
    fun largeWindow_capsContentInsteadOfScalingForever() {
        val profile = resolveRawWindowLayoutProfile(1600.dp, 1000.dp)

        assertEquals(RawWindowWidthClass.ExtraLarge, profile.widthClass)
        assertEquals(1200.dp, profile.playerContentMaxWidth)
        assertEquals(1200.dp, profile.homeCardContentMaxWidth)
        assertEquals(560.dp, profile.dialogMaxWidth)
        assertEquals(320.dp, profile.sideRailMaxWidth)
        assertTrue(profile.supportsPersistentLibraryRail)
        assertEquals(208.dp, profile.libraryRailWidth)
        assertEquals(16.dp, profile.libraryRailGap)
        assertEquals(1056.dp, profile.libraryContentMaxWidth)
    }
}
