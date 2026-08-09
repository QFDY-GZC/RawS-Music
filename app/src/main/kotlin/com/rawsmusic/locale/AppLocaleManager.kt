package com.rawsmusic.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLocaleManager {
    const val SYSTEM = "system"
    const val SIMPLIFIED_CHINESE = "zh-CN"
    const val ENGLISH = "en"

    private const val PREFS = "raws_locale_preferences"
    private const val KEY_LANGUAGE = "application_language"

    fun currentLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)
            ?.takeIf { it in supportedLanguages }
            ?: SYSTEM

    fun wrap(context: Context): Context {
        val language = currentLanguage(context)
        if (language == SYSTEM || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context
        }
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(configuration)
    }

    fun apply(context: Context) {
        val language = currentLanguage(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (language == SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(language)
            }
            context.getSystemService(LocaleManager::class.java)?.let { manager ->
                if (manager.applicationLocales != locales) manager.applicationLocales = locales
            }
            return
        }
        val locale = if (language == SYSTEM) {
            ResourcesLocale.systemLocale()
        } else {
            Locale.forLanguageTag(language)
        }
        Locale.setDefault(locale)
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    fun setLanguage(context: Context, language: String) {
        require(language in supportedLanguages)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
        apply(context.applicationContext)
    }

    val supportedLanguages: Set<String> =
        setOf(SYSTEM, SIMPLIFIED_CHINESE, ENGLISH)

    private object ResourcesLocale {
        fun systemLocale(): Locale {
            val locales = Resources.getSystem().configuration.locales
            return if (locales.isEmpty) Locale.getDefault() else locales[0]
        }
    }
}
