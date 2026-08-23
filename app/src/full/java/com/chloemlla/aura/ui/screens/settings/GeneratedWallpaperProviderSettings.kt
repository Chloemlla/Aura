package com.chloemlla.aura.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.chloemlla.aura.R
import com.chloemlla.aura.ui.screens.aigenerate.GeneratedWallpaperDisclosureDialog

@Composable
internal fun GeneratedWallpaperProviderSettings(
    viewModel: SettingsViewModel,
    providerKey: String,
    enabled: Boolean,
    disclosureAccepted: Boolean,
    onOpenStudio: () -> Unit,
) {
    var showProviderKey by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled) showProviderKey = false
    }

    SettingsToggle(
        icon = Icons.Default.AutoAwesome,
        title = stringResource(R.string.settings_services_generated_enable_title),
        subtitle = if (enabled) {
            stringResource(R.string.settings_services_generated_on_subtitle)
        } else {
            stringResource(R.string.settings_services_generated_off_subtitle)
        },
        checked = enabled,
        onCheckedChange = viewModel::setGeneratedContentProviderEnabled,
    )
    SettingsItem(
        icon = Icons.Default.Info,
        title = stringResource(R.string.settings_services_generated_disclosure_title),
        subtitle = if (disclosureAccepted) {
            stringResource(R.string.settings_services_generated_disclosure_accepted_subtitle)
        } else {
            stringResource(R.string.settings_services_generated_disclosure_subtitle)
        },
        onClick = { showDisclosure = true },
    )
    if (enabled) {
        SettingsItem(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.settings_services_generated_studio_title),
            subtitle = stringResource(R.string.settings_services_generated_studio_subtitle),
            onClick = onOpenStudio,
        )
        SettingsItem(
            icon = Icons.Default.Key,
            title = stringResource(R.string.settings_services_stability_key_title),
            subtitle = stringResource(R.string.settings_services_stability_key_subtitle),
            onClick = { showProviderKey = true },
        )
    }

    if (showDisclosure) {
        GeneratedWallpaperDisclosureDialog(
            accepted = disclosureAccepted,
            onAccept = viewModel::acceptGeneratedContentDisclosure,
            onReset = viewModel::resetGeneratedContentDisclosure,
            onDismiss = { showDisclosure = false },
        )
    }
    if (enabled && showProviderKey) {
        ProviderApiKeyDialog(
            title = stringResource(R.string.settings_services_stability_dialog_title),
            description = stringResource(R.string.settings_services_stability_dialog_desc),
            value = providerKey,
            placeholder = stringResource(R.string.settings_services_stability_dialog_placeholder),
            onSave = viewModel::setGeneratedWallpaperProviderKey,
            onDismiss = { showProviderKey = false },
        )
    }
}
