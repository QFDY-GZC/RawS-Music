package com.rawsmusic.lyrico.runtime

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.lyrico.LyricoSongCandidate
import com.rawsmusic.lyrico.buildLyricoSongRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostApiRegistryApi5Test {
    @Test
    fun registryAdvertisesPlugin5AndHost4OnlyAfterSupportExists() {
        assertEquals(5, HostApiRegistry.PLUGIN_API_VERSION)
        assertEquals(4, HostApiRegistry.HOST_API_VERSION)
        assertEquals(1..5, HostApiRegistry.SUPPORTED_PLUGIN_API_VERSIONS)
        assertTrue("i18n.getLocale" in HostApiRegistry.SUPPORTED_HOST_APIS)
        assertTrue("i18n.t" in HostApiRegistry.SUPPORTED_HOST_APIS)
    }

    @Test
    fun audioFileRequestIncludesDateAndPreservesLegacyFields() {
        val request = buildLyricoSongRequest(
            song = AudioFile(
                id = 42L,
                title = "Song",
                artist = "Artist",
                album = "Album",
                duration = 123_456L,
                year = 2026,
            ),
            sourceId = "com.example.source",
        )

        assertEquals(42L, request["id"])
        assertEquals("Song", request["title"])
        assertEquals("Artist", request["artist"])
        assertEquals("Album", request["album"])
        assertEquals(123_456L, request["duration"])
        assertEquals("2026", request["date"])
        assertEquals("com.example.source", request["sourceId"])
        assertEquals("com.example.source", request["pluginId"])
        assertEquals(emptyMap<String, String>(), request["fields"])
        assertEquals(emptyMap<String, String>(), request["internal"])
    }

    @Test
    fun audioFileWithoutYearUsesEmptyDate() {
        val request = buildLyricoSongRequest(
            song = AudioFile(id = 7L, title = "No date"),
            sourceId = "com.example.source",
        )

        assertEquals("", request["date"])
    }

    @Test
    fun selectedCandidateKeepsProviderDateInCallbackRequest() {
        val request = buildLyricoSongRequest(
            LyricoSongCandidate(
                pluginId = "com.example.source",
                pluginName = "Example",
                id = "remote-1",
                title = "Song",
                artist = "Artist",
                album = "Album",
                durationMs = 123_456L,
                coverUrl = "",
                supportsCoverSearch = true,
                fields = mapOf("quality" to "lossless"),
                internal = mapOf("token" to "opaque"),
                date = "2026-09-17",
            )
        )

        assertEquals("2026-09-17", request["date"])
        assertEquals(mapOf("quality" to "lossless"), request["fields"])
        assertEquals(mapOf("token" to "opaque"), request["internal"])
    }
}
