package com.rawsmusic.module.data.prefs

import com.rawsmusic.module.data.db.entity.*
import org.junit.Assert.*
import org.junit.Test

class BackupRoomSnapshotCodecTest {
    @Test fun originalHistoryIdsAndExplicitEmptyTablesSurviveRoundTrip() {
        val state = BackupRoomSnapshot(emptyList(), emptyList(), emptyList(),
            listOf(PlaybackHistoryEntity(77, -2, "title", "artist", "album", 42)), emptyList())
        val restored = BackupRoomSnapshotCodec.decode(BackupRoomSnapshotCodec.encode(state))
        assertEquals(state, restored)
        assertEquals(77L, restored.history!!.single().id)
    }
    @Test fun excludedTablesStayAbsentInsteadOfBecomingAnEmptyReplacement() {
        val restored = BackupRoomSnapshotCodec.decode(BackupRoomSnapshotCodec.encode(BackupRoomSnapshot()))
        assertNull(restored.playlists)
        assertNull(restored.history)
    }
    @Test fun partiallySpecifiedGroupsAreRejected() {
        assertTrue(runCatching { BackupRoomSnapshotCodec.decode("""{"version":6,"playlists":[]}""".toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun missingOrWronglyTypedEntityFieldsAreRejectedWithoutDefaulting() {
        val bad = """{"version":6,"stats":[],"history":[{"id":1,"songId":2,"title":"t","artist":"a","album":"b","playedAt":"42"}],"daily":[]}"""
        assertTrue(runCatching { BackupRoomSnapshotCodec.decode(bad.toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { BackupRoomSnapshotCodec.decode(bad.replace(",\"artist\":\"a\"", "").toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }
}
