package com.rawsmusic.metadata

import org.junit.Assert.*
import org.junit.Test

class LibraryMetadataMatchResourcesTest {
    @Test fun lyricsOnlyNeverParsesOldLyricsOrExtractsArtwork() {
        val need = LibraryMetadataMatchResources.needs(LibraryMetadataMatchMode.LYRICS_ONLY,
            { error("unnecessary lyric parse") }, { error("unnecessary artwork probe") })
        assertTrue(need.lyrics)
        assertFalse(need.cover)
    }
    @Test fun fullRematchDoesNotProbeMetadataItWillReplace() {
        val need = LibraryMetadataMatchResources.needs(LibraryMetadataMatchMode.REMATCH_ALL,
            { error("unnecessary lyric parse") }, { error("unnecessary artwork probe") })
        assertTrue(need.lyrics)
        assertTrue(need.cover)
    }
    @Test fun fillMissingStillRespectsExistingMetadata() {
        for (lyrics in listOf(false, true)) for (cover in listOf(false, true)) {
            assertEquals(LibraryMetadataMatchResources.Needs(!lyrics, !cover),
                LibraryMetadataMatchResources.needs(LibraryMetadataMatchMode.FILL_MISSING, { lyrics }, { cover }))
        }
    }
    @Test fun lowHeapDevicesCannotStartEightVms() {
        assertEquals(1, LibraryMetadataMatchResources.workerCount(128L * 1024 * 1024))
        assertEquals(2, LibraryMetadataMatchResources.workerCount(256L * 1024 * 1024))
        assertEquals(4, LibraryMetadataMatchResources.workerCount(1024L * 1024 * 1024))
    }
    @Test fun interruptionUnblocksTheNextBatchWithoutReportingSuccess() {
        LibraryMetadataMatchProgressBus.started("interrupted", 5000)
        LibraryMetadataMatchProgressBus.running("interrupted", 5000, 1200, 1100, 100)
        val token = LibraryMetadataMatchProgressBus.state.value.completionToken
        LibraryMetadataMatchProgressBus.interrupted("interrupted")
        assertFalse(LibraryMetadataMatchProgressBus.state.value.isRunning)
        assertEquals(token, LibraryMetadataMatchProgressBus.state.value.completionToken)
        LibraryMetadataMatchProgressBus.started("next", 5000)
        assertTrue(LibraryMetadataMatchProgressBus.state.value.isRunning)
        assertEquals(0, LibraryMetadataMatchProgressBus.state.value.processed)
        LibraryMetadataMatchProgressBus.interrupted("next")
    }
}
