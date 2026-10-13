package com.rawsmusic.module.player.lyrics

import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import org.junit.Assert.*
import org.junit.Test

class BluetoothLyricTimelineTest {
    private val lyrics = LyricData(lines = listOf(
        LyricLine(1000, "first", translation = "一"),
        LyricLine(3000, "second", translation = "二"),
        LyricLine(5000, ""),
    ))

    @Test fun serviceClockAdvancesLinesWithoutAnyActivityCallback() {
        assertEquals("first", bluetoothLyricLine(true, "A", "A", lyrics, 1000)?.text)
        assertEquals("second", bluetoothLyricLine(true, "A", "A", lyrics, 3200)?.text)
        assertEquals("二", bluetoothLyricLine(true, "A", "A", lyrics, 3200)?.translation)
    }

    @Test fun seekBackReevaluatesTheLineRatherThanRetainingTheLastPublishedText() {
        bluetoothLyricLine(true, "A", "A", lyrics, 3200)
        assertEquals("first", bluetoothLyricLine(true, "A", "A", lyrics, 1500)?.text)
    }

    @Test fun disabledMissingAndStaleLyricsRestoreRealArtist() {
        assertNull(bluetoothLyricLine(false, "A", "A", lyrics, 3200))
        assertNull(bluetoothLyricLine(true, "B", "A", lyrics, 3200))
        assertNull(bluetoothLyricLine(true, null, null, lyrics, 3200))
        assertNull(bluetoothLyricLine(true, "A", "A", null, 3200))
    }

    @Test fun introAndBlankLineNeverLeakThePreviousLyric() {
        assertNull(bluetoothLyricLine(true, "A", "A", lyrics, 999))
        assertNull(bluetoothLyricLine(true, "A", "A", lyrics, 5000))
    }

    @Test fun metadataForANewTrackRejectsTheOutgoingPayloadEvenBeforeClearRuns() {
        assertNull(bluetoothLyricDisplayArtist(true, "B", "A", "old", "", false))
        assertEquals("new", bluetoothLyricDisplayArtist(true, "B", "B", "new", "", false))
    }

    @Test fun togglesImmediatelyChangeArtistWithoutWaitingForANewLine() {
        assertEquals("first", bluetoothLyricDisplayArtist(true, "A", "A", "first", "一", false))
        assertEquals("first / 一", bluetoothLyricDisplayArtist(true, "A", "A", "first", "一", true))
        assertNull(bluetoothLyricDisplayArtist(false, "A", "A", "first", "一", true))
    }

    @Test fun defaultModeRemainsTheExistingArtistCompatibilityPath() {
        assertEquals(BluetoothLyricMetadataField.ARTIST, BluetoothLyricMetadataField.fromId(null))
        assertEquals(BluetoothLyricMetadataField.ARTIST, BluetoothLyricMetadataField.fromId("unrecognized"))
        val fields = bluetoothLyricMetadataText(
            "Original title", "Real performer", "Next lyric", BluetoothLyricMetadataField.ARTIST,
        )
        assertEquals("Original title", fields.title)
        assertEquals("Next lyric", fields.artist)
    }

    @Test fun titleCompatibilityModeUpdatesOnlyTheAvrcpTitleField() {
        val fields = bluetoothLyricMetadataText(
            "Original title", "Real performer", "Next lyric", BluetoothLyricMetadataField.TITLE,
        )
        assertEquals("Next lyric", fields.title)
        assertEquals("Real performer", fields.artist)
        assertEquals(BluetoothLyricMetadataField.TITLE, BluetoothLyricMetadataField.fromId("title"))
    }

    @Test fun switchingModesAndClearingLyricsRestoresUnmodifiedMetadata() {
        for (field in BluetoothLyricMetadataField.entries) {
            val empty = bluetoothLyricMetadataText("Title", "Artist", null, field)
            val blank = bluetoothLyricMetadataText("Title", "Artist", "", field)
            assertEquals("Title", empty.title)
            assertEquals("Artist", empty.artist)
            assertEquals(empty, blank)
        }
    }

    @Test fun translatedLyricIsKeptAsOneDisplayValueInEitherMode() {
        val translated = bluetoothLyricDisplayArtist(
            true, "A", "A", "Next lyric", "下一句", true,
        )
        assertEquals(
            "Next lyric / 下一句",
            bluetoothLyricMetadataText("Song", "Singer", translated, BluetoothLyricMetadataField.TITLE).title,
        )
        assertEquals(
            "Next lyric / 下一句",
            bluetoothLyricMetadataText("Song", "Singer", translated, BluetoothLyricMetadataField.ARTIST).artist,
        )
    }

