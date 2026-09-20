package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareArtworkPipelinePolicyTest {
    @Test
    fun hardwareDecoderResultStaysGpuResidentInsteadOfCpuNormalizing() {
        assertTrue(HardwareArtworkPipelinePolicy.keepDecoderGeometry(isHardware = true))
        assertFalse(HardwareArtworkPipelinePolicy.keepDecoderGeometry(isHardware = false))
    }

    @Test
    fun hardwareBitmapIsNeverReadBackOnlyToPopulateDiskThumbnailCache() {
        assertFalse(HardwareArtworkPipelinePolicy.allowDiskThumbnailWrite(isHardware = true))
        assertTrue(HardwareArtworkPipelinePolicy.allowDiskThumbnailWrite(isHardware = false))
    }

    @Test
    fun cpuPixelAnalysisUsesDedicatedSmallSoftwareRaster() {
        assertFalse(HardwareArtworkPipelinePolicy.mayAnalyzePixelsDirectly(isHardware = true))
        assertTrue(HardwareArtworkPipelinePolicy.mayAnalyzePixelsDirectly(isHardware = false))
        assertEquals(96, HardwareArtworkPipelinePolicy.PIXEL_ANALYSIS_SIDE)
    }

    @Test
    fun hardwareProviderDecodeNeverReentersCpuSourceResizeCache() {
        assertFalse(HardwareArtworkPipelinePolicy.allowDecodedSourceRescaleCache(isHardwarePreferred = true))
        assertTrue(HardwareArtworkPipelinePolicy.allowDecodedSourceRescaleCache(isHardwarePreferred = false))
    }

    @Test
    fun hardwarePlaybackWrapperIsNotCpuNormalizedOnCacheHit() {
        assertFalse(HardwareArtworkPipelinePolicy.allowPlaybackWrapperNormalization(isHardware = true))
        assertTrue(HardwareArtworkPipelinePolicy.allowPlaybackWrapperNormalization(isHardware = false))
    }
}
