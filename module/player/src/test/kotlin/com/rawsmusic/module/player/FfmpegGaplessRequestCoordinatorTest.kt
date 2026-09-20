package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class FfmpegGaplessRequestCoordinatorTest {
    @Test
    fun manualRequestOwnershipIsArmedBeforePrepareAndCanBeCleared() {
        var requested = false
        var target: String? = null
        var generation = -1
        val coordinator = FfmpegGaplessRequestCoordinator(
            tag = "test",
            clearNextRequest = {},
            bumpPrepareEpoch = {},
            manualRequested = { requested },
            setManualRequested = { requested = it },
            manualTargetPath = { target },
            setManualTargetPath = { target = it },
            manualGeneration = { generation },
            setManualGeneration = { generation = it },
            cancelUsbTrackSwitch = {},
            resetCrossfade = {},
            clearGaplessDecoder = {},
            isCurrentPlayback = { _, _ -> true },
            currentGeneration = { 7 },
            currentSourcePath = { "current.flac" },
        )

        coordinator.armManualCrossfadeRequest("next.flac", 7)

        assertTrue(requested)
        assertEquals("next.flac", target)
        assertEquals(7, generation)
        assertTrue(coordinator.isManualCrossfadeTrigger("next.flac", 7))

        coordinator.clearManualCrossfadeRequest("started")
        assertFalse(requested)
        assertNull(target)
        assertEquals(-1, generation)
    }
}
