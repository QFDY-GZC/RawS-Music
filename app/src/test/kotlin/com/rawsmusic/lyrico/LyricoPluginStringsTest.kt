package com.rawsmusic.lyrico

import com.google.gson.Gson
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LyricoPluginStringsTest {
    private val gson = Gson()

    @Test
    fun oldManifestWithoutI18nRemainsValidAtModelLevel() {
        val manifest = gson.fromJson(
            """
                {
                  "id":"example.old",
                  "name":"Old",
                  "versionCode":1,
                  "versionName":"1",
                  "apiVersion":4
                }
            """.trimIndent(),
            LyricoPluginManifest::class.java,
        )

        assertNull(manifest.i18n)
    }

    @Test
    fun api5ManifestParsesI18nResources() {
        val manifest = gson.fromJson(
            """
                {
                  "id":"example.api5",
                  "name":"API5",
                  "versionCode":1,
                  "versionName":"1",
                  "apiVersion":5,
                  "minHostApiVersion":4,
                  "i18n":{
                    "defaultLocale":"en",
                    "resources":{
                      "en":"i18n/en.json",
                      "zh-Hans":"i18n/zh-Hans.json"
                    }
                  }
                }
            """.trimIndent(),
            LyricoPluginManifest::class.java,
        )

        assertEquals("en", manifest.i18n?.defaultLocale)
        assertEquals("i18n/zh-Hans.json", manifest.i18n?.resources?.get("zh-Hans"))
    }

    @Test
    fun localeSelectionFallsBackFromExactToCompatibleThenDefault() {
        val root = Files.createTempDirectory("lyrico-i18n").toFile()
        try {
            val i18nDir = root.resolve("i18n").apply { mkdirs() }
            i18nDir.resolve("en.json").writeText(
                """{"hello":"Hello","count":"%1${'$'}s has %2${'$'}d tracks %%"}"""
            )
            i18nDir.resolve("zh-Hans.json").writeText("""{"hello":"你好"}""")
            val spec = LyricoPluginI18n(
                defaultLocale = "en",
                resources = linkedMapOf(
                    "en" to "i18n/en.json",
                    "zh-Hans" to "i18n/zh-Hans.json",
                ),
            )

            val exact = LyricoPluginStrings.load(root, spec, listOf("zh-Hans"))
            assertEquals("zh-Hans", exact.localeTag)
            assertEquals("你好", exact.text("hello"))

            val compatible = LyricoPluginStrings.load(root, spec, listOf("zh-Hans-CN"))
            assertEquals("zh-Hans", compatible.localeTag)
            assertEquals("你好", compatible.text("hello"))

            val fallback = LyricoPluginStrings.load(root, spec, listOf("fr-FR"))
            assertEquals("en", fallback.localeTag)
            assertEquals("Hello", fallback.text("hello"))
            assertEquals("missing.key", fallback.text("missing.key"))
            assertEquals("RawS has 3 tracks %", fallback.format("count", listOf("RawS", 3)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingI18nProducesUndSnapshot() {
        val root = Files.createTempDirectory("lyrico-i18n-empty").toFile()
        try {
            val strings = LyricoPluginStrings.load(root, null, listOf("en-US"))

            assertEquals("und", strings.localeTag)
            assertEquals("unknown", strings.text("unknown"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localeResourceCannotEscapePluginRoot() {
        val parent = Files.createTempDirectory("lyrico-i18n-safe").toFile()
        val root = parent.resolve("plugin").apply { mkdirs() }
        parent.resolve("outside.json").writeText("""{"hello":"escape"}""")
        try {
            val spec = LyricoPluginI18n(
                defaultLocale = "en",
                resources = mapOf("en" to "../outside.json"),
            )

            assertThrows(IllegalArgumentException::class.java) {
                LyricoPluginStrings.load(root, spec, listOf("en"))
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
