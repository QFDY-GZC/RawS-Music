package com.rawsmusic.module.data.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistBiographyRepositoryTest {

    @Test
    fun wikipediaSearchPrefersExactThenIgnoreCaseMatch() {
        val json = """
            {
              "query": {
                "search": [
                  {"title":"Aimer (singer)"},
                  {"title":"aimer"},
                  {"title":"Aimer"}
                ]
              }
            }
        """.trimIndent()

        assertEquals("Aimer", ArtistBiographyRepository.parseWikipediaSearchTitle(json, "Aimer"))
        assertEquals("aimer", ArtistBiographyRepository.parseWikipediaSearchTitle(json, "AIMER"))
        assertNull(ArtistBiographyRepository.parseWikipediaSearchTitle(json, "Other"))
    }

    @Test
    fun neteaseArtistLookupDoesNotAcceptUnrelatedSearchResult() {
        val json = """
            {
              "result": {
                "artists": [
                  {"id":11,"name":"Aimer Tribute"},
                  {"id":22,"name":"aimer"}
                ]
              }
            }
        """.trimIndent()

        assertEquals("22", ArtistBiographyRepository.parseNeteaseArtistId(json, "AIMER"))
        assertNull(ArtistBiographyRepository.parseNeteaseArtistId(json, "Aimer Tribute Band"))
    }

    @Test
    fun neteaseBiographyCombinesBriefAndSections() {
        val json = """
            {
              "briefDesc":"brief",
              "introduction":[
                {"ti":"Career","txt":"career text"},
                {"ti":"","txt":"plain section"}
              ]
            }
        """.trimIndent()

        val text = ArtistBiographyRepository.parseNeteaseBiography(json)
        assertTrue(text.contains("brief"))
        assertTrue(text.contains("Career\ncareer text"))
        assertTrue(text.contains("plain section"))
    }

    @Test
    fun lastFmHtmlParserStripsMarkupAndLicenseFooter() {
        val html = """
            <html><body>
              <div class="wiki-content"><p>Hello &amp; world</p><p>Second line</p>
              User-contributed text is available under the Creative Commons By-SA License
              </div>
            </body></html>
        """.trimIndent()

        assertEquals(
            "Hello & world\n\nSecond line",
            ArtistBiographyRepository.parseLastFmWikiHtml(html)
        )
    }

    @Test
    fun artistImageLookupPrefersExactNeteaseArtist() {
        val json = """
            {
              "result": {
                "artists": [
                  {"name":"Aimer Tribute","picUrl":"https://img.example/tribute.jpg"},
                  {"name":"aimer","picUrl":"https://img.example/folded.jpg"},
                  {"name":"Aimer","picUrl":"https://img.example/exact.jpg"}
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            "https://img.example/exact.jpg",
            ArtistImageCandidateRepository.parseNeteaseArtistImageUrl(json, "Aimer")
        )
        assertNull(ArtistImageCandidateRepository.parseNeteaseArtistImageUrl(json, "Other"))
    }

    @Test
    fun artistImageParsesLastFmOpenGraphImageInEitherAttributeOrder() {
        assertEquals(
            "https://img.example/a.jpg?x=1&y=2",
            ArtistImageCandidateRepository.parseOpenGraphImage(
                "<meta property=\"og:image\" content=\"https://img.example/a.jpg?x=1&amp;y=2\">"
            )
        )
        assertEquals(
            "https://img.example/b.jpg",
            ArtistImageCandidateRepository.parseOpenGraphImage(
                "<meta content='https://img.example/b.jpg' property='og:image'>"
            )
        )
    }
}
