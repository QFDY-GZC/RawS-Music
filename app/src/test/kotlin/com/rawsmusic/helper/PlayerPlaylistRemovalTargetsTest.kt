package com.rawsmusic.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPlaylistRemovalTargetsTest {
    @Test fun removesOnlyCheckedMembershipsOfCapturedSong() {
        assertEquals(listOf("mix"), playerPlaylistRemovalTargets(
            "song-a", setOf("mix", "unrelated"),
            sequenceOf("favorites" to listOf("song-a"), "mix" to listOf("song-a", "song-b"), "unrelated" to listOf("song-b"))
        ))
    }

    @Test fun uncheckedOrCancelledSelectionRemovesNothing() {
        assertEquals(emptyList<String>(), playerPlaylistRemovalTargets("song-a", emptySet(), sequenceOf("mix" to listOf("song-a"))))
    }

    @Test fun staleRemovedPlaylistIsNotTargeted() {
        assertEquals(emptyList<String>(), playerPlaylistRemovalTargets("song-a", setOf("deleted"), sequenceOf("mix" to listOf("song-a"))))
    }
}
