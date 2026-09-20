package com.rawsmusic.core.common.taglib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeMetadataReportTest {
    @Test
    fun parse_preservesStrictMigrationDiagnostics() {
        val report = TranscodeMetadataReport.parse(
            """
            supported=1
            saveOk=1
            verifyOk=0
            strictOk=0
            sourceTextFields=14
            sourceTextValues=18
            targetTextFields=15
            targetTextValues=19
            rejectedFields=1
            textMismatches=2
            picturesCopied=3
            pictureMismatches=1
            unsupportedBinaryItems=4
            mismatchKeys=LYRICS,CUSTOM FIELD
            """.trimIndent(),
        )

        assertTrue(report.supported)
        assertTrue(report.saveOk)
        assertFalse(report.verifyOk)
        assertFalse(report.strictOk)
        assertEquals(14, report.sourceTextFields)
        assertEquals(18, report.sourceTextValues)
        assertEquals(15, report.targetTextFields)
        assertEquals(19, report.targetTextValues)
        assertEquals(1, report.rejectedFields)
        assertEquals(2, report.textMismatches)
        assertEquals(3, report.picturesCopied)
        assertEquals(1, report.pictureMismatches)
        assertEquals(4, report.unsupportedBinaryItems)
        assertEquals(listOf("LYRICS", "CUSTOM FIELD"), report.mismatchKeys)
    }

    @Test
    fun parse_blankReport_isUnsupported() {
        assertEquals(TranscodeMetadataReport.Unsupported, TranscodeMetadataReport.parse(null))
        assertEquals(TranscodeMetadataReport.Unsupported, TranscodeMetadataReport.parse(""))
    }
}
