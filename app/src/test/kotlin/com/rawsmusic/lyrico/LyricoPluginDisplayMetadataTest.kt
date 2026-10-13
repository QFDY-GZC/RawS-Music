package com.rawsmusic.lyrico

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LyricoPluginDisplayMetadataTest {
    private val root = File("src/test/resources/lyrico/furigana-display")
    private val manifest get() = Gson().fromJson(root.resolve("manifest.json").readText(), LyricoPluginManifest::class.java)
    private fun display(tag: String): LyricoPluginManifest {
        val raw = manifest
        return LyricoPluginDisplayMetadata.resolve(raw, LyricoPluginStrings.load(root, raw.i18n, listOf(tag)))
    }
    @Test fun suppliedPluginResolvesSimplifiedChineseNameAndDescription() {
        val display = display("zh-CN")
        assertEquals("日语歌词源插件", display.name)
        assertTrue(display.description.startsWith("专门搜日语歌词"))
    }
    @Test fun hongKongSelectsTraditionalCatalog() {
        assertEquals("日語歌詞源插件", display("zh-HK").name)
    }
    @Test fun unsupportedJapaneseLocaleUsesEnglishDefault() {
        assertEquals("Japanese Lyrics Source", display("ja-JP").name)
    }
    @Test fun literalOldManifestRemainsUnchanged() {
        val raw = manifest.copy(name = "Old source", description = "Literal @mention", i18n = null)
        assertEquals(raw, LyricoPluginDisplayMetadata.resolve(raw, LyricoPluginStrings.empty()))
    }
    @Test fun missingReferencesUseIdAndEmptyDescription() {
        val result = LyricoPluginDisplayMetadata.resolve(manifest, LyricoPluginStrings.empty())
        assertEquals(manifest.id, result.name)
        assertEquals("", result.description)
    }
    @Test fun localizationDoesNotChangePluginIdentityOrInstalledManifest() {
        val raw = manifest
        val localized = display("en-US")
        assertEquals(raw.copy(name = localized.name, description = localized.description), localized)
        assertEquals("@plugin.name", manifest.name)
    }
}
