package com.rawsmusic.core.common.taglib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeMetadataSourceInfoTest {
    @Test
    fun parser_preservesOpaqueMetadataPreflight() {
        val info = TranscodeMetadataSourceInfo.parse(
            """
            supported=1
            textFields=12
            textValues=17
            pictureCount=2
            unsupportedOpaqueItems=2
            unsupportedKeys=GEOB,PRIV
            """.trimIndent(),
        )
        assertTrue(info.supported)
        assertFalse(info.strictReadable)
        assertTrue(info.pictureCount == 2)
        assertTrue(info.unsupportedKeys == listOf("GEOB", "PRIV"))
    }

    @Test
    fun parser_acceptsFullyRepresentableSource() {
        val info = TranscodeMetadataSourceInfo.parse(
            """
            supported=1
            textFields=8
            textValues=9
            pictureCount=1
            unsupportedOpaqueItems=0
            unsupportedKeys=
            """.trimIndent(),
        )
        assertTrue(info.strictReadable)
    }
}
