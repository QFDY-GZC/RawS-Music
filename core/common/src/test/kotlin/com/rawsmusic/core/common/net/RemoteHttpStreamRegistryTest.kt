package com.rawsmusic.core.common.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteHttpStreamRegistryTest {
    @Test
    fun lateBoundHeadersOverrideStaticHeadersAtLookupTime() {
        val url = "https://example.com/test/${System.nanoTime()}.flac"
        var token = "one"
        RemoteHttpStreamRegistry.register(
            url = url,
            headers = mapOf("Authorization" to "Bearer static", "X-Static" to "yes"),
            userAgent = "RawSMusic/Test",
            owner = "test",
            headerProvider = {
                mapOf("Authorization" to "Bearer $token", "X-Dynamic" to "now")
            },
        )

        val entry = RemoteHttpStreamRegistry.lookup(url)!!
        assertEquals("Bearer one", entry.resolveHeaders(url)["Authorization"])
        assertEquals("yes", entry.resolveHeaders(url)["X-Static"])
        token = "two"
        assertEquals("Bearer two", entry.resolveHeaders(url)["Authorization"])
        assertEquals("now", entry.resolveHeaders(url)["X-Dynamic"])

        RemoteHttpStreamRegistry.remove(url)
        assertNull(RemoteHttpStreamRegistry.lookup(url))
    }

    @Test
    fun providerHeaderNewlinesAreRemoved() {
        val url = "https://example.com/test/${System.nanoTime()}.flac"
        RemoteHttpStreamRegistry.register(
            url = url,
            headers = emptyMap(),
            headerProvider = { mapOf("X-Test\r\n" to "a\r\nb") },
        )

        assertEquals("ab", RemoteHttpStreamRegistry.lookup(url)!!.resolveHeaders(url)["X-Test"])
        RemoteHttpStreamRegistry.remove(url)
    }

    @Test
    fun lateBoundTransportUrlDoesNotChangeSourceIdentity() {
        val sourceUrl = "https://example.com/test/${System.nanoTime()}.m4a"
        var transportUrl = "http://127.0.0.1:12345/rawsmusic-webdav/token/file.m4a"
        RemoteHttpStreamRegistry.register(
            url = sourceUrl,
            headers = mapOf("Authorization" to "Basic abc"),
            owner = "webdav",
            urlProvider = { transportUrl },
        )

        val entry = RemoteHttpStreamRegistry.lookup(sourceUrl)!!
        assertEquals(transportUrl, entry.resolveUrl(sourceUrl))
        transportUrl = "http://127.0.0.1:23456/rawsmusic-webdav/token/file.m4a"
        assertEquals(transportUrl, entry.resolveUrl(sourceUrl))

        RemoteHttpStreamRegistry.remove(sourceUrl)
    }

    @Test
    fun transportUrlDefaultsToOriginalSource() {
        val sourceUrl = "https://example.com/test/${System.nanoTime()}.flac"
        RemoteHttpStreamRegistry.register(sourceUrl, emptyMap())
        assertEquals(sourceUrl, RemoteHttpStreamRegistry.lookup(sourceUrl)!!.resolveUrl(sourceUrl))
        RemoteHttpStreamRegistry.remove(sourceUrl)
    }
}
