package com.rawsmusic.module.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class FfmpegMetadataReaderCueTest {

    @Test
    fun `merge keeps ffmpeg embedded cuesheet when taglib does not expose it`() {
        val cue = """
            REM DATE 2009
            PERFORMER "Franco Corelli"
            TITLE "Icon: Franco Corelli"
            FILE "CDImage.wav" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                INDEX 01 03:37:22
        """.trimIndent()

        val method = FfmpegMetadataReader::class.java.getDeclaredMethod(
            "mergeTags",
            FfmpegMetadataReader.ExtendedTags::class.java,
            FfmpegMetadataReader.ExtendedTags::class.java,
        ).apply { isAccessible = true }

        val merged = method.invoke(
            FfmpegMetadataReader,
            FfmpegMetadataReader.ExtendedTags(album = "Icon: Franco Corelli"),
            FfmpegMetadataReader.ExtendedTags(cueSheet = cue),
        ) as FfmpegMetadataReader.ExtendedTags

        assertEquals(cue, merged.cueSheet)
    }
}
