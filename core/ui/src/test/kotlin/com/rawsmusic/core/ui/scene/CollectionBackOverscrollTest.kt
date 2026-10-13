package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionBackOverscrollTest {
    @Test fun navigationKeepsMainClockBoundedAndClearsOverflowAfterCancellation() {
        val navigation = NavigationState()
        navigation.navigateTo(NavScene.SONGS)
        navigation.completeTransitionAt(NavScene.SONGS)
        assertTrue(navigation.startBackDrag(-1f))
        navigation.updateBackDrag(-0.2f)
        assertEquals(0f, navigation.dragBackProgress, 0f)
        assertEquals(-0.2f, navigation.dragBackRawProgress, 0f)
        navigation.releaseBackDrag(false)
        assertEquals(0f, navigation.dragBackReleaseProgress, 0f)
        navigation.completeBackDrag(false)
        assertEquals(0f, navigation.dragBackRawProgress, 0f)
        assertTrue(navigation.startBackDrag(1f))
        navigation.updateBackDrag(0.4f)
        assertEquals(0f, collectionBackOriginOverscroll(navigation.dragBackRawProgress), 0f)
        navigation.resetTransientBackState()
        assertEquals(0f, navigation.dragBackRawProgress, 0f)
    }
    @Test fun normalTransitionDoesNotTranslateTheList() {
        for (progress in listOf(0f, 0.5f, 1f, 1.1f)) {
            assertEquals(0f, collectionBackOriginOverscroll(progress), 0f)
        }
    }

    @Test fun reverseOverflowKeepsItsSignAndUsesQuarterWidth() {
        val overflow = collectionBackOriginOverscroll(-0.2f)
        assertEquals(-50f, collectionBackOriginOffset(overflow, 1f, 1000f), 0.001f)
        assertEquals(50f, collectionBackOriginOffset(overflow, -1f, 1000f), 0.001f)
        assertEquals(0f, collectionBackOriginOffset(collectionBackOriginOverscroll(0f), 1f, 1000f), 0f)
    }

    @Test fun returnIsCubicAndNeverCrossesZero() {
        assertEquals(1f, collectionOverscrollReturnFraction(0f), 0f)
        assertEquals(0.125f, collectionOverscrollReturnFraction(0.5f), 0.00001f)
        assertEquals(0f, collectionOverscrollReturnFraction(1f), 0f)
        assertEquals(0f, collectionOverscrollReturnFraction(2f), 0f)
    }

    @Test fun returnDurationHasMinimumAndScalesWithDisplacement() {
        assertEquals(350, collectionOverscrollReturnMillis(-0.2f))
        assertEquals(500, collectionOverscrollReturnMillis(-1f))
    }
}
