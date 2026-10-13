package com.rawsmusic.transcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTranscodeFailurePresentationTest {
    private fun format(reason: AudioTranscodeFailureReason? = null, raw: String? = null,
        code: Int? = null, language: String = "zh") = formatTranscodeFailureDetail(
        language, reason, raw, code, { "localized:${it.name}" }, { text, number -> "$text ($number)" })

    @Test fun successfulOrRunningTaskWithNoFailureHasNoErrorText() {
        assertNull(format())
        assertNull(format(raw = "  "))
    }

    @Test fun realInternalErrorStillHasFallbackMessage() {
        assertEquals("localized:INTERNAL_ERROR", format(AudioTranscodeFailureReason.INTERNAL_ERROR))
    }

    @Test fun encodingFailureKeepsNativeCodeAndReadableDetail() {
        assertEquals("localized:ENCODE_FAILED (-22)", format(AudioTranscodeFailureReason.ENCODE_FAILED, code = -22))
        assertEquals("编码器打开失败", format(AudioTranscodeFailureReason.ENCODE_FAILED, "编码器打开失败"))
        assertEquals("localized:ENCODE_FAILED", format(AudioTranscodeFailureReason.ENCODE_FAILED, "编码器打开失败", language = "en"))
    }
}
