package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTransitionPolicyTest {
    private fun song(
        path: String,
        duration: Long = 240_000L,
        album: String = "Album",
        albumId: Long = -1L,
        track: Int = 0,
        disc: Int = 1,
        format: String = "FLAC",
        bits: Int = 24,
    ) = AudioFile(
        path = path,
        title = path,
        duration = duration,
        album = album,
        albumId = albumId,
        trackNumber = track,
        discNumber = disc,
        format = format,
        bitsPerSample = bits,
    )

    @Test
    fun `lyrics recipe starts formal fade after final word ends`() {
        val current = song("/a.flac")
        val next = song("/b.flac", album = "Other")
        val lyrics = LyricData(
            lines = listOf(
                LyricLine(5_000, "one"),
                LyricLine(60_000, "two"),
                LyricLine(120_000, "three"),
                LyricLine(180_000, "four"),
                LyricLine(
                    timeStamp = 220_000,
                    text = "last line",
                    words = listOf(
                        LyricWord("last", 220_000, 222_000),
                        LyricWord("line", 222_100, 224_000),
                    ),
                ),
            ),
        )

        val decision = AutoTransitionPolicy.build(current, next, lyrics)
        val recipe = decision.recipe
        assertFalse(decision.forceGapless)
        assertNotNull(recipe)
        assertEquals(AutoTransitionPolicy.Source.LYRICS, recipe?.source)
        val handoverStart = recipe?.handoverPositionMs ?: 0L
        val handoverMs = recipe?.handoverDurationMs ?: 0
        assertEquals(224_000L, handoverStart)
        assertEquals(16_000, handoverMs)
        assertEquals(220_000L, recipe?.triggerPositionMs)
        assertEquals(1.0f, recipe?.confidence ?: 0f, 0.0001f)
    }

    @Test
    fun `too little lyric data falls back to envelope`() {
        val current = song("/a.flac")
        val next = song("/b.flac", album = "Other")
        val lyrics = LyricData(lines = listOf(LyricLine(230_000, "only one")))

        val decision = AutoTransitionPolicy.build(current, next, lyrics)

        assertEquals(AutoTransitionPolicy.Source.ENVELOPE, decision.recipe?.source)
        assertFalse(decision.forceGapless)
    }

    @Test
    fun `sequential album pair protects original gapless boundary`() {
        val current = song("/a.flac", albumId = 7, track = 4)
        val next = song("/b.flac", albumId = 7, track = 5)

        val decision = AutoTransitionPolicy.build(current, next, null)

        assertTrue(decision.forceGapless)
        assertNull(decision.recipe)
        assertEquals("sequential_album_gapless", decision.reason)
    }

    @Test
    fun `repeat one same item does not mix a track with itself`() {
        val current = song("/a.flac", album = "A")
        val decision = AutoTransitionPolicy.build(current, current, null)

        assertTrue(decision.forceGapless)
        assertNull(decision.recipe)
        assertEquals("same_item_repeat_gapless", decision.reason)
    }

    @Test
    fun `dsd never enters pcm auto mix`() {
        val current = song("/a.dsf", album = "A", format = "DSF", bits = 1)
        val next = song("/b.flac", album = "B")

        val decision = AutoTransitionPolicy.build(current, next, null)

        assertTrue(decision.forceGapless)
        assertNull(decision.recipe)
    }

    @Test
    fun `known short current or follow track stays gapless`() {
        val long = song("/long.flac", duration = 30_000L, album = "Long")
        val short = song("/short.flac", duration = 29_999L, album = "Short")

        listOf(
            AutoTransitionPolicy.build(short, long, null),
            AutoTransitionPolicy.build(long, short, null),
        ).forEach { decision ->
            assertTrue(decision.forceGapless)
            assertNull(decision.recipe)
            assertEquals("short_track_gapless", decision.reason)
        }
        assertEquals(30_000L, AutoTransitionPolicy.MIN_TRACK_DURATION_MS)
    }

    @Test
    fun `runtime rejects stale recipe when resolved duration is below threshold`() {
        val decision = AutoTransitionPolicy.build(
            song("/a.flac", duration = 60_000L, album = "A"),
            song("/b.flac", duration = 60_000L, album = "B"),
            null,
        )
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(decision.recipe)

        assertNull(runtime.resolveStartPlan(positionMs = 20_000L, durationMs = 29_999L))
    }
    @Test
    fun `long final lyric does not duck lead before its end`() {
        val current = song("/a.flac", duration = 240_000L)
        val next = song("/b.flac", album = "Other")
        val lyrics = LyricData(
            lines = listOf(
                LyricLine(5_000, "one"),
                LyricLine(60_000, "two"),
                LyricLine(120_000, "three"),
                LyricLine(180_000, "four"),
                LyricLine(
                    timeStamp = 210_000,
                    text = "long final line",
                    words = listOf(LyricWord("tail", 210_000, 225_000)),
                ),
            ),
        )

        val recipe = AutoTransitionPolicy.build(current, next, lyrics).recipe
        assertNotNull(recipe)
        val handover = recipe?.handoverPositionMs ?: 0L
        assertEquals(225_000L, handover)
        assertTrue(handover > 210_000L)
        assertEquals(15_000, recipe?.handoverDurationMs)
    }

    @Test
    fun `lyrics semantic window allows up to thirty seconds but not more`() {
        val current = song("/a.flac", duration = 240_000L)
        val next = song("/b.flac", album = "Other")
        fun lyricsAt(lastStart: Long) = LyricData(
            lines = listOf(
                LyricLine(5_000, "one"),
                LyricLine(60_000, "two"),
                LyricLine(120_000, "three"),
                LyricLine(180_000, "four"),
                LyricLine(lastStart, "last"),
            ),
        )

        assertEquals(
            AutoTransitionPolicy.Source.LYRICS,
            AutoTransitionPolicy.build(current, next, lyricsAt(210_000L)).recipe?.source,
        )
        assertEquals(
            AutoTransitionPolicy.Source.ENVELOPE,
            AutoTransitionPolicy.build(current, next, lyricsAt(209_999L)).recipe?.source,
        )
        assertEquals(30_000L, AutoTransitionPolicy.MAX_LYRIC_PREROLL_MS)
        assertEquals(30_000L, AutoTransitionPolicy.ENVELOPE_LOOKAHEAD_MS)
    }

    @Test
    fun `late final lyric prioritizes end anchor over forcing an early eight second fade`() {
        val current = song("/a.flac", duration = 240_000L)
        val next = song("/b.flac", album = "Other")
        val lyrics = LyricData(
            lines = listOf(
                LyricLine(5_000, "one"),
                LyricLine(60_000, "two"),
                LyricLine(120_000, "three"),
                LyricLine(180_000, "four"),
                LyricLine(
                    timeStamp = 235_000,
                    text = "very late final line",
                    words = listOf(LyricWord("tail", 235_000, 238_000)),
                ),
            ),
        )

        val recipe = AutoTransitionPolicy.build(current, next, lyrics).recipe
        assertNotNull(recipe)
        assertEquals(AutoTransitionPolicy.Source.LYRICS, recipe?.source)
        assertEquals(238_000L, recipe?.handoverPositionMs)
        // Only 2 s of physical PCM remains after the requested lyric-end anchor. Do not pull the
        // fade eight seconds earlier just to satisfy the nominal window.
        assertEquals(2_000, recipe?.handoverDurationMs)

        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(recipe)
        assertNull(runtime.resolveStartPlan(recipe?.triggerPositionMs ?: 0L, current.duration))
        val plan = runtime.resolveStartPlan(recipe?.handoverPositionMs ?: 0L, current.duration)
        assertNotNull(plan)
        assertEquals(0, plan?.preRollMs)
        assertEquals(2_000, plan?.handoverMs)
    }

    @Test
    fun `manual compatibility midpoint uses complementary linear amplitude`() {
        assertEquals(0.5f, PcmCrossfadeMixer.gainOut(0.5f), 0.0001f)
        assertEquals(0.5f, PcmCrossfadeMixer.gainIn(0.5f), 0.0001f)
    }

    @Test
    fun `automatic transition uses adaptive AM style timing`() {
        assertEquals(6_000, AutoTransitionPolicy.MIN_HANDOVER_MS)
        assertEquals(12_000, AutoTransitionPolicy.DEFAULT_HANDOVER_MS)
        assertEquals(20_000, AutoTransitionPolicy.MAX_HANDOVER_MS)
        assertEquals(55, AutoTransitionPolicy.SMART_PIVOT_PERCENT)
        assertEquals(20_000, AutoTransitionPolicy.adaptiveHandoverMs(28_000L))
        assertEquals(7_000, AutoTransitionPolicy.adaptiveHandoverMs(7_000L))
        assertEquals(6_600, AutoTransitionPolicy.pivotMs(12_000))
    }

}
