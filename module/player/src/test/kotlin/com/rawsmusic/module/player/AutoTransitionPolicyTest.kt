package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.scanner.parser.LyricParserBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AutoTransitionPolicyTest {
    @Test fun `sparse lyrics allow loud sustained fade in bounded earlier tail`() {
        val runtime = AutoTransitionRuntime("test")
        val current = song("/a.flac", duration = 300_000L)
        runtime.updateRecipe(AutoTransitionPolicy.build(current, song("/b.flac", album = "Other"),
            LyricData(listOf(LyricLine(0, "instrumental"), LyricLine(1_000, "music")))).recipe)
        // Still loud (roughly -6 to -14 dBFS), but continuously falling over four seconds.
        repeat(21) { index ->
            val amplitude = (16_000 * 10.0.pow(-index * 0.4 / 20.0)).toInt()
            val pcm = ByteArray(256)
            repeat(128) { i -> pcm[i * 2] = amplitude.toByte(); pcm[i * 2 + 1] = (amplitude shr 8).toByte() }
            runtime.observeCurrentPcm(250_000L + index * 200L, pcm, pcm.size, false, false, 16)
        }
        assertNull(runtime.resolveStartPlan(100_000L, current.duration))
        assertNotNull(runtime.resolveStartPlan(254_000L, current.duration))
    }
    @Test
    fun `parser supplied display end permits gentle fallback without claiming vocal end`() {
        val lines = mutableListOf(
            LyricLine(5_000, "one"), LyricLine(60_000, "two"),
            LyricLine(120_000, "three"), LyricLine(220_000, "last"),
        )
        LyricParserBase.fillEndTimes(lines)
        val recipe = AutoTransitionPolicy.build(
            song("/a.flac"), song("/b.flac", album = "Other"), LyricData(lines),
        ).recipe!!
        assertFalse(recipe.vocalEndKnown)
        assertEquals(222000L, recipe.gentleStartPositionMs)
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(recipe)
        assertNotNull(runtime.resolveStartPlan(225_000L, 240_000L))
    }

    @Test
    fun `republishing unchanged recipe retains measured quiet tail`() {
        val recipe = AutoTransitionPolicy.build(
            song("/a.flac"), song("/b.flac", album = "Other"), null,
        ).recipe!!
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(recipe)
        repeat(9) { index ->
            runtime.observeCurrentPcm(220_000L + index * 100L, ByteArray(256), 256, false, false, 16)
        }
        assertNotNull(runtime.resolveStartPlan(220_800L, 240_000L))
        runtime.updateRecipe(recipe.copy())
        assertNotNull(runtime.resolveStartPlan(220_800L, 240_000L))
        runtime.updateRecipe(recipe.copy(targetPath = "/c.flac"))
        assertNull(runtime.resolveStartPlan(220_800L, 240_000L))
    }

    @Test
    fun `double speed envelope window uses the same source time as lyrics`() {
        val timeline = com.rawsmusic.core.common.utils.LyricPlaybackTimeline
        timeline.speed = 2f
        try {
            val runtime = AutoTransitionRuntime("test")
            runtime.updateRecipe(AutoTransitionPolicy.build(
                song("/a.flac"), song("/b.flac", album = "Other"), null,
            ).recipe)
            repeat(9) { index ->
                runtime.observeCurrentPcm(100_000L + index * 100L, ByteArray(256), 256, false, false, 16)
            }
            assertNull(runtime.resolveStartPlan(100_800L, 120_000L))
            assertNotNull(runtime.resolveStartPlan(105_000L, 120_000L))
        } finally {
            timeline.speed = 1f
        }
    }

    @Test
    fun `long trusted outro falls back to gentle mix two seconds into final lyric`() {
        val current = song("/a.flac")
        val lyrics = LyricData(listOf(
            LyricLine(5_000, "one"), LyricLine(60_000, "two"), LyricLine(120_000, "three"),
            LyricLine(180_000, "last", words = listOf(LyricWord("last", 180_000, 195_000))),
        ))
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(AutoTransitionPolicy.build(current, song("/b.flac", album = "Other"), lyrics).recipe)
        assertNull(runtime.resolveStartPlan(181_999L, 240_000L))
        assertEquals(13000, runtime.resolveStartPlan(182_000L, 240_000L)?.preRollMs)
        assertEquals(15000, runtime.resolveStartPlan(195_000L, 240_000L)?.handoverMs)
        // A measured monotonic amplitude fall, well before the final thirty-second window.
        val amplitudes = listOf(16000, 14500, 13000, 11500, 10000, 8500, 7000, 5500,
            4300, 3300, 2600, 2100, 1800, 1500, 1200, 900)
        amplitudes.forEachIndexed { index, amplitude ->
            val pcm = ByteArray(256)
            for (sample in 0 until 128) {
                pcm[sample * 2] = amplitude.toByte()
                pcm[sample * 2 + 1] = (amplitude shr 8).toByte()
            }
            runtime.observeCurrentPcm(196_000L + index * 200L, pcm, pcm.size, false, false, 16)
        }
        assertEquals(15000, runtime.resolveStartPlan(200_000L, 240_000L)?.handoverMs)
    }
    @Test fun doubleSpeedUsesPlaybackTimeForSemanticHandover() {
        val timeline = com.rawsmusic.core.common.utils.LyricPlaybackTimeline
        val old = timeline.speed
        try {
            timeline.speed = 2f
            val runtime = AutoTransitionRuntime("test")
            runtime.updateRecipe(AutoTransitionPolicy.Recipe(
                "/b.flac", AutoTransitionPolicy.Source.LYRICS, 170000L, 180000L, 12000, confidence = 1f,
            ))
            assertNull(runtime.resolveStartPlan(89999L, 120000L))
            assertEquals(6000, runtime.resolveStartPlan(90000L, 120000L)?.handoverMs)
        } finally { timeline.speed = old }
    }
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
        assertEquals(15_000, handoverMs)
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
    fun `repeat one may crossfade a pcm track into a fresh decoder of itself`() {
        val current = song("/a.flac", album = "A")
        val decision = AutoTransitionPolicy.build(
            current = current,
            next = current,
            lyrics = null,
            allowSameItemCrossfade = true,
        )

        assertFalse(decision.forceGapless)
        assertNotNull(decision.recipe)
        assertEquals(AutoTransitionPolicy.Source.ENVELOPE, decision.recipe?.source)
        assertEquals(current.path, decision.recipe?.targetPath)
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
                LyricLine(lastStart, "last", endTime = lastStart + 4_500L),
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
    fun `late final lyric permits gentle overlap but keeps formal end anchor`() {
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
        // Only 2 s remains after the final vocal. A short gentle overlap protects the final
        // phrase; the main exchange still starts at the trusted end instead of cutting it.
        assertEquals(2_000, recipe?.handoverDurationMs)

        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(recipe)
        val gentle = runtime.resolveStartPlan(recipe?.triggerPositionMs ?: 0L, current.duration)
        assertEquals(3_000, gentle?.preRollMs)
        assertEquals(6_000, gentle?.handoverMs)
        val plan = runtime.resolveStartPlan(recipe?.handoverPositionMs ?: 0L, current.duration)
        assertNotNull(plan)
        assertEquals(0, plan?.preRollMs)
        assertEquals(6_000, plan?.handoverMs)
    }

    @Test
    fun `manual compatibility midpoint uses complementary linear amplitude`() {
        assertEquals(0.5f, PcmCrossfadeMixer.gainOut(0.5f), 0.0001f)
        assertEquals(0.5f, PcmCrossfadeMixer.gainIn(0.5f), 0.0001f)
    }

    @Test
    fun `automatic transition caps adjustment at fifteen seconds`() {
        assertEquals(6_000, AutoTransitionPolicy.MIN_HANDOVER_MS)
        assertEquals(12_000, AutoTransitionPolicy.DEFAULT_HANDOVER_MS)
        assertEquals(15_000, AutoTransitionPolicy.MAX_HANDOVER_MS)
        assertEquals(55, AutoTransitionPolicy.SMART_PIVOT_PERCENT)
        assertEquals(15_000, AutoTransitionPolicy.adaptiveHandoverMs(28_000L))
        assertEquals(7_000, AutoTransitionPolicy.adaptiveHandoverMs(7_000L))
        assertEquals(6_600, AutoTransitionPolicy.pivotMs(12_000))
    }

}
