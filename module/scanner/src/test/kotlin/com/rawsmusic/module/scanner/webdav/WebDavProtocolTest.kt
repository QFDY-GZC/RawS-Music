package com.rawsmusic.module.scanner.webdav

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavProtocolTest {
    @Test
    fun collectionNormalizationKeepsQueryAfterPathSlash() {
        assertEquals(
            "https://example.com/dav/?token=abc",
            WebDavUrlTools.normalizeCollectionUrl("https://example.com/dav?token=abc"),
        )
    }

    @Test
    fun relativeHrefResolvesAgainstCurrentDirectoryNotConfiguredRoot() {
        assertEquals(
            "https://example.com/dav/music/Album/Track%201.flac",
            WebDavUrlTools.resolveHref(
                "https://example.com/dav/music/Album/",
                "Track%201.flac",
                directoryHint = false,
            ),
        )
    }

    @Test
    fun parentNeverEscapesConfiguredRoot() {
        assertEquals(
            "https://example.com/dav/music/",
            WebDavUrlTools.parentWithinRoot(
                "https://example.com/dav/",
                "https://example.com/dav/music/Album/",
            ),
        )
        assertNull(
            WebDavUrlTools.parentWithinRoot(
                "https://example.com/dav/",
                "https://example.com/dav/",
            ),
        )
        assertNull(
            WebDavUrlTools.parentWithinRoot(
                "https://example.com/dav/",
                "https://evil.example/dav/music/",
            ),
        )
    }

    @Test
    fun credentialScopeRejectsCrossOriginPortAndDowngrade() {
        assertTrue(
            WebDavUrlTools.isCredentialSafeTarget(
                "https://example.com/dav/",
                "https://example.com/dav/file.flac",
            ),
        )
        assertTrue(
            WebDavUrlTools.isCredentialSafeTarget(
                "http://example.com/dav/",
                "https://example.com/dav/file.flac",
            ),
        )
        assertFalse(
            WebDavUrlTools.isCredentialSafeTarget(
                "https://example.com/dav/",
                "http://example.com/dav/file.flac",
            ),
        )
        assertFalse(
            WebDavUrlTools.isCredentialSafeTarget(
                "https://example.com/dav/",
                "https://other.example/dav/file.flac",
            ),
        )
        assertFalse(
            WebDavUrlTools.isCredentialSafeTarget(
                "https://example.com:8443/dav/",
                "https://example.com:9443/dav/file.flac",
            ),
        )
    }

    @Test
    fun persistedCredentialRootMatchUsesPathBoundary() {
        assertTrue(
            WebDavUrlTools.isWithinConfiguredRoot(
                "https://example.com/dav/music/",
                "https://example.com/dav/music/Album/track.flac",
            ),
        )
        assertFalse(
            WebDavUrlTools.isWithinConfiguredRoot(
                "https://example.com/dav/music/",
                "https://example.com/dav/music-archive/track.flac",
            ),
        )
        assertFalse(
            WebDavUrlTools.isWithinConfiguredRoot(
                "https://example.com/dav/music/",
                "https://example.com/dav/other/track.flac",
            ),
        )
    }

    @Test
    fun legacyUserInfoIsRemovedFromPersistedMediaUrl() {
        assertEquals(
            "https://example.com/dav/music/track.flac?x=1",
            WebDavUrlTools.stripUserInfo("https://user:secret@example.com/dav/music/track.flac?x=1"),
        )
    }

    @Test
    fun customHeaderCodecAcceptsBearerAndApiKey() {
        val headers = WebDavHeaderCodec.parse(
            """
                # reverse proxy token
                Authorization: Bearer abc.def
                X-API-Key: secret-value
            """.trimIndent(),
        )
        assertEquals("Bearer abc.def", headers["Authorization"])
        assertEquals("secret-value", headers["X-API-Key"])
    }

    @Test
    fun customUserAgentOverridesPlaybackClientIdentity() {
        val request = WebDavClient().buildPlaybackRequestForUrl(
            config = WebDavConfig(
                url = "https://example.com/dav/",
                extraHeaders = mapOf("User-Agent" to "Zotero/8.0"),
            ),
            targetUrl = "https://example.com/dav/track.flac",
            refreshAuthentication = false,
        )

        assertEquals("Zotero/8.0", request.userAgent)
        assertFalse(request.headers.keys.any { it.equals("User-Agent", true) })
    }

    @Test
    fun customHeaderCodecRejectsHttpFramingHeaders() {
        val error = runCatching { WebDavHeaderCodec.parse("Host: evil.example") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("managed by the HTTP stack"))
    }

    @Test
    fun customHeaderCodecRejectsMalformedLines() {
        val error = runCatching { WebDavHeaderCodec.parse("Authorization Bearer token") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("expected"))
    }

    @Test
    fun multiStatusUsesOnlySuccessfulPropstatProperties() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/Music/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Wrong Name</d:displayname>
                  </d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Music</d:displayname>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val item = WebDavMultiStatusParser.parse(xml).single()
        assertEquals("/dav/Music/", item.href)
        assertEquals("Music", item.displayName)
        assertTrue(item.isDirectory)
    }

    @Test
    fun digestSupportsSha256AndIncrementsNonceCount() {
        val digest = WebDavDigestAuth()
        val challenge = digest.parseChallenges(
            listOf("Digest realm=\"dav\", nonce=\"abc123\", algorithm=SHA-256, qop=\"auth,auth-int\""),
        ).single()
        val request = Request.Builder().url("https://example.com/dav/file.flac?x=1").get().build()

        val first = digest.authorization(request, "user", "pass", challenge).orEmpty()
        val second = digest.authorization(request, "user", "pass", challenge).orEmpty()

        assertTrue(first.startsWith("Digest "))
        assertTrue(first.contains("algorithm=SHA-256"))
        assertTrue(first.contains("qop=auth"))
        assertTrue(first.contains("uri=\"/dav/file.flac?x=1\""))
        assertTrue(first.contains("nc=00000001"))
        assertTrue(second.contains("nc=00000002"))
        assertFalse(first == second)
    }
}
