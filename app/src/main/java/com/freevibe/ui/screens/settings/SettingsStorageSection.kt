package com.freevibe.ui.screens.settings

import androidx.compose.material.icons.Icons
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
import com.freevibe.R

@Composable
internal fun StorageSettingsSection(
    viewModel: SettingsViewModel,
    cacheUsage: CacheUsageState,
    onDownloadsClick: () -> Unit,
) {
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    SettingsSection(
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
