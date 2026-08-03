package com.rawsmusic.core.common.source.lx

import java.security.MessageDigest
import java.util.Locale

/**
 * Non-executing metadata inspection for LX User API and Hacylon-compatible Render API scripts.
 *
 * Import intentionally stays permissive, matching Hacylon: a source is stored after basic text and
 * size validation, while protocol validation belongs to the isolated runtime when the source is
 * actually used. Minified/bundled sources frequently alias `lx`, `send`, `on`, or `EVENT_NAMES`, so
 * requiring those exact source-code spellings here rejects otherwise valid LX scripts.
 */
object LxSourceScriptParser {
    const val MIN_SCRIPT_CHARS: Int = 50
    const val MAX_SCRIPT_BYTES: Int = 9_000_000

    private val knownPlatforms = linkedSetOf("kw", "kg", "tx", "wy", "mg", "local")

    fun inspect(scriptRaw: String): LxSourceScriptMetadata {
        val script = scriptRaw.removePrefix("\uFEFF").trim()
        require(script.length >= MIN_SCRIPT_CHARS) { "LX 音源脚本内容异常" }
        require(script.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) {
            "LX 音源脚本超过 9 MB 限制"
        }

        // Render API is the only format that needs a distinct storage marker. Everything else is
        // retained as User API and validated later by the isolated LX runtime, just as Hacylon does.
        val hasRenderApiConfig = Regex("""\bAPI_URL\s*=""").containsMatchIn(script) &&
            Regex("""\bAPI_KEY\s*=""").containsMatchIn(script) &&
            "/url/" in script &&
            "X-Request-Key" in script
        val format = if (hasRenderApiConfig) LxSourceFormat.RenderApi else LxSourceFormat.UserApi

        // Hacylon checks a literal name first, then @name. Preserve that ordering so imported source
        // identities stay compatible even when scripts contain both forms.
        val name = literalProperty(script, "name")
            ?: commentField(script, "name")
            ?: "LX 音源"

        val platforms = parsePlatformHints(script)
        return LxSourceScriptMetadata(
            descriptor = LxSourceDescriptor(
                name = name.trim().ifBlank { "LX 音源" }.take(80),
                version = (literalProperty(script, "version") ?: commentField(script, "version")).orEmpty().trim().take(48),
                author = (literalProperty(script, "author") ?: commentField(script, "author")).orEmpty().trim().take(96),
                description = (literalProperty(script, "description") ?: commentField(script, "description")).orEmpty().trim().take(2_048),
                homepage = (literalProperty(script, "homepage") ?: commentField(script, "homepage")).orEmpty().trim().take(2_048),
                platforms = platforms,
            ),
            sha256 = sha256(script.toByteArray(Charsets.UTF_8)),
            format = format,
        )
    }

    private fun parsePlatformHints(script: String): List<LxPlatformCapability> {
        val result = linkedMapOf<String, LxPlatformCapability>()
        knownPlatforms.forEach { platform ->
            val qualities = linkedSetOf<String>()
            val actions = linkedSetOf<LxSourceAction>()

            // Legacy source declarations sometimes expose a simple quality array.
            Regex(
                pattern = """(?:['"])?${Regex.escape(platform)}(?:['"])?\s*:\s*\[([^]]*)]""",
                option = RegexOption.DOT_MATCHES_ALL,
            ).findAll(script).forEach { match ->
                parseStringArray(match.groupValues[1]).forEach { value ->
                    if (value.isKnownQuality()) qualities += value.lowercase(Locale.ROOT)
                }
            }

            // LX User API 2.x commonly declares each source as
            // { type: 'music', actions: ['musicUrl'], qualitys: ['128k', ...] }.
            Regex(
                pattern = """(?:['"])?${Regex.escape(platform)}(?:['"])?\s*:\s*\{([\s\S]{0,1600}?)\}(?=\s*,?\s*(?:['"]?(?:kw|kg|tx|wy|mg|local)['"]?\s*:|\}))""",
                option = RegexOption.IGNORE_CASE,
            ).findAll(script).forEach { match ->
                val body = match.groupValues[1]
                Regex("""\bqualitys?\s*:\s*\[([^]]*)]""", RegexOption.IGNORE_CASE)
                    .find(body)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::parseStringArray)
                    ?.filterTo(qualities) { it.isKnownQuality() }
                Regex("""\bactions?\s*:\s*\[([^]]*)]""", RegexOption.IGNORE_CASE)
                    .find(body)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::parseStringArray)
                    ?.mapNotNullTo(actions, LxSourceAction::fromWireName)
            }

            val mentionsPlatform = Regex("""['"]${Regex.escape(platform)}['"]""").containsMatchIn(script) ||
                Regex("""\b${Regex.escape(platform)}\s*:""").containsMatchIn(script)
            if (qualities.isNotEmpty() || actions.isNotEmpty() || mentionsPlatform) {
                result[platform] = LxPlatformCapability(
                    platform = platform,
                    qualities = qualities.mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) },
                    actions = actions.ifEmpty {
                        if (platform == "local") {
                            linkedSetOf(LxSourceAction.MusicUrl, LxSourceAction.Lyric, LxSourceAction.Pic)
                        } else {
                            linkedSetOf(LxSourceAction.MusicUrl)
                        }
                    },
                )
            }
        }
        return result.values.toList()
    }

    private fun parseStringArray(body: String): List<String> =
        Regex("""['"]([^'"]+)['"]""").findAll(body).map { it.groupValues[1].trim() }.toList()

    private fun String.isKnownQuality(): Boolean =
        matches(Regex("""(?:128k|320k|flac|flac24bit|wav|ape)""", RegexOption.IGNORE_CASE))

    private fun commentField(script: String, name: String): String? {
        val match = Regex(
            pattern = """(?im)^\s*(?://+|/\*+|\*+)\s*@${Regex.escape(name)}\s+(.+?)\s*(?:\*/)?$""",
        ).find(script) ?: return null
        return match.groupValues[1].trim().trimEnd('*', '/').trim().takeIf(String::isNotBlank)
    }

    private fun literalProperty(script: String, property: String): String? {
        val escaped = Regex.escape(property)
        val patterns = listOf(
            Regex("""\b$escaped\s*:\s*\"((?:\\.|[^\"\\])*)\"""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\b$escaped\s*:\s*'((?:\\.|[^'\\])*)'""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\b$escaped\s*:\s*`((?:\\.|[^`\\])*)`""", RegexOption.DOT_MATCHES_ALL),
        )
        val raw = patterns.firstNotNullOfOrNull { it.find(script)?.groupValues?.getOrNull(1) } ?: return null
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

data class LxSourceScriptMetadata(
    val descriptor: LxSourceDescriptor,
    val sha256: String,
    val format: LxSourceFormat,
)
