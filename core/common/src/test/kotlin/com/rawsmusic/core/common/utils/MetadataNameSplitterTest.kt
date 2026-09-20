package com.rawsmusic.core.common.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataNameSplitterTest {

    @Test
    fun splitsArtistsAndKeepsProtectedNamesIntact() {
        val config = MetadataNameSplitConfig(
            artistSeparators = listOf("/", "feat.", "&", ","),
            artistProtectedNames = listOf("AC/DC")
        )

        assertEquals(
            listOf("AC/DC", "Guest"),
            MetadataNameSplitter.splitArtistNames("AC/DC / Guest", config)
        )
    }

    @Test
    fun splitsGenresAndNormalizesWhitespace() {
        val config = MetadataNameSplitConfig(genreSeparators = listOf(";"))

        assertEquals(
            listOf("Rock", "Pop"),
            MetadataNameSplitter.splitGenreNames(" Rock ;  Pop ", config)
        )
    }

    @Test
    fun ignoreCaseMergesNamesWhenMatching() {
        val config = MetadataNameSplitConfig(
            artistSeparators = listOf("/"),
            ignoreCase = true
        )

        assertEquals(
            true,
            MetadataNameSplitter.matchesArtistName("Alice / Bob", "alice", config)
        )
        assertEquals("alice", MetadataNameSplitter.identityKey("Alice", ignoreCase = true))
    }
}
