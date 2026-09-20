package com.rawsmusic.core.common.utils

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Splits multi-value metadata without breaking names explicitly protected by
 * the user. Protection is applied before separator matching, just like the
 * library model used by Halcyon.
 */
data class MetadataNameSplitConfig(
    val artistSeparators: List<String> = emptyList(),
    val artistProtectedNames: List<String> = emptyList(),
    val genreSeparators: List<String> = emptyList(),
    val genreProtectedNames: List<String> = emptyList(),
    val ignoreCase: Boolean = false
)

object MetadataNameSplitter {
    private const val TOKEN_PREFIX = "\uE000"
    private const val TOKEN_SUFFIX = "\uE001"

    private val separatorRegexCache = ConcurrentHashMap<String, Regex>()
    private val protectedNameRegexCache = ConcurrentHashMap<String, Regex>()

    fun splitArtistNames(value: String, config: MetadataNameSplitConfig): List<String> = splitNames(
        value = value,
        separators = config.artistSeparators,
        protectedNames = config.artistProtectedNames,
        ignoreCase = config.ignoreCase
    )

    fun splitGenreNames(value: String, config: MetadataNameSplitConfig): List<String> = splitNames(
        value = value,
        separators = config.genreSeparators,
        protectedNames = config.genreProtectedNames,
        ignoreCase = config.ignoreCase
    )

    fun matchesArtistName(
        value: String,
        target: String,
        config: MetadataNameSplitConfig
    ): Boolean = matches(splitArtistNames(value, config), target, config.ignoreCase)

    fun matchesGenreName(
        value: String,
        target: String,
        config: MetadataNameSplitConfig
    ): Boolean = matches(splitGenreNames(value, config), target, config.ignoreCase)

    fun parseSetting(value: String): List<String> = value
        .lineSequence()
        .flatMap { it.split('\t').asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .toList()

    fun identityKey(value: String, ignoreCase: Boolean): String = value.trim().let {
        if (ignoreCase) it.lowercase(Locale.ROOT) else it
    }

    private fun matches(values: List<String>, target: String, ignoreCase: Boolean): Boolean {
        val normalizedTarget = target.trim()
        if (normalizedTarget.isBlank()) return false
        return values.any { it.equals(normalizedTarget, ignoreCase = ignoreCase) }
    }

    private fun splitNames(
        value: String,
        separators: List<String>,
        protectedNames: List<String>,
        ignoreCase: Boolean
    ): List<String> {
        val normalized = value
            .replace('（', '(')
            .replace('）', ')')
            .trim()
        if (normalized.isBlank()) return emptyList()

        val protectedMap = linkedMapOf<String, String>()
        var protectedText = normalized
        protectedNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedByDescending(String::length)
            .forEachIndexed { index, name ->
                val token = "$TOKEN_PREFIX$index$TOKEN_SUFFIX"
                val regex = protectedNameRegexCache.getOrPut(name) {
                    Regex(Regex.escape(name), RegexOption.IGNORE_CASE)
                }
                if (regex.containsMatchIn(protectedText)) {
                    protectedMap[token] = name
                    protectedText = regex.replace(protectedText, token)
                }
            }

        val separatorRegex = buildSeparatorRegex(separators, ignoreCase)
            ?: return listOf(normalized).filterNot(::isUnknownName)

        return protectedText
            .split(separatorRegex)
            .asSequence()
            .map { item ->
                protectedMap.entries.fold(item.trim()) { current, (token, name) ->
                    current.replace(token, name)
                }.trim()
            }
            .filter { it.isNotBlank() && !isUnknownName(it) }
            .distinctBy { identityKey(it, ignoreCase) }
            .toList()
    }

    private fun buildSeparatorRegex(
        separators: List<String>,
        ignoreCase: Boolean
    ): Regex? {
        val normalized = separators
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sortedByDescending(String::length)
            .map { Regex.escape(it) }
        if (normalized.isEmpty()) return null
        val pattern = "\\s*(?:${normalized.joinToString("|")})\\s*"
        val flags = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return separatorRegexCache.getOrPut("$pattern|$ignoreCase") {
            Regex(pattern, flags)
        }
    }

    private fun isUnknownName(value: String): Boolean =
        value.trim().lowercase(Locale.ROOT) == "<unknown>"
}
