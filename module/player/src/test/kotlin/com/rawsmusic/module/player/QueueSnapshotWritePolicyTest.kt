package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.*
import org.junit.Test

class QueueSnapshotWritePolicyTest {
    @Test fun cursorOnlyKeepsLargeQueueAndTraversal() {
        val songs = List(10000) { AudioFile(id = it.toLong(), path = "track-$it") }
        val ids = List(10000) { it.toLong() + 1 }
        val policy = QueueSnapshotWritePolicy()
        assertEquals(QueueSnapshotWritePolicy.Changes(true, true, true), policy.changes(songs, ids, emptyList(), "0,1"))
        policy.committed(songs, ids, emptyList(), "0,1")
        repeat(1000) {
            assertEquals(QueueSnapshotWritePolicy.Changes(false, false, false), policy.changes(songs, ids, emptyList(), "0,1"))
        }
    }
    @Test fun structuralPriorityAndTraversalChangesAreIndependent() {
        val song = AudioFile(path = "a", cueTrackIndex = 1)
        val songs = listOf(song, song)
        val ids = listOf(1L, 2L)
        val policy = QueueSnapshotWritePolicy()
        policy.committed(songs, ids, emptyList(), "0,1")
        assertEquals(QueueSnapshotWritePolicy.Changes(false, true, false), policy.changes(songs, ids, listOf(song), "0,1"))
        assertEquals(QueueSnapshotWritePolicy.Changes(false, false, true), policy.changes(songs, ids, emptyList(), "1,0"))
        assertTrue(policy.changes(songs.map { it.copy(cueTrackIndex = 2) }, ids, emptyList(), "0,1").queue)
        assertTrue(policy.changes(songs, listOf(2L, 1L), emptyList(), "0,1").queue)
    }
    @Test fun uncommittedFailureIsRetriedAndDirectPriorityWriteInvalidatesBaseline() {
        val songs = listOf(AudioFile(path = "a"))
        val ids = listOf(1L)
        val policy = QueueSnapshotWritePolicy()
        repeat(2) { assertTrue(policy.changes(songs, ids, emptyList(), "").queue) }
        policy.committed(songs, ids, emptyList(), "")
        policy.invalidatePriority()
        assertEquals(QueueSnapshotWritePolicy.Changes(false, true, false), policy.changes(songs, ids, emptyList(), ""))
    }
}
