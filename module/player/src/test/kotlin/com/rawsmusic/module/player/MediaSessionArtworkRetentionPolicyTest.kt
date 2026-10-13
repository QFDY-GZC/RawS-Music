package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionArtworkRetentionPolicyTest {
    @Test fun incomingArtworkAlwaysWins() {
        assertEquals(
            MediaSessionArtworkSource.INCOMING,
            resolveMediaSessionArtworkSource("song-a", true, "song-a", true, "song-a", true),
        )
    }

    @Test fun textOnlyAndLyricUpdatesRetainTheLastCommittedArtworkForTheSameSong() {
        assertEquals(
            MediaSessionArtworkSource.PREVIOUS_SAME_SONG,
            resolveMediaSessionArtworkSource("song-a", false, "song-a", true, "song-a", true),
        )
        assertEquals(
            MediaSessionArtworkSource.PREVIOUS_SAME_SONG,
            resolveMediaSessionArtworkSource("song-a", false, "song-a", true, null, false),
        )
    }

    @Test fun currentOwnedCoverMayRepairMissingArtworkWhenTheLastCommitWasTextOnly() {
        assertEquals(
            MediaSessionArtworkSource.CURRENT_SONG_COVER,
            resolveMediaSessionArtworkSource("song-a", false, "song-a", false, "song-a", true),
        )
    }

    @Test fun rapidSongChangeMustNeverRetainTheOutgoingArtwork() {
        assertEquals(
            MediaSessionArtworkSource.NONE,
            resolveMediaSessionArtworkSource("song-b", false, "song-a", true, "song-a", true),
        )
        assertEquals(
            MediaSessionArtworkSource.CURRENT_SONG_COVER,
            resolveMediaSessionArtworkSource("song-b", false, "song-a", true, "song-b", true),
        )
    }

    @Test fun emptyOrUnavailableIdentityNeverReusesAnOldCover() {
        assertEquals(
            MediaSessionArtworkSource.NONE,
            resolveMediaSessionArtworkSource("", false, "", true, "", true),
        )
        assertEquals(
            MediaSessionArtworkSource.NONE,
            resolveMediaSessionArtworkSource("song-a", false, null, false, null, false),
        )
    }
}
