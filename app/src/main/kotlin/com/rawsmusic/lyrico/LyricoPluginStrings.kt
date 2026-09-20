package com.rawsmusic.lyrico

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale
import kotlin.math.abs

class LyricoPluginStrings private constructor(
    val localeTag: String,
    private val chain: List<Map<String, String>>,
) {
    fun text(key: String): String = lookup(key) ?: key

    fun format(key: String, args: List<Any?>): String {
        val template = lookup(key) ?: return key
        return if (args.isEmpty()) template else LyricoPluginStringFormat.format(template, args)
    }

    private fun lookup(key: String): String? = chain.firstNotNullOfOrNull { it[key] }

    companion object {
        private const val MAX_BYTES = 512L * 1024L

        fun empty(): LyricoPluginStrings = LyricoPluginStrings("und", emptyList())

        fun load(
            root: File,
            spec: LyricoPluginI18n?,
            requestedLocaleTags: List<String>,
        ): LyricoPluginStrings {
            if (spec == null) return empty()
            require(spec.resources.isNotEmpty()) { "Plugin i18n resources are required" }
            require(spec.defaultLocale in spec.resources) { "Default locale resource is missing" }
            require(spec.resources.size <= 64) { "Plugin declares too many locales" }

            val canonicalRoot = root.canonicalFile
            val catalogs = spec.resources.mapValues { (tag, relativePath) ->
                require(isCanonicalLocaleTag(tag)) { "Use a canonical BCP 47 locale: $tag" }
                require(relativePath.isNotBlank() && !File(relativePath).isAbsolute && '\\' !in relativePath) {
                    "Unsafe locale path: $relativePath"
                }
                require(relativePath.split('/').none { it.isEmpty() || it == ".." }) {
                    "Unsafe locale path: $relativePath"
                }
                val file = File(canonicalRoot, relativePath).canonicalFile
                require(file.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Locale resource escapes plugin directory: $relativePath"
                }
                require(file.isFile && file.extension.equals("json", ignoreCase = true)) {
                    "Invalid locale resource: $relativePath"
                }
                require(file.length() <= MAX_BYTES) { "Locale resource exceeds 512 KiB: $relativePath" }
                parseCatalog(file)
            }

            val selected = selectLocale(
                available = catalogs.keys,
                requested = requestedLocaleTags,
                defaultLocale = spec.defaultLocale,
            )
            val locale = Locale.forLanguageTag(selected)
            val parentCandidates = buildList {
                if (locale.country.isNotEmpty()) {
                    val languageAndScript = listOf(locale.language, locale.script)
                        .filter(String::isNotEmpty)
                        .joinToString("-")
                    if (languageAndScript.isNotEmpty()) add(languageAndScript)
                }
                if (locale.language.isNotEmpty()) add(locale.language)
            }
            val chain = (listOf(selected) + parentCandidates + spec.defaultLocale)
                .distinct()
                .mapNotNull(catalogs::get)
            return LyricoPluginStrings(selected, chain)
        }

        private fun parseCatalog(file: File): Map<String, String> {
            val root = JsonParser.parseString(file.readText())
            require(root.isJsonObject) { "Locale resource must be a JSON object: ${file.name}" }
            return root.asJsonObject.entrySet().associate { (key, value) ->
                require(key.isNotBlank()) { "Empty resource key" }
                require(value.isStringPrimitive()) { "Plugin string must be text: $key" }
                key to value.asString
            }
        }

        private fun JsonElement.isStringPrimitive(): Boolean =
            isJsonPrimitive && asJsonPrimitive.isString

        private fun selectLocale(
            available: Set<String>,
            requested: List<String>,
            defaultLocale: String,
        ): String {
            val sorted = available.sorted()
            requested.forEach { requestedTag ->
                val wanted = Locale.forLanguageTag(requestedTag)
                sorted.firstOrNull { it.equals(wanted.toLanguageTag(), ignoreCase = true) }?.let { return it }
                sorted.firstOrNull { candidateTag ->
                    val candidate = Locale.forLanguageTag(candidateTag)
                    candidate.language.equals(wanted.language, ignoreCase = true) &&
                        scriptsCompatible(candidate, wanted)
                }?.let { return it }
                sorted.firstOrNull { candidateTag ->
                    Locale.forLanguageTag(candidateTag).language.equals(wanted.language, ignoreCase = true)
                }?.let { return it }
            }
            return defaultLocale
        }

        private fun scriptsCompatible(left: Locale, right: Locale): Boolean {
            if (left.script.isNotEmpty() && right.script.isNotEmpty()) {
                return left.script.equals(right.script, ignoreCase = true)
            }
            if (left.language != "zh" || right.language != "zh") return true
            return inferChineseScript(left) == inferChineseScript(right)
        }

        private fun inferChineseScript(locale: Locale): String = when (locale.country.uppercase(Locale.ROOT)) {
            "TW", "HK", "MO" -> "Hant"
            else -> "Hans"
        }

        private fun isCanonicalLocaleTag(tag: String): Boolean {
            if (tag.isBlank() || tag == "und") return false
            return runCatching {
                Locale.Builder().setLanguageTag(tag).build().toLanguageTag() == tag
            }.getOrDefault(false)
        }
    }
}

internal object LyricoPluginStringFormat {
    private val token = Regex("%(?:([1-9][0-9]*)\\$)?([sd%])")

    fun signature(text: String): Map<Int, String> {
        val result = sortedMapOf<Int, String>()
        var offset = 0
        var implicit = 0
        var hasImplicit = false
        var hasExplicit = false
        while (true) {
            val start = text.indexOf('%', offset)
            if (start < 0) break
            val match = token.find(text, start)
            require(match != null && match.range.first == start) { "Unsupported format at $start: $text" }
            offset = match.range.last + 1
            val type = match.groupValues[2]
            val position = match.groupValues[1]
            if (type == "%") {
                require(position.isEmpty()) { "Use %% for a literal percent" }
                continue
            }
            val index = if (position.isEmpty()) {
                hasImplicit = true
                ++implicit
            } else {
                hasExplicit = true
                position.toInt()
            }
            require(index <= 64) { "At most 64 format arguments are supported" }
            require(result[index] == null || result[index] == type) { "Conflicting argument types at $index" }
            result[index] = type
        }
        require(!(hasImplicit && hasExplicit)) { "Do not mix indexed and unindexed placeholders" }
        require(!hasImplicit || implicit <= 1) { "Multiple arguments require positional placeholders" }
        require(result.keys.toList() == (1..result.size).toList()) { "Argument indexes must be contiguous" }
        return result
    }

    fun format(text: String, args: List<Any?>): String {
        val signature = signature(text)
        require(args.size == signature.size) { "Expected ${signature.size} arguments, got ${args.size}" }
        signature.forEach { (index, type) ->
            val value = args[index - 1]
            val valid = if (type == "s") {
                value is String
            } else {
                value is Number && value.toDouble().isFinite() &&
                    abs(value.toDouble()) <= 9_007_199_254_740_991.0 &&
                    value.toDouble() == value.toLong().toDouble()
            }
            require(valid) { "Invalid argument $index for %$type" }
        }
        var implicit = 0
        return token.replace(text) { match ->
            val type = match.groupValues[2]
            if (type == "%") {
                "%"
            } else {
                val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: implicit++
                if (type == "d") (args[index] as Number).toLong().toString() else args[index] as String
            }
        }
    }
}
