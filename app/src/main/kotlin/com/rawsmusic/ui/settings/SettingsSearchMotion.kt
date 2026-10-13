package com.rawsmusic.ui.settings

import java.util.Locale
import kotlin.math.PI

internal object SettingsSearchMotion {
    // HomepageImpl search springs: response=0.35, bounce=0. Response is not a duration.
    val stiffness = ((2.0 * PI / 0.35) * (2.0 * PI / 0.35)).toFloat()
    // COUISearchBar.Default -> StateChangedToEdit/NormalParamsHigh. Separate from the page spring.
    val barStiffness = ((2.0 * PI / 0.5) * (2.0 * PI / 0.5)).toFloat()
    const val barDampingRatio = 0.75f
    fun cancelScale(progress: Float): Float = 0.5f + 0.5f * progress
    // Material branch: view alpha reaches 1 at 20%; text colour has its own progress alpha.
    fun cancelAlpha(progress: Float): Float = progress.coerceIn(0f, 1f).let {
        (it / 0.2f).coerceAtMost(1f)
    }
    data class Frame(val homeAlpha: Float, val titleScale: Float, val titleOffsetPx: Float, val resultAlpha: Float)
    fun at(progress: Float, entering: Boolean): Frame {
        val p = progress.coerceIn(0f, 1f)
        val remaining = 1f - p
        val alpha = if (entering) remaining * remaining * remaining else remaining
        return Frame(alpha, 0.8f + 0.2f * alpha, -200f * p, p)
    }
    fun headerOffset(savedHomeOffset: Float, collapseRange: Float, progress: Float): Float =
        savedHomeOffset + (-collapseRange - savedHomeOffset) * progress.coerceIn(0f, 1f)
}

internal fun updateSettingsSearchHistory(history: List<String>, query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return history
    return (listOf(trimmed) + history.filterNot { it.equals(trimmed, ignoreCase = true) }).take(6)
}

internal data class SettingsSearchText(val parent: String, val title: String, val summary: String, val keywords: String) {
    fun matches(query: String): Boolean {
        val tokens = normalizeSettingsSearchText(query).split(' ').filter(String::isNotBlank)
        val haystack = normalizeSettingsSearchText("$parent $title $summary $keywords")
        val ownText = normalizeSettingsSearchText("$title $summary $keywords")
        return tokens.isNotEmpty() && tokens.all(haystack::contains) && tokens.any(ownText::contains)
    }
    fun score(query: String): Int {
        val needle = normalizeSettingsSearchText(query)
        val label = normalizeSettingsSearchText(title)
        return when {
            label == needle -> 0
            label.startsWith(needle) -> 1
            label.contains(needle) -> 2
            normalizeSettingsSearchText(keywords).contains(needle) -> 3
            normalizeSettingsSearchText(parent).contains(needle) -> 4
            else -> 5
        }
    }
}

internal fun normalizeSettingsSearchText(value: String): String = value.lowercase(Locale.ROOT)
    .replace(Regex("[\\s,，。;；:/\\\\|·_\\-]+"), " ").trim()
