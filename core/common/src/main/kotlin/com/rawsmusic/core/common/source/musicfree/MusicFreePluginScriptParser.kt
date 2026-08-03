package com.rawsmusic.core.common.source.musicfree

import java.security.MessageDigest
import java.util.Locale

/**
 * Static MusicFree script inspection used before an imported script is ever executed.
 *
 * This parser intentionally understands only literal manifest fields and declared method
 * names. It does not evaluate JavaScript. The isolated runtime can perform the authoritative
 * mount later, while the importer can already present a stable preview and persist the file.
 */
object MusicFreePluginScriptParser {
    const val MAX_SCRIPT_BYTES: Int = 2 * 1024 * 1024

    private val supportedMethodNames = linkedMapOf(
        "search" to MusicFreeMethod.Search,
        "getMediaSource" to MusicFreeMethod.GetMediaSource,
        "getLyric" to MusicFreeMethod.GetLyric,
        "getMusicInfo" to MusicFreeMethod.GetMusicInfo,
        "importMusicItem" to MusicFreeMethod.ImportMusicItem,
        "importMusicSheet" to MusicFreeMethod.ImportMusicSheet,
        "getAlbumInfo" to MusicFreeMethod.GetAlbumInfo,
        "getArtistWorks" to MusicFreeMethod.GetArtistWorks,
        "getMusicSheetInfo" to MusicFreeMethod.GetMusicSheetInfo,
    )

    fun inspect(scriptRaw: String): MusicFreeScriptMetadata {
        val script = scriptRaw.removePrefix("\uFEFF").trim()
        require(script.isNotBlank()) { "音源脚本为空" }
        require(script.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) {
            "音源脚本超过 2 MiB 限制"
        }
        require(
            "module.exports" in script ||
                "exports.default" in script ||
                "export default" in script
        ) { "未识别到 MusicFree 插件导出" }

        val platform = literalProperty(script, "platform")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("音源缺少 platform 字段")
        require(platform.length <= 80) { "音源名称过长" }

        val version = literalProperty(script, "version").orEmpty().trim().take(48)
        val author = literalProperty(script, "author").orEmpty().trim().take(96)
        val description = literalProperty(script, "description").orEmpty().trim().take(2_048)
        val appVersion = literalProperty(script, "appVersion").orEmpty().trim().take(64)
        val sourceUrl = literalProperty(script, "srcUrl").orEmpty().trim().take(2_048)
        val methods = supportedMethodNames
            .filterKeys { declaresMethod(script, it) }
            .values
            .toCollection(linkedSetOf())

        require(methods.isNotEmpty()) { "音源没有声明可用的 MusicFree 方法" }

        val supportedTypes = parseSupportedSearchTypes(script)
        return MusicFreeScriptMetadata(
            descriptor = MusicFreePluginDescriptor(
                platform = platform,
                version = version,
                author = author,
                description = description,
                supportedSearchTypes = supportedTypes,
                methods = methods,
            ),
            appVersion = appVersion,
            sourceUrl = sourceUrl,
            sha256 = sha256(script.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun parseSupportedSearchTypes(script: String): Set<MusicFreeMediaType> {
        val body = Regex(
            pattern = """\bsupportedSearchType\s*:\s*\[([^]]*)]""",
            option = RegexOption.DOT_MATCHES_ALL,
        ).find(script)?.groupValues?.getOrNull(1).orEmpty()
        if (body.isBlank()) return setOf(MusicFreeMediaType.Music)

        val tokens = Regex("""['\"]([^'\"]+)['\"]""")
            .findAll(body)
            .map { it.groupValues[1].trim().lowercase(Locale.ROOT) }
            .toSet()
        val result = buildSet {
            if (tokens.any { it == "music" || it == "song" }) add(MusicFreeMediaType.Music)
            if (tokens.any { it == "album" }) add(MusicFreeMediaType.Album)
            if (tokens.any { it == "artist" }) add(MusicFreeMediaType.Artist)
            if (tokens.any { it == "sheet" || it == "playlist" }) add(MusicFreeMediaType.Sheet)
        }
        return result.ifEmpty { setOf(MusicFreeMediaType.Music) }
    }

    private fun declaresMethod(script: String, method: String): Boolean {
        val escaped = Regex.escape(method)
        return Regex("""\b$escaped\s*\(""").containsMatchIn(script) ||
            Regex("""\b$escaped\s*:\s*(?:async\s*)?(?:function\b|\([^)]*\)\s*=>|[A-Za-z_$][\w$]*\s*=>)""")
                .containsMatchIn(script)
    }

    private fun literalProperty(script: String, property: String): String? {
        val escaped = Regex.escape(property)
        val patterns = listOf(
            Regex("""\b$escaped\s*:\s*\"((?:\\.|[^\"\\])*)\"""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\b$escaped\s*:\s*'((?:\\.|[^'\\])*)'""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\b$escaped\s*:\s*`((?:\\.|[^`\\])*)`""", RegexOption.DOT_MATCHES_ALL),
        )
        val raw = patterns.firstNotNullOfOrNull { regex ->
            regex.find(script)?.groupValues?.getOrNull(1)
        } ?: return null
        return unescapeLiteral(raw)
    }

    private fun unescapeLiteral(raw: String): String {
        return raw
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\`", "`")
            .replace("\\\\", "\\")
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

data class MusicFreeScriptMetadata(
    val descriptor: MusicFreePluginDescriptor,
    val appVersion: String = "",
    val sourceUrl: String = "",
    val sha256: String,
)
