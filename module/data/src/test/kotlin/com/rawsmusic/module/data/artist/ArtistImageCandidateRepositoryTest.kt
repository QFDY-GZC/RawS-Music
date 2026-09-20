package com.rawsmusic.module.data.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistImageCandidateRepositoryTest {

    @Test
    fun neteaseImagePrefersExactArtistOverFoldedMatch() {
        val raw = """
            {
              "result": {
                "artists": [
                  {"name":"aimer","picUrl":"https://example.com/folded.jpg"},
                  {"name":"Aimer","picUrl":"https://example.com/exact.jpg"}
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            "https://example.com/exact.jpg",
            ArtistImageCandidateRepository.parseNeteaseArtistImageUrl(raw, "Aimer"),
        )
    }

    @Test
    fun neteaseImageRejectsUnrelatedSearchResult() {
        val raw = """
            {
              "result": {
                "artists": [
                  {"name":"Aimer Tribute","picUrl":"https://example.com/tribute.jpg"}
                ]
              }
            }
        """.trimIndent()

        assertNull(ArtistImageCandidateRepository.parseNeteaseArtistImageUrl(raw, "Aimer"))
    }
}
