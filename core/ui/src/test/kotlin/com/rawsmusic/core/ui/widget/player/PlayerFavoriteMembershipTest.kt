package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class PlayerFavoriteMembershipTest {
    @Test fun removalTargetsOnlyPlaylistsContainingTheCapturedSong() {
        assertEquals(listOf("favorites", "custom"), playerSavedPlaylistIds(
            "song-b", sequenceOf("favorites" to listOf("song-b"), "other" to listOf("song-a"), "custom" to listOf("song-a", "song-b"))
        ))
    }

    @Test fun missingSongNeverSchedulesRemoval() {
        assertEquals(emptyList<String>(), playerSavedPlaylistIds(null, sequenceOf("favorites" to listOf("song-a"))))
    }

    @Test fun membershipInAnySelectedPlaylistFillsTheHeart() {
        assertTrue(playerSavedPlaylistIds("song-b", sequenceOf("first" to listOf("song-a"), "second" to listOf("song-b"))).isNotEmpty())
    }

    @Test fun cancellationOrAnotherSongDoesNotFillTheHeart() {
        assertTrue(playerSavedPlaylistIds("song-b", sequenceOf("first" to listOf("song-a"))).isEmpty())
        assertTrue(playerSavedPlaylistIds("song-b", emptySequence()).isEmpty())
    }

    @Test fun missingSongCannotAppearSaved() {
        assertTrue(playerSavedPlaylistIds(null, sequenceOf("first" to listOf("song-a"))).isEmpty())
    }

    @Test fun removingLastMembershipClearsTheHeart() {
        assertTrue(playerSavedPlaylistIds("song-a", sequenceOf("first" to listOf("song-a"))).isNotEmpty())
        assertTrue(playerSavedPlaylistIds("song-a", sequenceOf("first" to emptyList())).isEmpty())
    }
}
