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
        assertEquals(AutoTransitionPolicy.LEAD_FADE_WINDOW_MS, handoverMs)
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
        assertEquals(AutoTransitionPolicy.LEAD_FADE_WINDOW_MS, recipe?.handoverDurationMs)
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
    fun `automatic follow uses an audible subordinate floor and lead fade owns final eight seconds`() {
        assertEquals(-50f, AutoTransitionPolicy.INCOMING_START_DB, 0.0001f)
        assertEquals(-20f, AutoTransitionPolicy.INCOMING_UNDERLAY_CEILING_DB, 0.0001f)
        assertEquals(-26f, AutoTransitionPolicy.DEFAULT_INCOMING_FLOOR_DB, 0.0001f)
        assertEquals(8_000, AutoTransitionPolicy.LEAD_FADE_WINDOW_MS)
        assertEquals(1_000, AutoTransitionPolicy.POST_DOMINANCE_TAIL_MS)
        assertEquals(-36f, AutoTransitionPolicy.LEAD_DB_AT_DOMINANCE, 0.0001f)
    }

}
