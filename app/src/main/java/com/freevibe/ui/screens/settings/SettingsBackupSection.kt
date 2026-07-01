package com.freevibe.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
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
internal fun BackupSettingsSection(
    context: Context,
    viewModel: SettingsViewModel,
    autoBackupEnabled: Boolean,
    autoBackupFolderUri: String,
    autoBackupFolderPermissionActive: Boolean,
    autoBackupIntervalHours: Long,
    autoBackupKeepCount: Int,
    onChooseAutoBackupFolder: (Boolean) -> Unit,
    onFeedback: (String) -> Unit,
) {
    var showAutoBackupIntervalPicker by remember { mutableStateOf(false) }
    var showAutoBackupKeepPicker by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(R.string.settings_backup_section_title),
        description = stringResource(R.string.settings_backup_section_description),
    ) {
        SettingsToggle(
            icon = Icons.Default.FolderOpen,
            title = stringResource(R.string.settings_backup_scheduled_title),
            subtitle = autoBackupStatusSubtitle(
                enabled = autoBackupEnabled,
                folderUri = autoBackupFolderUri,
                folderPermissionActive = autoBackupFolderPermissionActive,
                intervalHours = autoBackupIntervalHours,
                keepCount = autoBackupKeepCount,
            ),
            checked = autoBackupEnabled,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    viewModel.setAutoBackupEnabled(false)
                } else if (!autoBackupFolderPermissionActive) {
                    onChooseAutoBackupFolder(true)
                    onFeedback(context.getString(R.string.settings_feedback_backup_choose_folder))
                } else {
                    viewModel.setAutoBackupEnabled(true)
                }
            },
        )
        SettingsItem(
            icon = Icons.Default.FolderOpen,
            title = stringResource(R.string.settings_backup_folder_title),
            subtitle = autoBackupFolderSubtitle(
                folderUri = autoBackupFolderUri,
                folderPermissionActive = autoBackupFolderPermissionActive,
            ),
            onClick = { onChooseAutoBackupFolder(false) },
        )
        if (autoBackupFolderUri.isNotBlank()) {
            SettingsItem(
                icon = Icons.Default.DeleteOutline,
                title = stringResource(R.string.settings_backup_clear_title),
                subtitle = stringResource(R.string.settings_backup_clear_subtitle),
                onClick = viewModel::clearAutoBackupFolderUri,
            )
        }
        SettingsItem(
            icon = Icons.Default.Timer,
            title = stringResource(R.string.settings_backup_interval_title),
            subtitle = formatAutoBackupInterval(autoBackupIntervalHours),
            onClick = { showAutoBackupIntervalPicker = true },
        )
        SettingsItem(
            icon = Icons.Default.History,
            title = stringResource(R.string.settings_backup_keep_title),
            subtitle = autoBackupRetentionLabel(autoBackupKeepCount),
            onClick = { showAutoBackupKeepPicker = true },
        )
    }

    if (showAutoBackupIntervalPicker) {
        val intervals = listOf(
            12L to stringResource(R.string.settings_picker_backup_interval_12h),
            24L to stringResource(R.string.settings_picker_backup_interval_daily),
            168L to stringResource(R.string.settings_picker_backup_interval_weekly),
            720L to stringResource(R.string.settings_picker_backup_interval_monthly),
        )
        AlertDialog(
            onDismissRequest = { showAutoBackupIntervalPicker = false },
            title = { Text(stringResource(R.string.settings_picker_backup_interval_title)) },
            text = {
                Column {
                    intervals.forEach { (hours, label) ->
                        SettingsRadioOptionRow(
                            label = label,
                            selected = autoBackupIntervalHours == hours,
                            onClick = {
                                viewModel.setAutoBackupIntervalHours(hours)
                                showAutoBackupIntervalPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoBackupIntervalPicker = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showAutoBackupKeepPicker) {
        val keepCounts = listOf(3, 5, 10, 20)
        AlertDialog(
            onDismissRequest = { showAutoBackupKeepPicker = false },
            title = { Text(stringResource(R.string.settings_picker_backup_keep_title)) },
            text = {
                Column {
                    keepCounts.forEach { count ->
                        SettingsRadioOptionRow(
                            label = autoBackupRetentionLabel(count),
                            selected = autoBackupKeepCount == count,
                            onClick = {
                                viewModel.setAutoBackupKeepCount(count)
                                showAutoBackupKeepPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoBackupKeepPicker = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
