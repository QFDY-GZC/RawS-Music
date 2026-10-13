package com.rawsmusic.core.ui.widget.player

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongContributorsDialogModelTest {
    @Test
    fun collaborationsUseDistinctAlbumTracksAndArtistTags() {
        val first = AudioFile(
            path = "/Music/Album/01.flac",
            title = "First",
            album = "Sample Album",
            albumId = 2026,
            albumArtist = "Alice",
            artist = "Alice feat. Bob",
            composer = "Alice; Carol",
        )
        val second = first.copy(
            path = "/Music/Album/02.flac",
            artist = "Alice",
        )
        val third = first.copy(
            path = "/Music/Album/03.flac",
            artist = "Carol & Alice",
        )
        val unrelated = first.copy(
            path = "/Elsewhere/01.flac",
            albumId = 3026,
            artist = "Stranger",
        )
        val result = buildSongContributorsSummary(
            first,
            listOf(first, second, third, third, unrelated),
        )
        assertEquals(3, result.trackCount)
        assertEquals(2, result.collaborationTrackCount)
        assertEquals(3, result.participants.size)
        assertEquals(listOf("Alice", "Bob"), result.songArtists)
        assertEquals(listOf("Alice", "Carol"), result.composers)
        assertEquals(3, result.participants.first { it.name == "Alice" }.songCount)
        assertEquals(1, result.participants.first { it.name == "Bob" }.songCount)
        assertEquals(1, result.participants.first { it.name == "Carol" }.songCount)
        assertFalse(result.participants.any { it.name == "Stranger" })
    }

    @Test
    fun artistParsingPreservesCommasInsideAnArtistName() {
        assertEquals(
            listOf("Tyler, The Creator", "A\$AP Rocky"),
            splitCreditsArtists("Tyler, The Creator feat. A\$AP Rocky"),
        )
        assertEquals(listOf("Alice", "Bob", "Carol"), splitCreditsArtists("Alice、Bob / Carol"))
    }

    @Test
    fun unnamedAlbumWithoutIdDoesNotCollectSameNameFromOtherFolders() {
        val song = AudioFile(
            path = "/first/01.mp3",
            album = "Greatest Hits",
            artist = "Alice",
        )
        val other = song.copy(path = "/second/02.mp3", artist = "Other Artist")
        val included = song.copy(path = "/first/03.mp3", artist = "Bob")
        val result = buildSongContributorsSummary(song, listOf(other, included))
        assertEquals(2, result.trackCount)
        assertTrue(result.participants.any { it.name == "Bob" })
        assertFalse(result.participants.any { it.name == "Other Artist" })
    }

    @Test
    fun albumHyperlinkUsesTheSameRawKeyAsTheAlbumsProvider() {
        val track = AudioFile(
            path = "/Music/Duets/track.flac",
            album = "Duets",
            albumArtist = "Various Artists",
            artist = "Alice feat. Bob",
        )
        assertEquals(
            "Various Artists␟Duets",
            songCreditsAlbumRouteKey(track, listOf(track)),
        )
        assertEquals(null, songCreditsAlbumRouteKey(track, emptyList()))
    }

    @Test
    fun featuredArtistsAndComposerPartsRemainNavigableEvenWithoutStandaloneGroups() {
        val duet = AudioFile(
            path = "/Music/Duets/track.flac",
            artist = "Alice feat. Bob",
            composer = "Carol; Dana",
        )
        val solo = duet.copy(
            path = "/Music/Solos/track.flac",
            artist = "Eve",
            composer = "Frank",
        )
        assertEquals(listOf(duet), creditedSongsForArtist("Bob", listOf(duet, solo)))
        assertEquals(listOf(duet), creditedSongsForComposer("Dana", listOf(duet, solo)))
        assertTrue(creditedSongsForArtist("Unknown", listOf(duet, solo)).isEmpty())
    }
}
