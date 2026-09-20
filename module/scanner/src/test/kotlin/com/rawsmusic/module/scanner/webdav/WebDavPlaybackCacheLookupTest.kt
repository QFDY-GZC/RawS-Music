package com.rawsmusic.module.scanner.webdav

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavPlaybackCacheLookupTest {
    private val config = WebDavConfig(
        url = "https://example.com/dav/",
        username = "user",
        password = "secret",
    )

    @Test
    fun returnsOnlyPublishedCompleteCacheWithMatchingKnownSize() {
        val root = Files.createTempDirectory("webdav-cache-test").toFile()
        try {
            val remoteUrl = "https://example.com/dav/song.m4a"
            val media = WebDavPlaybackCache.cacheFileForTest(root, config, remoteUrl)
            media.parentFile?.mkdirs()
            media.writeBytes(ByteArray(4096) { 1 })

            assertNull(
                WebDavPlaybackCache.findCachedPathInDirectory(
                    directory = root,
                    config = config,
                    remoteUrl = remoteUrl,
                    knownSize = 4096L,
                )
            )

            WebDavPlaybackCache.markerFileForTest(root, config, remoteUrl)
                .writeText(media.length().toString())

            assertEquals(
                media.absolutePath,
                WebDavPlaybackCache.findCachedPathInDirectory(
                    directory = root,
                    config = config,
                    remoteUrl = remoteUrl,
                    knownSize = 4096L,
                )
            )
            assertNull(
                WebDavPlaybackCache.findCachedPathInDirectory(
                    directory = root,
                    config = config,
                    remoteUrl = remoteUrl,
                    knownSize = 8192L,
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
