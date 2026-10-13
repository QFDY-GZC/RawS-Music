package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncPlannerCueQuickTest {
    @Test
    fun quickPhysicalRowDoesNotReplaceExistingCueExpansion() {
        val path = "/storage/emulated/0/Music/album.flac"
        val cueOne = AudioFile(
            path = path,
            fileSize = 1000,
            dateModified = 10,
            cueTrackIndex = 1,
            cueOffsetMs = 0,
        )
        val cueTwo = AudioFile(
            path = path,
            fileSize = 1000,
            dateModified = 10,
            cueTrackIndex = 2,
            cueOffsetMs = 217_293,
        )
        val quickPhysical = AudioFile(path = path, fileSize = 1000, dateModified = 10)

        val upserts = LibrarySyncPlanner.calculateQuickVisibleUpserts(
            oldSongs = listOf(cueOne, cueTwo),
            quickSongs = listOf(quickPhysical),
        )

        assertTrue(upserts.isEmpty())
    }

    @Test
    fun quickPhysicalRowIsStillInsertedForGenuinelyNewFile() {
        val quickPhysical = AudioFile(
            path = "/storage/emulated/0/Music/new.flac",
            fileSize = 1000,
            dateModified = 10,
        )

        assertEquals(
            listOf(quickPhysical),
            LibrarySyncPlanner.calculateQuickVisibleUpserts(
                oldSongs = emptyList(),
                quickSongs = listOf(quickPhysical),
            ),
        )
    }

    @Test
    fun finalDeltaDeletesOnlyPhysicalHostWhenCueExpansionSurvives() {
        val path = "/storage/emulated/0/Music/album.flac"
        val physical = AudioFile(path = path, fileSize = 1000, dateModified = 10)
        val cueOne = physical.copy(cueTrackIndex = 1, cueOffsetMs = 0)
        val cueTwo = physical.copy(cueTrackIndex = 2, cueOffsetMs = 217_293)

        val delta = LibrarySyncPlanner.calculateDelta(
            oldSongs = listOf(physical, cueOne, cueTwo),
            newSongs = listOf(cueOne, cueTwo),
        )

        assertEquals(listOf(physical), delta.deletes)
        assertTrue(delta.upserts.isEmpty())
        assertEquals(2, delta.unchanged.size)
    }
}
