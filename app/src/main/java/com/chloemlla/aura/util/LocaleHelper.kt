package com.chloemlla.aura.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * App language selection. On API 33+ the platform per-app language API owns the
 * value, so the system "App languages" screen and the in-app picker cannot drift
 * apart and the process default locale is left alone. Below 33 there is no
 * platform API (and no appcompat dependency here), so the legacy
 * SharedPreferences + wrapped Configuration path remains as the fallback.
 */
object LocaleHelper {
    private const val PREFS_NAME = "freevibe_locale"
    private const val KEY_APP_LOCALE = "app_locale"

    // Default: follow system
    private const val SYSTEM_LOCALE = ""

    fun getAppLocaleTag(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            platformLocaleTag(context).ifBlank { storedLocaleTag(context) }
        } else {
            storedLocaleTag(context)
        }

    fun setAppLocale(context: Context, localeTag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setPlatformLocaleTag(context, localeTag)
            storeLocaleTag(context, SYSTEM_LOCALE)
        } else {
            storeLocaleTag(context, localeTag)
        }
    }

    /**
     * Hands a language picked by an older build over to the platform once, so an
     * upgraded install keeps its language instead of silently falling back to the
     * system one when [wrapContext] stops wrapping.
     */
    fun migrateStoredLocaleToPlatform(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val stored = storedLocaleTag(context)
        if (stored.isBlank()) return
        runCatching {
            if (platformLocaleTag(context).isBlank()) setPlatformLocaleTag(context, stored)
            storeLocaleTag(context, SYSTEM_LOCALE)
        }
    }

    fun wrapContext(context: Context): Context {
        // API 33+ resolves the app locale in the platform, which also rebuilds the
        // components itself; wrapping here would shadow that choice.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context

        val localeTag = storedLocaleTag(context)
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

    private fun storedLocaleTag(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LOCALE, SYSTEM_LOCALE) ?: SYSTEM_LOCALE

    private fun storeLocaleTag(context: Context, localeTag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LOCALE, localeTag)
            .apply()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun platformLocaleTag(context: Context): String {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        if (locales == null || locales.isEmpty) return SYSTEM_LOCALE
        return normalizeToSupportedTag(locales[0].toLanguageTag())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setPlatformLocaleTag(context: Context, localeTag: String) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = if (localeTag.isBlank()) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(localeTag)
        }
    }

    /**
     * The platform may report a region-qualified tag ("zh-Hans-CN") for a language
     * stored as "zh"; the picker matches option tags exactly.
     */
    private fun normalizeToSupportedTag(localeTag: String): String {
        if (localeTag.isBlank()) return SYSTEM_LOCALE
        val language = Locale.forLanguageTag(localeTag).language
        return getSupportedLanguages()
            .firstOrNull { it.tag.isNotBlank() && it.tag == language }
            ?.tag
            ?: localeTag
    }
}
