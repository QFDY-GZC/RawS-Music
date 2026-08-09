package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPlayRequestResolverTest {
    private fun song(path: String, cue: Long = 0L, track: Int = 0) = AudioFile(
        path = path,
        title = path,
        cueOffsetMs = cue,
        cueTrackIndex = track,
    )

    @Test
    fun `corrects stale requested index by playback identity`() {
        val warnings = mutableListOf<String>()
        val resolver = PlayerPlayRequestResolver(warnings::add)
        val first = song("/a.flac")
        val target = song("/b.flac", cue = 15_000L, track = 2)

        val resolved = resolver.resolve(
            song = target,
            requestedQueue = listOf(first, target),
            requestedIndex = 0,
            currentQueue = emptyList(),
        )

        assertEquals(1, resolved.index)
        assertEquals(listOf(first, target), resolved.queue)
        assertTrue(warnings.single().contains("index corrected"))
    }

    @Test
    fun `isolates selection missing from supplied queue`() {
        val resolver = PlayerPlayRequestResolver {}
        val target = song("/target.flac")

        val resolved = resolver.resolve(
            song = target,
            requestedQueue = listOf(song("/other.flac")),
            requestedIndex = 0,
            currentQueue = emptyList(),
        )

        assertEquals(0, resolved.index)
        assertEquals(listOf(target), resolved.queue)
    }

    @Test
    fun `manual switch only applies to active different song with fade path`() {
        val resolver = PlayerPlayRequestResolver {}
        val current = song("/a.flac")
        val requested = song("/b.flac")

        assertFalse(resolver.shouldUseManualSwitch(current, current, true, true, true, 120))
        assertFalse(resolver.shouldUseManualSwitch(current, requested, false, false, true, 120))
        assertTrue(resolver.shouldUseManualSwitch(current, requested, true, false, true, 0))
        assertTrue(resolver.shouldUseManualSwitch(current, requested, false, true, false, 120))
        assertFalse(resolver.shouldUseManualSwitch(current, requested, true, false, false, 0))
    }
}
