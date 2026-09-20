package com.rawsmusic.core.common.artwork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkResolutionPolicyTest {
    @Test
    fun largeDisplayUsesExpectedProviderTiers() {
        val heap = 256L * 1024L * 1024L
        assertEquals(512, ArtworkResolutionPolicy.lowTargetSide(1200))
        assertEquals(1024, ArtworkResolutionPolicy.highTargetSide(false, 1200, heap))
        assertEquals(1536, ArtworkResolutionPolicy.highTargetSide(true, 1200, heap))
    }

    @Test
    fun smallDisplayUsesExpectedProviderTiers() {
        val heap = 256L * 1024L * 1024L
        assertEquals(256, ArtworkResolutionPolicy.lowTargetSide(999))
        assertEquals(512, ArtworkResolutionPolicy.highTargetSide(false, 999, heap))
        assertEquals(1024, ArtworkResolutionPolicy.highTargetSide(true, 999, heap))
    }

    @Test
    fun qualityFeaturesAreIgnoredBelow128MiB() {
        val lowHeap = 127L * 1024L * 1024L
        assertFalse(ArtworkResolutionPolicy.qualityFeatureSupported(lowHeap))
        assertEquals(1024, ArtworkResolutionPolicy.highTargetSide(true, 1200, lowHeap))
        assertEquals(512, ArtworkResolutionPolicy.highTargetSide(true, 800, lowHeap))
        assertFalse(ArtworkResolutionPolicy.use24BitRgbEffective(true, lowHeap))
        assertTrue(ArtworkResolutionPolicy.use24BitRgbEffective(true, 128L * 1024L * 1024L))
    }
}
