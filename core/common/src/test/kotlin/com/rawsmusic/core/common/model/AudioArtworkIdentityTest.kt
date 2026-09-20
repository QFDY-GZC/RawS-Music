package com.rawsmusic.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioArtworkIdentityTest {
    @Test
    fun realAudioPathWinsOverExternalArtworkMetadata() {
        assertEquals(
            "audio:///music/song.flac|123|456",
            resolveAudioFirstArtworkKey(
                audioPath = "/music/song.flac",
                fileSize = 123L,
                dateModified = 456L,
                externalArtworkPath = "file:///music/folder.jpg"
            )
        )
    }

    @Test
    fun contentAudioUriDoesNotPretendToBeAFileBackedAudioKey() {
        assertEquals(
            "content://media/external/audio/albumart/7",
            resolveAudioFirstArtworkKey(
                audioPath = "content://com.android.providers.media.documents/document/audio:42",
                fileSize = 123L,
                dateModified = 456L,
                externalArtworkPath = "content://media/external/audio/albumart/7"
            )
        )
    }

    @Test
    fun externalArtworkRemainsAvailableForVirtualRows() {
        assertEquals(
            "content://media/external/audio/albumart/7",
            resolveAudioFirstArtworkKey(
                audioPath = "",
                fileSize = 0L,
                dateModified = 0L,
                externalArtworkPath = "content://media/external/audio/albumart/7"
            )
        )
    }

    @Test
    fun remoteAudioUrlIsNotReusedAsItsOwnArtworkUrl() {
        val mediaUrl = "https://example.com/dav/song.m4a"
        assertEquals(
            "",
            resolveAudioFirstArtworkKey(
                audioPath = mediaUrl,
                fileSize = 1024L,
                dateModified = 0L,
                externalArtworkPath = mediaUrl
            )
        )
    }

    @Test
    fun distinctRemoteArtworkUrlIsStillAllowed() {
        assertEquals(
            "https://example.com/covers/song.jpg",
            resolveAudioFirstArtworkKey(
                audioPath = "https://example.com/dav/song.m4a",
                fileSize = 1024L,
                dateModified = 0L,
                externalArtworkPath = "https://example.com/covers/song.jpg"
            )
        )
    }
}