    @Test fun twoLineModePublishesCurrentAndUpcomingLyricInSeparateFields() {
        val first = bluetoothLyricLine(true, "A", "A", lyrics, 1500)?.text
        val next = bluetoothLyricNextLine(true, "A", "A", lyrics, 1500)
        assertEquals("second", next)
        assertEquals(
            BluetoothLyricMetadataText("first", "second"),
            bluetoothLyricMetadataText("Song", "Singer", first, BluetoothLyricMetadataField.DUAL, next),
        )

        val afterAdvance = bluetoothLyricLine(true, "A", "A", lyrics, 3200)?.text
        val afterNext = bluetoothLyricNextLine(true, "A", "A", lyrics, 3200)
        assertNull(afterNext) // The next timestamp is explicitly blank.
        assertEquals(
            BluetoothLyricMetadataText("second", ""),
            bluetoothLyricMetadataText("Song", "Singer", afterAdvance, BluetoothLyricMetadataField.DUAL, afterNext),
        )
    }

    @Test fun twoLineModeUsesTranslationAsSecondLineWhenEnabled() {
        val first = bluetoothLyricLine(true, "A", "A", lyrics, 1500)!!
        val upcoming = bluetoothLyricNextLine(true, "A", "A", lyrics, 1500).orEmpty()
        assertEquals("一", bluetoothLyricSecondaryLine(true, first.translation, upcoming))
        assertEquals("second", bluetoothLyricSecondaryLine(false, first.translation, upcoming))
        assertEquals(
            BluetoothLyricMetadataText("first", "一"),
            bluetoothLyricMetadataText(
                "Song", "Singer", first.text, BluetoothLyricMetadataField.DUAL,
                bluetoothLyricSecondaryLine(true, first.translation, upcoming),
            ),
        )
    }

    @Test fun nextLineNeverLeaksAnUnrelatedTrackButKeepsTheNextTimedLineAcrossAnIntro() {
        assertNull(bluetoothLyricNextLine(true, "B", "A", lyrics, 1500))
        assertNull(bluetoothLyricNextLine(false, "A", "A", lyrics, 1500))
        assertNull(bluetoothLyricNextLine(true, "A", "A", lyrics, 999))
        assertEquals("second", bluetoothLyricNextLine(true, "A", "A", lyrics, 1500))
        val distant = LyricData(lines = listOf(
            LyricLine(1000, "first"),
            LyricLine(50000, "much later"),
        ))
        assertEquals("much later", bluetoothLyricNextLine(true, "A", "A", distant, 2000))
    }

    @Test fun lastCreditDoesNotTurnIntoTheSingerBeforeTheFirstSungLine() {
        val intro = LyricData(lines = listOf(
            LyricLine(1000, "编曲：Terence Teo"),
            LyricLine(40000, "站在十字路的交点"),
        ))
        for (position in listOf(2000L, 25001L, 39999L)) {
            assertEquals(
                BluetoothLyricMetadataText("编曲：Terence Teo", "站在十字路的交点"),
                bluetoothLyricMetadataText("雨天", "孙燕姿",
                    bluetoothLyricLine(true, "A", "A", intro, position)?.text,
                    BluetoothLyricMetadataField.DUAL,
                    bluetoothLyricNextLine(true, "A", "A", intro, position)),
            )
        }
    }

    @Test fun lastLyricDoesNotRestoreSingerWhileLyricDisplayStillOwnsTheFields() {
        assertEquals(BluetoothLyricMetadataText("末句", ""),
            bluetoothLyricMetadataText("歌名", "歌手", "末句", BluetoothLyricMetadataField.DUAL))
    }

    @Test fun singleCharacterLineAndFollowingLineAreSelectedInEveryMode() {
        val short = LyricData(lines = listOf(
            LyricLine(1000, "我"), LyricLine(2000, "脱不了身"), LyricLine(3000, "只剩折磨"),
        ))
        for (field in BluetoothLyricMetadataField.entries) {
            val first = bluetoothLyricMetadataText("歌名", "歌手",
                bluetoothLyricLine(true, "A", "A", short, 1000)?.text, field,
                bluetoothLyricNextLine(true, "A", "A", short, 1000))
            val next = bluetoothLyricMetadataText("歌名", "歌手",
                bluetoothLyricLine(true, "A", "A", short, 2000)?.text, field,
                bluetoothLyricNextLine(true, "A", "A", short, 2000))
            assertNotEquals(first, next)
            if (field == BluetoothLyricMetadataField.ARTIST) assertEquals("我", first.artist)
            else assertEquals("我", first.title)
        }
    }

    @Test fun twoLineModeResolvesToRealMetadataOnClearAndOnUnknownField() {
        assertEquals(BluetoothLyricMetadataField.DUAL, BluetoothLyricMetadataField.fromId("dual"))
        assertEquals(
            BluetoothLyricMetadataText("Track", "Singer"),
            bluetoothLyricMetadataText("Track", "Singer", "", BluetoothLyricMetadataField.DUAL, "stale"),
        )
        assertEquals(
            BluetoothLyricMetadataText("Track", "Singer"),
            bluetoothLyricMetadataText("Track", "Singer", null, BluetoothLyricMetadataField.DUAL),
        )
        assertEquals(BluetoothLyricMetadataField.ARTIST, BluetoothLyricMetadataField.fromId("invalid"))
    }
}
