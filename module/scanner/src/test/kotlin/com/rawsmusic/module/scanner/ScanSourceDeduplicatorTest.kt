package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScanSourceDeduplicatorTest {
    @Test
    fun `direct path and SAF document URI collapse to complete direct row`() {
        val direct = AudioFile(
            path = "/storage/emulated/0/Music/Same/song.flac",
            title = "Song",
            artist = "Artist",
            album = "Album",
            duration = 180_000,
            fileSize = 42_000_000,
            format = "FLAC"
        )
        val saf = AudioFile(
            path = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FSame/" +
                "document/primary%3AMusic%2FSame%2Fsong.flac",
            title = "song"
        )

        val result = ScanSourceDeduplicator.deduplicate(listOf(saf, direct))

        assertEquals(1, result.size)
        assertSame(direct, result.single())
    }

    @Test
    fun `same file name in different folders remains distinct`() {
        val first = AudioFile(path = "/storage/emulated/0/Music/A/song.flac", title = "Song")
        val second = AudioFile(path = "/storage/emulated/0/Music/B/song.flac", title = "Song")

        val result = ScanSourceDeduplicator.deduplicate(listOf(first, second))

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun `different SAF tree roots with the same document id collapse`() {
        val parentTree = AudioFile(
            path = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
                "document/primary%3AMusic%2FSame%2Fsong.flac"
        )
        val childTree = AudioFile(
            path = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FSame/" +
                "document/primary%3AMusic%2FSame%2Fsong.flac"
        )

        assertEquals(1, ScanSourceDeduplicator.deduplicate(listOf(parentTree, childTree)).size)
    }

    @Test
    fun `cue tracks from one physical file remain distinct`() {
        val first = AudioFile(
            path = "/storage/emulated/0/Music/album.flac",
            cueOffsetMs = 0,
            cueTrackIndex = 1
        )
        val second = first.copy(cueOffsetMs = 120_000, cueTrackIndex = 2)

        assertEquals(2, ScanSourceDeduplicator.deduplicate(listOf(first, second)).size)
    }
}
