package com.chloemlla.aura.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.chloemlla.aura.R
import com.chloemlla.aura.data.repository.GeneratedAssetAudit

@Composable
internal fun StorageSettingsSection(
    viewModel: SettingsViewModel,
    cacheUsage: CacheUsageState,
    generatedAssets: GeneratedAssetAudit,
    onDownloadsClick: () -> Unit,
) {
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    SettingsSection(
        sectionKey = SettingsSectionKeys.STORAGE,
        title = stringResource(R.string.settings_storage_section_title),
        description = stringResource(R.string.settings_storage_section_description),
    ) {
        SettingsItem(
            icon = Icons.Default.Download,
            title = stringResource(R.string.settings_storage_downloads_title),
            subtitle = stringResource(R.string.settings_storage_downloads_subtitle),
            onClick = onDownloadsClick,
        )
        SettingsItem(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.settings_storage_free_up_title),
            subtitle = cacheUsageSubtitle(cacheUsage),
            onClick = { showClearCacheConfirm = true },
        )
        // Generated PNGs are only deleted once nothing references them, so surfacing
        // the split (and any reference whose file is gone) is the only way a user can
        // tell "pinned" apart from "leaked".
        SettingsItem(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.settings_storage_generated_title),
            subtitle = generatedAssetsSubtitle(generatedAssets),
            onClick = viewModel::refreshGeneratedAssetAudit,
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.settings_picker_clear_cache_title)) },
            text = { Text(clearCacheConfirmation(cacheUsage)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCache()
                        showClearCacheConfirm = false
                    },
                ) {
                    Text(
                        stringResource(R.string.settings_picker_clear_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
internal fun generatedAssetsSubtitle(audit: GeneratedAssetAudit): String {
    val total = audit.referencedFiles + audit.unreferencedFiles
    if (total == 0 && audit.staleReferences == 0) {
        return stringResource(R.string.settings_storage_generated_empty)
    }
    val base = stringResource(
        R.string.settings_storage_generated_summary,
        audit.referencedFiles,
        audit.unreferencedFiles,
    )
    return if (audit.staleReferences > 0) {
        base + " " + stringResource(R.string.settings_storage_generated_stale, audit.staleReferences)
    } else {
        base
    }
}
