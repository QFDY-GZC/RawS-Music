package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCachedMediaEnricherTest {
    @Test
    fun mergeUsesFirstAudioStreamAndContainerTags() {
        val original = AudioFile(
            path = "https://example.com/dav/song.m4a",
            title = "song",
            artist = "WebDAV",
            album = "WebDAV remote",
            format = "M4A",
            fileSize = 100L,
        )
        val info = mapOf(
            "duration" to "250.293333",
            "bit_rate" to "320000",
            "title" to "Avid",
            "artist" to "mizuki",
            "album" to "ALDNOAH.ZERO OST",
            "album_artist" to "澤野弘之",
            "genre" to "Soundtrack",
            "composer" to "Hiroyuki Sawano",
            "date" to "2014-09-10",
            "track" to "1/20",
            "disc" to "1/2",
            "stream_0_codec_type" to "video",
            "stream_1_codec_type" to "audio",
            "stream_1_sample_rate" to "44100",
            "stream_1_effective_sample_rate" to "48000",
            "stream_1_channels" to "2",
            "stream_1_bits_per_sample" to "24",
            "stream_1_bit_rate" to "256000",
            "stream_1_codec_name" to "aac",
        )

        val result = RemoteCachedMediaEnricher.merge(
            song = original,
            info = info,
            artworkPath = "file:///cache/webdav.jpg",
            localSize = 99_764_709L,
        )

        assertEquals("Avid", result.title)
        assertEquals("mizuki", result.artist)
        assertEquals("ALDNOAH.ZERO OST", result.album)
        assertEquals("澤野弘之", result.albumArtist)
        assertEquals(250_293L, result.duration)
        assertEquals(48_000, result.sampleRate)
        assertEquals(24, result.bitsPerSample)
        assertEquals(2, result.channelCount)
        assertEquals(256_000, result.bitRate)
        assertEquals("AAC", result.encodingFormat)
        assertEquals(2014, result.year)
        assertEquals(1, result.trackNumber)
        assertEquals(1, result.discNumber)
        assertEquals("Soundtrack", result.genre)
        assertEquals("Hiroyuki Sawano", result.composer)
        assertEquals("file:///cache/webdav.jpg", result.albumArtPath)
        assertEquals(99_764_709L, result.fileSize)
        assertEquals("https://example.com/dav/song.m4a", result.path)
    }

    @Test
    fun mergePreservesExistingValuesWhenTagsAreMissing() {
        val original = AudioFile(
            path = "https://example.com/dav/song.flac",
            title = "Remote title",
            artist = "Remote artist",
            album = "Remote album",
            duration = 1234L,
            sampleRate = 96_000,
            bitsPerSample = 24,
            channelCount = 2,
            albumArtPath = "file:///old.jpg",
        )

        val result = RemoteCachedMediaEnricher.merge(
            song = original,
            info = emptyMap(),
            artworkPath = "",
            localSize = 0L,
        )

        assertEquals(original, result)
    }
}
