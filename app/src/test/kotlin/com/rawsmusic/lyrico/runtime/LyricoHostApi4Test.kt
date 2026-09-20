package com.rawsmusic.lyrico.runtime

import com.rawsmusic.lyrico.LyricoPluginI18n
import com.rawsmusic.lyrico.LyricoPluginStrings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LyricoHostApi4Test {
    @Test
    fun i18nHostCallsReturnLocaleAndFormattedString() {
        val root = Files.createTempDirectory("lyrico-host-api4").toFile()
        try {
            root.resolve("en-US.json").writeText(
                """{"hello":"Hello %1${'$'}s, %2${'$'}d tracks"}"""
            )
            val strings = LyricoPluginStrings.load(
                root = root,
                spec = LyricoPluginI18n(
                    defaultLocale = "en-US",
                    resources = mapOf("en-US" to "en-US.json"),
                ),
                requestedLocaleTags = listOf("en-US"),
            )
            val host = QuickJsHostApi(pluginStrings = strings)

            assertEquals("en-US", valueOf(host.call("i18n.getLocale", "{}")))
            assertEquals(
                "Hello RawSMusic, 5 tracks",
                valueOf(
                    host.call(
                        "i18n.t",
                        """{"key":"hello","args":["RawSMusic",5]}""",
                    )
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptySnapshotStillProvidesStableHostApi4Fallbacks() {
        val host = QuickJsHostApi(pluginStrings = LyricoPluginStrings.empty())

        assertEquals("und", valueOf(host.call("i18n.getLocale", "{}")))
        assertEquals("missing.key", valueOf(host.call("i18n.t", """{"key":"missing.key"}""")))
    }

    @Test
    fun bootstrapPublishesPlatformI18nMethods() {
        val bootstrap = QuickJsRuntime.hostApiBootstrapForTest()

        assertTrue(bootstrap.contains("i18n.getLocale"))
        assertTrue(bootstrap.contains("i18n.t"))
        assertTrue(bootstrap.contains("i18n:"))
    }

    private fun valueOf(json: String): String =
        Json.parseToJsonElement(json).jsonObject.getValue("value").jsonPrimitive.content
}
