package com.rawsmusic.module.data.repository

import org.junit.Assert.*
import org.junit.Test

class LibraryDeletionFenceTest {
    @Test fun deletedPhysicalFileCannotBeRepublishedOrWrittenFromAnOldBatch() {
        val fence = LibraryDeletionFence()
        fence.deleted("/music/a.flac", "a", wholePath = true)
        assertFalse(fence.isVisible("/music/a.flac", "a"))
        assertFalse(fence.canWrite("/music/a.flac", "a", physicalFilePresent = false))
        assertFalse(fence.isVisible("/music/a.flac", "cue1"))
        assertTrue(fence.isVisible("/music/b.flac", "b"))
    }

    @Test fun deletingOneCueTrackDoesNotDeleteItsSiblingsOrReinsertTheTrack() {
        val fence = LibraryDeletionFence()
        fence.deleted("/music/a.flac", "cue1", wholePath = false)
        assertFalse(fence.canWrite("/music/a.flac", "cue1", physicalFilePresent = true))
        assertTrue(fence.canWrite("/music/a.flac", "cue2", physicalFilePresent = true))
    }

    @Test fun restoredPhysicalFileBecomesVisibleOnlyAfterSuccessfulWrite() {
        val fence = LibraryDeletionFence()
        fence.deleted("/music/a.flac", "a", wholePath = true)
        assertTrue(fence.canWrite("/music/a.flac", "a", physicalFilePresent = true))
        assertFalse(fence.isVisible("/music/a.flac", "a"))
        fence.written("/music/a.flac")
        assertTrue(fence.isVisible("/music/a.flac", "a"))
    }
}
