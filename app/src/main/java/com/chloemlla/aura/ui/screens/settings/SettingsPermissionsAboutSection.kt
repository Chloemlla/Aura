package com.chloemlla.aura.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.chloemlla.aura.R
import com.chloemlla.aura.ui.util.openExternalUrl

internal const val AURA_SOURCE_URL = "https://github.com/SysAdminDoc/Aura"
internal const val AURA_PRIVACY_POLICY_URL = "https://github.com/SysAdminDoc/Aura/blob/main/docs/privacy/privacy-policy.md"
internal const val OPEN_METEO_LICENCE_URL = "https://open-meteo.com/en/licence"

internal enum class SettingsPermissionPrompt {
    DAILY_NOTIFICATION_REQUEST,
    DAILY_NOTIFICATION_RECOVERY,
    WEATHER_LOCATION_REQUEST,
    WEATHER_LOCATION_RECOVERY,
}

@Composable
internal fun SettingsPermissionPromptDialog(
    prompt: SettingsPermissionPrompt,
    onDismiss: () -> Unit,
    onLaunchNotificationPermission: () -> Unit,
    onLaunchLocationPermission: () -> Unit,
    onEnableDailyWallpaper: () -> Unit,
    onOpenNotificationSettings: () -> Boolean,
    onOpenAppSettings: () -> Boolean,
    onFeedback: (String) -> Unit,
) {
    val settingsUnavailable = stringResource(R.string.settings_feedback_settings_unavailable)
    val isRecovery = when (prompt) {
        SettingsPermissionPrompt.DAILY_NOTIFICATION_RECOVERY,
        SettingsPermissionPrompt.WEATHER_LOCATION_RECOVERY -> true
        SettingsPermissionPrompt.DAILY_NOTIFICATION_REQUEST,
        SettingsPermissionPrompt.WEATHER_LOCATION_REQUEST -> false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (prompt) {
                    SettingsPermissionPrompt.DAILY_NOTIFICATION_REQUEST,
                    SettingsPermissionPrompt.DAILY_NOTIFICATION_RECOVERY -> stringResource(R.string.permission_notification_title)
                    SettingsPermissionPrompt.WEATHER_LOCATION_REQUEST,
                    SettingsPermissionPrompt.WEATHER_LOCATION_RECOVERY -> stringResource(R.string.permission_location_title)
                },
            )
        },
        text = {
            Text(
                when (prompt) {
                    SettingsPermissionPrompt.DAILY_NOTIFICATION_REQUEST -> stringResource(R.string.permission_notification_body)
                    SettingsPermissionPrompt.DAILY_NOTIFICATION_RECOVERY -> stringResource(R.string.permission_notification_denied_body)
                    SettingsPermissionPrompt.WEATHER_LOCATION_REQUEST -> stringResource(R.string.permission_location_body)
                    SettingsPermissionPrompt.WEATHER_LOCATION_RECOVERY -> stringResource(R.string.permission_location_denied_body)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    val settingsOpened = when (prompt) {
                        SettingsPermissionPrompt.DAILY_NOTIFICATION_REQUEST -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                onLaunchNotificationPermission()
                            } else {
                                onEnableDailyWallpaper()
                            }
                            true
                        }
                        SettingsPermissionPrompt.DAILY_NOTIFICATION_RECOVERY -> onOpenNotificationSettings()
                        SettingsPermissionPrompt.WEATHER_LOCATION_REQUEST -> {
                            onLaunchLocationPermission()
                            true
                        }
                        SettingsPermissionPrompt.WEATHER_LOCATION_RECOVERY -> onOpenAppSettings()
                    }
                    if (!settingsOpened) {
                        onFeedback(settingsUnavailable)
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (isRecovery) R.string.permission_open_settings else R.string.permission_continue,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.permission_not_now))
            }
        },
    )
}

@Composable
internal fun PermissionsSettingsSection(context: Context) {
    SettingsSection(
        title = stringResource(R.string.settings_permissions_section_title),
        description = stringResource(R.string.settings_permissions_section_description),
    ) {
        PermissionTransparencyRow(
            icon = Icons.Default.Wallpaper,
            permission = stringResource(R.string.settings_perm_wallpaper),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_wallpaper_desc),
        )
        PermissionTransparencyRow(
            icon = Icons.Default.Language,
            permission = stringResource(R.string.settings_perm_internet),
            scope = PermissionScope.REMOTE,
            description = stringResource(R.string.settings_perm_internet_desc),
        )
        PermissionTransparencyRow(
            icon = Icons.Default.Notifications,
            permission = stringResource(R.string.settings_perm_notifications),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_notifications_desc),
            granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
        )
        PermissionTransparencyRow(
            icon = Icons.Default.LocationOn,
            permission = stringResource(R.string.settings_perm_location),
            scope = PermissionScope.REMOTE,
            description = stringResource(R.string.settings_perm_location_desc),
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
        PermissionTransparencyRow(
            icon = Icons.Default.Contacts,
            permission = stringResource(R.string.settings_perm_contacts),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_contacts_desc),
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED,
        )
        PermissionTransparencyRow(
            icon = Icons.Default.Mic,
            permission = stringResource(R.string.settings_perm_microphone),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_microphone_desc),
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
        PermissionTransparencyRow(
            icon = Icons.Default.Settings,
            permission = stringResource(R.string.settings_perm_modify_settings),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_modify_settings_desc),
        )
        PermissionTransparencyRow(
            icon = Icons.Default.PlayCircle,
            permission = stringResource(R.string.settings_perm_foreground),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_foreground_desc),
        )
    }
}

@Composable
internal fun AboutSettingsSection(
    context: Context,
    onLicensesClick: () -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_about_section_title),
        description = stringResource(R.string.settings_about_section_description),
    ) {
        SettingsItem(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_about_app_title),
            subtitle = stringResource(R.string.settings_about_app_subtitle, com.chloemlla.aura.BuildConfig.VERSION_NAME),
            onClick = {},
        )
        SettingsItem(
            icon = Icons.Default.Code,
            title = stringResource(R.string.settings_about_source_title),
            subtitle = stringResource(R.string.settings_about_source_subtitle),
            onClick = { openExternalUrl(context, AURA_SOURCE_URL) },
        )
        SettingsItem(
            icon = Icons.Default.Security,
            title = stringResource(R.string.settings_about_privacy_title),
            subtitle = stringResource(R.string.settings_about_privacy_subtitle),
            onClick = { openExternalUrl(context, AURA_PRIVACY_POLICY_URL) },
        )
        SettingsItem(
            icon = Icons.Default.Description,
            title = stringResource(R.string.settings_about_licenses_title),
            subtitle = stringResource(R.string.settings_about_licenses_subtitle),
            onClick = onLicensesClick,
        )
    }
}
