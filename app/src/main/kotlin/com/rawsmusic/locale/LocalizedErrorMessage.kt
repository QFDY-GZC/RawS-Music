package com.rawsmusic.locale

import android.content.Context
import androidx.annotation.StringRes

/**
 * Keeps useful device/runtime error text when it already matches the active UI language, while
 * preventing legacy Chinese-only lower-layer diagnostics from leaking into non-Chinese UI.
 */
fun Context.localizedErrorMessage(
    error: Throwable?,
    @StringRes fallbackRes: Int,
    vararg fallbackArgs: Any,
): String = localizedErrorMessage(
    rawMessage = error?.message,
    fallback = getString(fallbackRes, *fallbackArgs),
)

fun Context.localizedErrorMessage(
    rawMessage: String?,
    @StringRes fallbackRes: Int,
    vararg fallbackArgs: Any,
): String = localizedErrorMessage(
    rawMessage = rawMessage,
    fallback = getString(fallbackRes, *fallbackArgs),
)

private fun Context.localizedErrorMessage(
    rawMessage: String?,
    fallback: String,
): String {
    val raw = rawMessage?.trim().orEmpty()
    if (raw.isBlank()) return fallback
    val language = resources.configuration.locales[0].language
    if (language == "zh") return raw
    val containsHan = raw.any { ch ->
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
    }
    return if (containsHan) fallback else raw
}
