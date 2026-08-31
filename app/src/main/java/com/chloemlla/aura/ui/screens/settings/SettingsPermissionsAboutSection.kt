package com.chloemlla.aura.ui.screens.settings

import android.Manifest
import android.app.NotificationManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    var notificationsGranted by remember { mutableStateOf(notificationsPermissionGranted(context)) }
    var locationGranted by remember {
        mutableStateOf(checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    var contactsGranted by remember {
        mutableStateOf(checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS))
    }
    var dndAccessGranted by remember {
        mutableStateOf(context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true)
    }
    var microphoneGranted by remember {
        mutableStateOf(checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
    }
    // Re-read on every resume so badges refresh after the user returns from the
    // system settings screen where a permission was toggled (AURA-G8-20).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = notificationsPermissionGranted(context)
                locationGranted = checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                contactsGranted = checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
                dndAccessGranted = context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationPolicyAccessGranted == true
                microphoneGranted = checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsSection(
        sectionKey = SettingsSectionKeys.PERMISSIONS,
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
            granted = notificationsGranted,
        )
        PermissionTransparencyRow(
            icon = Icons.Default.LocationOn,
            permission = stringResource(R.string.settings_perm_location),
            scope = PermissionScope.REMOTE,
            description = stringResource(R.string.settings_perm_location_desc),
            granted = locationGranted,
        )
        PermissionTransparencyRow(
            icon = Icons.Default.Contacts,
            permission = stringResource(R.string.settings_perm_contacts),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_contacts_desc),
            granted = contactsGranted,
        )
        PermissionTransparencyRow(
            icon = Icons.Default.Notifications,
            permission = stringResource(R.string.settings_perm_dnd),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_dnd_desc),
            granted = dndAccessGranted,
        )
        PermissionTransparencyRow(
            icon = Icons.Default.Mic,
            permission = stringResource(R.string.settings_perm_microphone),
            scope = PermissionScope.LOCAL,
            description = stringResource(R.string.settings_perm_microphone_desc),
            granted = microphoneGranted,
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

private fun checkSelfPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun notificationsPermissionGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }

@Composable
internal fun AboutSettingsSection(
    context: Context,
    onLicensesClick: () -> Unit,
) {
    SettingsSection(
        sectionKey = SettingsSectionKeys.ABOUT,
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
