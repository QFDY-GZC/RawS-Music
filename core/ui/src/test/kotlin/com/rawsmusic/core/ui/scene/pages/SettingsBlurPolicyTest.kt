package com.rawsmusic.core.ui.scene.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBlurPolicyTest {
    @Test fun invalidRestoredOffsetDoesNotRemoveTheTopSamplingBand() {
        assertEquals(206f, settingsBlurViewportHeight(206f, Float.NaN), 0f)
        assertEquals(206f, settingsBlurViewportHeight(206f, Float.NEGATIVE_INFINITY), 0f)
    }

    @Test fun searchSamplesTheCompositedAppBarRatherThanRedrawingTheSharpPage() {
        assertEquals(SettingsSearchSample.APP_BAR, settingsSearchSample(1f))
        assertEquals(SettingsSearchSample.APP_BAR, settingsSearchSample(0.01f))
    }

    @Test fun expandedSearchDoesNotReuseAnOldAppBarRecording() {
        assertEquals(SettingsSearchSample.PAGE, settingsSearchSample(0f))
        assertEquals(SettingsSearchSample.PAGE, settingsSearchSample(-1f))
    }
    @Test fun overlayFollowsReferenceViewAlphaWithoutChangingBlurRadius() {
        assertEquals(0.01f, settingsBlurSampleAlpha(0.01f), 0f)
        assertEquals(0.5f, settingsBlurSampleAlpha(0.5f), 0f)
        assertEquals(1f, settingsBlurSampleAlpha(2f), 0f)
        assertEquals(0.007f, settingsOverlayTintAlpha(0.01f, 0.7f), 0.000001f)
    }

    @Test fun unscrolledHeaderDoesNotShowBlurSample() {
        assertEquals(0f, settingsBlurSampleAlpha(0f), 0f)
        assertEquals(0f, settingsBlurSampleAlpha(-1f), 0f)
    }
    @Test fun collapsedHeaderStillCoversStatusBarSearchAndExtraBand() {
        assertEquals(90f, settingsBlurViewportHeight(206f, -116f), 0f)
    }

    @Test fun fixedChildToolbarRetainsItsEntireBlurBand() {
        assertEquals(100f, settingsBlurViewportHeight(100f, 0f), 0f)
    }

    @Test fun fullyOffscreenBandHasNoNegativeHeight() {
        assertEquals(0f, settingsBlurViewportHeight(100f, -120f), 0f)
    }
    @Test fun transparentPageDoesNotAcquireAnOpaqueColourRamp() {
        assertEquals(0f, settingsOverlayTintAlpha(0f, 1f), 0f)
        assertEquals(0f, settingsOverlayTintAlpha(0f, 0.4f), 0f)
    }

    @Test fun solidPagePreservesReferenceLightAndDarkStops() {
        assertEquals(1f, settingsOverlayTintAlpha(1f, 1f), 0f)
        assertEquals(0.7f, settingsOverlayTintAlpha(1f, 0.7f), 0f)
        assertEquals(0.4f, settingsOverlayTintAlpha(1f, 0.4f), 0f)
        assertEquals(0f, settingsOverlayTintAlpha(1f, 0f), 0f)
    }

    @Test fun partiallyTransparentPageRetainsItsOwnOpacity() {
        assertEquals(0.35f, settingsOverlayTintAlpha(0.5f, 0.7f), 0.00001f)
    }

    @Test fun sharpAndBlurredSamplesMeetAtReferenceEndpoints() {
        assertEquals(1f, settingsBlurWeight(0f), 0f)
        assertEquals(0.875f, settingsBlurWeight(0.5f), 0f)
        assertEquals(0f, settingsBlurWeight(1f), 0f)
        assertEquals(1f, settingsBlurWeight(-0.2f), 0f)
        assertEquals(0f, settingsBlurWeight(1.2f), 0f)
    }
}
