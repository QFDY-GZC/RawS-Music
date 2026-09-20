package com.rawsmusic.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AiFastVocalAlignmentCacheStoreTest {
    @Test
    fun cacheKeyIsStableAndIncludesAllInputs() {
        val first = AiFastVocalAlignmentCacheStore.cacheKey(
            audioFingerprint = "audio-a",
            lyricFingerprint = "lyrics-a",
            modelVersion = "model-a",
        )

        assertEquals(
            first,
            AiFastVocalAlignmentCacheStore.cacheKey("audio-a", "lyrics-a", "model-a"),
        )
        assertNotEquals(
            first,
            AiFastVocalAlignmentCacheStore.cacheKey("audio-b", "lyrics-a", "model-a"),
        )
        assertNotEquals(
            first,
            AiFastVocalAlignmentCacheStore.cacheKey("audio-a", "lyrics-b", "model-a"),
        )
        assertNotEquals(
            first,
            AiFastVocalAlignmentCacheStore.cacheKey("audio-a", "lyrics-a", "model-b"),
        )
    }
}
