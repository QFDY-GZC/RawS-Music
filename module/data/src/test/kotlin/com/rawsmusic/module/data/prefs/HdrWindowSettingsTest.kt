package com.rawsmusic.module.data.prefs

import org.junit.Assert.*
import org.junit.Test

class HdrWindowSettingsTest {
    @Test fun manualHeadroomIsRequestedOnSupportedApiOnly() {
        val settings = HdrWindowSettings(desiredHeadroom = 2.5f)
        assertEquals(2.5f, settings.requestedHeadroom(35), 0f)
        assertEquals(0f, settings.requestedHeadroom(34), 0f)
    }
    @Test fun malformedHeadroomCannotReachWindowApi() {
        assertEquals(0f, HdrWindowSettings(desiredHeadroom = Float.NaN).requestedHeadroom(35), 0f)
        assertEquals(0f, HdrWindowSettings(desiredHeadroom = Float.POSITIVE_INFINITY).requestedHeadroom(35), 0f)
        assertEquals(0f, HdrWindowSettings(desiredHeadroom = -1f).requestedHeadroom(35), 0f)
        assertEquals(1f, HdrWindowSettings(desiredHeadroom = 0.5f).requestedHeadroom(35), 0f)
        assertEquals(8f, HdrWindowSettings(desiredHeadroom = 100f).requestedHeadroom(35), 0f)
    }
    @Test fun unavailableRatioIsNotReportedAsOneOrRequestedValue() {
        assertNull(observedHdrRatio(34, false, 1f))
        assertNull(observedHdrRatio(33, true, 2f))
        assertNull(observedHdrRatio(34, true, Float.NaN))
        assertNull(observedHdrRatio(34, true, 0.5f))
        assertEquals(1f as Float?, observedHdrRatio(34, true, 1f))
        assertEquals(1.7f as Float?, observedHdrRatio(34, true, 1.7f))
    }
    @Test fun selectedSceneKeepsWindowWithoutAnyArtworkRequirement() {
        assertTrue(HdrWindowSettings(true).requests(HdrScene.PLAYER))
    }
    @Test fun disabledAndUnselectedDoNotRequestHdr() {
        assertFalse(HdrWindowSettings().requests(HdrScene.PLAYER))
        assertFalse(HdrWindowSettings(true).requests(HdrScene.HOME))
    }
    @Test fun handoffKeepsHdrUntilUnselectedDestinationSettles() {
        val settings = HdrWindowSettings(true)
        assertTrue(settings.requests(HdrScene.HOME, HdrScene.PLAYER))
        assertTrue(settings.requests(HdrScene.PLAYER, HdrScene.HOME))
        assertFalse(settings.requests(HdrScene.HOME))
    }
    @Test fun unsupportedAndBackgroundAlwaysFallBack() {
        assertTrue(canKeepHdrWindow(34, true, true, true, true))
        assertFalse(canKeepHdrWindow(33, true, true, true, true))
        assertFalse(canKeepHdrWindow(34, false, true, true, true))
        assertFalse(canKeepHdrWindow(34, true, false, true, true))
        assertFalse(canKeepHdrWindow(34, true, true, false, true))
        assertFalse(canKeepHdrWindow(34, true, true, true, false))
    }
    @Test fun independentSceneSelections() {
        val settings = HdrWindowSettings(true, setOf(HdrScene.SETTINGS, HdrScene.LYRICS))
        assertTrue(settings.requests(HdrScene.SETTINGS))
        assertTrue(settings.requests(HdrScene.LYRICS))
        assertFalse(settings.requests(HdrScene.PLAYER))
    }
}
