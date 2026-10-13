package com.rawsmusic.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontScaleLayoutReporterTest {
    @Test
    fun recordsTheFirstLayoutAndAChangedPreferenceWithoutRepeatingTheSameScale() {
        val messages = mutableListOf<String>()
        val reporter = FontScaleLayoutReporter("home_title", messages::add)

        reporter.report(85, "system_default", 0.85f, 27.2f, 125, 40)
        reporter.report(85, "system_default", 0.85f, 27.2f, 125, 40)
        reporter.report(70, "system_default", 0.70f, 22.4f, 125, 40)

        assertEquals(2, messages.size)
        assertTrue(messages[0].contains("surface=home_title pref=85%"))
        assertTrue(messages[1].contains("surface=home_title pref=70%"))
        assertTrue(messages[1].contains("font22spPx=22.4"))
        assertTrue(messages[1].contains("layout=125x40"))
    }

    @Test
    fun aNewMountRecordsItsCurrentScale() {
        val messages = mutableListOf<String>()
        FontScaleLayoutReporter("preview", messages::add)
            .report(70, "system_default", 0.70f, 22.4f, 110, 32)

        assertEquals(1, messages.size)
    }

    @Test
    fun aDifferentFontAtTheSameScaleIsRecordedWithoutAFullPath() {
        val messages = mutableListOf<String>()
        val reporter = FontScaleLayoutReporter("preview", messages::add)

        reporter.report(70, "system_default", 0.70f, 22.4f, 110, 32)
        reporter.report(70, "NotoSansCJK.ttc", 0.70f, 22.4f, 110, 32)

        assertEquals(2, messages.size)
        assertTrue(messages[1].contains("font=NotoSansCJK.ttc"))
    }
}
