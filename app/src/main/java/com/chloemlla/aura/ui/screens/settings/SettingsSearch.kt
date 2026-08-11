package com.chloemlla.aura.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chloemlla.aura.R

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
    const val LANGUAGE = "language"
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
    SettingsSearchSection(SettingsSectionKeys.LANGUAGE, R.string.settings_language_section_title, R.string.settings_language_subtitle),
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

/** Keys of the sections whose title/description match [query] (all keys when blank). */
internal fun visibleSettingsSectionKeys(context: Context, query: String): Set<String> =
    SETTINGS_SEARCH_SECTIONS.filter { section ->
        settingsSectionMatchesQuery(
            query,
            context.getString(section.titleRes) + " " + context.getString(section.descriptionRes),
        )
    }.mapTo(mutableSetOf()) { it.key }

/** Settings search field plus the "no matches" message shown when [resultsEmpty]. */
@Composable
internal fun SettingsSearchBar(query: String, onQueryChange: (String) -> Unit, resultsEmpty: Boolean) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        label = { Text(stringResource(R.string.settings_search_hint)) },
        singleLine = true,
    )
    if (resultsEmpty) {
        Text(
            text = stringResource(R.string.settings_search_no_results, query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        )
    }
}
