package com.rawsmusic.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeHeaderMotionTest {
    @Test
    fun `title fades and scales through the full header collapse`() {
        val expanded = SettingsHomeHeaderMotion.at(scrollDp = 0f)
        // Half of the *title* range (64dp), not of the 116dp AppBar range: the expansion fraction is
        // |offset| / (height - minimumHeight).
        val halfway = SettingsHomeHeaderMotion.at(scrollDp = 32f)
        val collapsed = SettingsHomeHeaderMotion.at(scrollDp = 116f)

        assertEquals(1f, expanded.titleAlpha, 0.001f)
        assertEquals(1f, expanded.titleScale, 0.001f)
        assertEquals(0.5f, halfway.titleAlpha, 0.001f)
        assertEquals(0.85f, halfway.titleScale, 0.001f)
        assertEquals(0f, collapsed.titleAlpha, 0.001f)
        assertEquals(0.7f, collapsed.titleScale, 0.001f)
        // The title is already gone at 64dp; the rest of the AppBar travel must not resurrect it.
        assertEquals(0f, SettingsHomeHeaderMotion.at(64f).titleAlpha, 0.001f)
    }

    @Test
    fun `blur becomes opaque over first thirty dp and clamps outside range`() {
        assertEquals(0f, SettingsHomeHeaderMotion.at(-20f).blurAlpha, 0.001f)
        assertEquals(0.5f, SettingsHomeHeaderMotion.at(15f).blurAlpha, 0.001f)
        assertEquals(1f, SettingsHomeHeaderMotion.at(30f).blurAlpha, 0.001f)
        assertEquals(1f, SettingsHomeHeaderMotion.at(500f).blurAlpha, 0.001f)
        assertEquals(0f, SettingsHomeHeaderMotion.at(500f).titleAlpha, 0.001f)
    }

    @Test
    fun `blur alpha follows the resolved scroll distance`() {
        // AppBarBlurOverlayBehavior.computeOverlayAlpha: min(1, distance / 30dp), 0 at distance 0.
        // The distance is the |AppBar offset| while it is non-zero, otherwise the content scroll
        // plus the rubber band, so a list that cannot move the bar still fades the overlay in.
        assertEquals(0f, SettingsHomeHeaderMotion.blurAlpha(0f), 0.001f)
        assertEquals(0.5f, SettingsHomeHeaderMotion.blurAlpha(15f), 0.001f)
        assertEquals(1f, SettingsHomeHeaderMotion.blurAlpha(30f), 0.001f)
        assertEquals(1f, SettingsHomeHeaderMotion.blurAlpha(500f), 0.001f)
    }

    @Test
    fun `extra blur height needs the title collapsed, not the whole range`() {
        // SettingsCollapsableAppBarLayout.isCollapsed() is the collapsing layout's
        // COUICollapsingTextHelper expansion fraction == 1, i.e. 64dp of scroll - HomepageImpl keys
        // homepage_appbar_blur_extra_height off that, not off the full 116dp range.
        assertFalse(SettingsHomeHeaderMotion.at(63f).collapsed)
        assertTrue(SettingsHomeHeaderMotion.at(64f).collapsed)
        assertTrue(SettingsHomeHeaderMotion.at(116f).collapsed)
    }

    @Test
    fun `collapsing is the AppBar's pre-scroll, expanding only the list's leftover`() {
        val range = 406f // 116dp at density 3.5

        // Finger up (negative): the bar takes the whole drag until it is collapsed.
        assertEquals(-100f, SettingsHomeHeaderMotion.preScrollConsumption(-100f, 0f, range), 0.01f)
        assertEquals(-100f, SettingsHomeHeaderMotion.preScrollConsumption(-100f, -100f, range), 0.01f)
        // ...and then only what is left of the range.
        assertEquals(-6f, SettingsHomeHeaderMotion.preScrollConsumption(-50f, -400f, range), 0.01f)
        assertEquals(0f, SettingsHomeHeaderMotion.preScrollConsumption(-50f, -range, range), 0.01f)

        // Finger down (positive) is never the bar's in the pre-scroll, whatever the offset is.
        assertEquals(0f, SettingsHomeHeaderMotion.preScrollConsumption(120f, -range, range), 0.01f)
        assertEquals(0f, SettingsHomeHeaderMotion.preScrollConsumption(120f, 0f, range), 0.01f)

        // Expanding is the leftover the list could not consume, so it is the post-scroll's.
        assertEquals(120f, SettingsHomeHeaderMotion.postScrollConsumption(120f, -range, range), 0.01f)
        assertEquals(6f, SettingsHomeHeaderMotion.postScrollConsumption(120f, -6f, range), 0.01f)
        assertEquals(0f, SettingsHomeHeaderMotion.postScrollConsumption(120f, 0f, range), 0.01f)
        assertEquals(0f, SettingsHomeHeaderMotion.postScrollConsumption(-120f, -range, range), 0.01f)
    }
}
