package com.freevibe.ui.screens.settings

import com.freevibe.R

/**
 * One searchable settings section: its stable key plus the title/description string resources
 * whose text a search query is matched against. Keeps the search index declarative and testable
 * without duplicating per-setting copy.
 */
internal data class SettingsSearchSection(
    val key: String,
    val titleRes: Int,
    val descriptionRes: Int,
)

internal object SettingsSectionKeys {
    const val WALLPAPERS = "wallpapers"
    const val SCHEDULER = "scheduler"
    const val BACKUP = "backup"
    const val SMART = "smart"
    const val SOUNDS = "sounds"
    const val VIDEO = "video"
    const val SERVICES = "services"
    const val STORAGE = "storage"
    const val DIAGNOSTICS = "diagnostics"
    const val PERMISSIONS = "permissions"
    const val ABOUT = "about"
}

/** Search index in on-screen order; each entry maps a rendered section to its header copy. */
internal val SETTINGS_SEARCH_SECTIONS: List<SettingsSearchSection> = listOf(
    SettingsSearchSection(SettingsSectionKeys.WALLPAPERS, R.string.settings_wallpapers_section_title, R.string.settings_wallpapers_section_description),
    SettingsSearchSection(SettingsSectionKeys.SCHEDULER, R.string.settings_scheduler_section_title, R.string.settings_scheduler_section_description),
    SettingsSearchSection(SettingsSectionKeys.BACKUP, R.string.settings_backup_section_title, R.string.settings_backup_section_description),
    SettingsSearchSection(SettingsSectionKeys.SMART, R.string.settings_smart_section_title, R.string.settings_smart_section_description),
    SettingsSearchSection(SettingsSectionKeys.SOUNDS, R.string.settings_sounds_section_title, R.string.settings_sounds_section_description),
    SettingsSearchSection(SettingsSectionKeys.VIDEO, R.string.settings_video_section_title, R.string.settings_video_section_description),
    SettingsSearchSection(SettingsSectionKeys.SERVICES, R.string.settings_services_section_title, R.string.settings_services_section_description),
    SettingsSearchSection(SettingsSectionKeys.STORAGE, R.string.settings_storage_section_title, R.string.settings_storage_section_description),
    SettingsSearchSection(SettingsSectionKeys.DIAGNOSTICS, R.string.settings_diagnostics_section_title, R.string.settings_diagnostics_section_description),
    SettingsSearchSection(SettingsSectionKeys.PERMISSIONS, R.string.settings_permissions_section_title, R.string.settings_permissions_section_description),
    SettingsSearchSection(SettingsSectionKeys.ABOUT, R.string.settings_about_section_title, R.string.settings_about_section_description),
)

/**
 * True when [query] is blank (show everything) or, trimmed, is a case-insensitive substring of
 * [haystack] (a section's title + description). Pure so section filtering is unit-testable.
 */
internal fun settingsSectionMatchesQuery(query: String, haystack: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    return haystack.contains(trimmed, ignoreCase = true)
}
