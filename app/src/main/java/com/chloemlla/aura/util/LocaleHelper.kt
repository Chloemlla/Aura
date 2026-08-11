package com.chloemlla.aura.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Persists a user-chosen app locale (default = follow system) and wraps a
 * Context so its resources resolve in that locale. Non-AppCompat activities
 * pick the locale up via an attachBaseContext override; callers rebuild the
 * Activity (Activity.recreate) to apply a change immediately.
 */
object LocaleHelper {
    private const val PREFS_NAME = "freevibe_locale"
    private const val KEY_APP_LOCALE = "app_locale"

    // Default: follow system
    private const val SYSTEM_LOCALE = ""

    fun getAppLocaleTag(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LOCALE, SYSTEM_LOCALE) ?: SYSTEM_LOCALE
    }

    fun setAppLocale(context: Context, localeTag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LOCALE, localeTag)
            .apply()
    }

    fun wrapContext(context: Context): Context {
        val localeTag = getAppLocaleTag(context)
        if (localeTag.isBlank()) return context // Use system default

        val locale = Locale.forLanguageTag(localeTag)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }

    fun getDisplayName(localeTag: String): String {
        if (localeTag.isBlank()) return "System default"
        return Locale.forLanguageTag(localeTag).displayName
    }

    data class LanguageOption(val tag: String, val label: String)

    fun getSupportedLanguages(): List<LanguageOption> {
        return listOf(
            LanguageOption("", "System default"),
            LanguageOption("en", "English"),
            LanguageOption("zh", "中文"),
        )
    }
}
