package com.freevibe.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.freevibe.R
import com.freevibe.data.repository.CommunityBlockedUser
import com.freevibe.service.CommunityIdentitySummary
import com.freevibe.ui.components.CommunityGuidelinesDialog

@Composable
internal fun ServicesCommunitySettingsSection(
    context: Context,
    viewModel: SettingsViewModel,
    communityProviderEnabled: Boolean,
    communityGuidelinesAccepted: Boolean,
    communityGuidelinesAcceptedVersion: Int,
    communityIdentitySummary: CommunityIdentitySummary,
    communityIdentityCleanup: CommunityIdentityCleanupState,
    blockedCommunityCreators: List<CommunityBlockedUser>,
    communityBlockAction: CommunityBlockActionState,
    wallhavenApiKey: String,
    pexelsApiKey: String,
    pixabayApiKey: String,
    freesoundApiKey: String,
    generatedWallpaperProviderKey: String,
    providerCredentialStorageUnavailable: Boolean,
    generatedContentProviderEnabled: Boolean,
    generatedContentDisclosureAccepted: Boolean,
    wallhavenProviderEnabled: Boolean,
    pexelsProviderEnabled: Boolean,
    pixabayProviderEnabled: Boolean,
    showSketchyContent: Boolean,
    showNsfwContent: Boolean,
    onCreatorProfileClick: () -> Unit,
    onCommunityReportsClick: () -> Unit,
    onGeneratedWallpapersClick: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    var showCommunityIdentity by remember { mutableStateOf(false) }
    var showBlockedCreators by remember { mutableStateOf(false) }
    var showCommunityGuidelines by remember { mutableStateOf(false) }
    var showWallhavenKey by remember { mutableStateOf(false) }
    var showPexelsKey by remember { mutableStateOf(false) }
    var showPixabayKey by remember { mutableStateOf(false) }
    var showFreesoundKey by remember { mutableStateOf(false) }

    LaunchedEffect(communityBlockAction.message, communityBlockAction.error) {
        communityBlockAction.message?.let {
            onFeedback(it)
            viewModel.clearCommunityBlockAction()
        }
        communityBlockAction.error?.let {
            onFeedback(it)
            viewModel.clearCommunityBlockAction()
        }
    }
    LaunchedEffect(communityIdentityCleanup.message, communityIdentityCleanup.error) {
        communityIdentityCleanup.message?.let {
            onFeedback(it)
            viewModel.clearCommunityIdentityCleanupState()
        }
        communityIdentityCleanup.error?.let {
            onFeedback(it)
            viewModel.clearCommunityIdentityCleanupState()
        }
    }
    SettingsSection(
        sectionKey = SettingsSectionKeys.SERVICES,
        title = stringResource(R.string.settings_services_section_title),
        description = stringResource(R.string.settings_services_section_description),
    ) {
        SettingsToggle(
            icon = Icons.Default.Groups,
            title = stringResource(R.string.settings_services_community_title),
            subtitle = if (communityProviderEnabled) {
                stringResource(R.string.settings_services_community_on_subtitle)
            } else {
                stringResource(R.string.settings_services_community_off_subtitle)
            },
            checked = communityProviderEnabled,
            onCheckedChange = viewModel::setCommunityProviderEnabled,
            searchAliases = setOf("firebase", "app check", "integrity"),
        )
        if (communityProviderEnabled) {
            SettingsItem(
                icon = Icons.Default.VerifiedUser,
                title = stringResource(R.string.settings_services_guidelines_title),
                subtitle = if (communityGuidelinesAccepted) {
                    stringResource(R.string.settings_services_guidelines_accepted_subtitle, communityGuidelinesAcceptedVersion)
                } else {
                    stringResource(R.string.settings_services_guidelines_required_subtitle)
                },
                onClick = { showCommunityGuidelines = true },
            )
        }
        if (communityProviderEnabled && communityGuidelinesAccepted) {
            SettingsItem(
                icon = Icons.Default.Person,
                title = stringResource(R.string.settings_services_identity_title),
                subtitle = communityIdentitySubtitle(communityIdentitySummary),
                onClick = {
                    viewModel.refreshCommunityIdentitySummary()
                    showCommunityIdentity = true
                },
            )
            SettingsItem(
                icon = Icons.Default.Person,
                title = stringResource(R.string.settings_services_creator_title),
                subtitle = stringResource(R.string.settings_services_creator_subtitle),
                onClick = onCreatorProfileClick,
            )
            SettingsItem(
                icon = Icons.Default.Block,
                title = stringResource(R.string.settings_services_blocked_title),
                subtitle = if (blockedCommunityCreators.isEmpty()) {
                    stringResource(R.string.settings_services_blocked_none_subtitle)
                } else {
                    stringResource(R.string.settings_services_blocked_count_subtitle, blockedCommunityCreators.size)
                },
                onClick = { showBlockedCreators = true },
            )
            if (viewModel.isAdmin) {
                SettingsItem(
                    icon = Icons.Default.Report,
                    title = stringResource(R.string.settings_services_reports_title),
                    subtitle = stringResource(R.string.settings_services_reports_subtitle),
                    onClick = onCommunityReportsClick,
                )
            }
        }
        SettingsItem(
            icon = Icons.Default.Key,
            title = stringResource(R.string.settings_services_wallhaven_key_title),
            subtitle = stringResource(R.string.settings_services_wallhaven_key_subtitle),
            onClick = { showWallhavenKey = true },
        )
        if (providerCredentialStorageUnavailable) {
            SettingsItem(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.settings_services_provider_key_storage_warning_title),
                subtitle = stringResource(R.string.settings_services_provider_key_storage_warning_subtitle),
                onClick = { },
            )
        }
        SettingsToggle(
            icon = Icons.Default.ImageSearch,
            title = stringResource(R.string.settings_services_wallhaven_enable_title),
            subtitle = if (wallhavenProviderEnabled) {
                stringResource(R.string.settings_services_wallhaven_on_subtitle)
            } else {
                stringResource(R.string.settings_services_wallhaven_off_subtitle)
            },
            checked = wallhavenProviderEnabled,
            onCheckedChange = viewModel::setWallhavenProviderEnabled,
        )
        SettingsToggle(
            icon = Icons.Default.Visibility,
            title = stringResource(R.string.settings_services_sketchy_title),
            subtitle = if (wallhavenApiKey.isBlank()) {
                stringResource(R.string.settings_services_sketchy_no_key_subtitle)
            } else {
                stringResource(R.string.settings_services_sketchy_subtitle)
            },
            checked = showSketchyContent,
            onCheckedChange = viewModel::setShowSketchy,
        )
        SettingsToggle(
            icon = Icons.Default.Warning,
            title = stringResource(R.string.settings_services_nsfw_title),
            subtitle = if (wallhavenApiKey.isBlank()) {
                stringResource(R.string.settings_services_nsfw_no_key_subtitle)
            } else {
                stringResource(R.string.settings_services_nsfw_subtitle)
            },
            checked = showNsfwContent,
            onCheckedChange = viewModel::setShowNsfw,
        )
        SettingsToggle(
            icon = Icons.Default.PhotoLibrary,
            title = stringResource(R.string.settings_services_pexels_enable_title),
            subtitle = if (pexelsProviderEnabled) {
                stringResource(R.string.settings_services_pexels_on_subtitle)
            } else {
                stringResource(R.string.settings_services_pexels_off_subtitle)
            },
            checked = pexelsProviderEnabled,
            onCheckedChange = viewModel::setPexelsProviderEnabled,
        )
        SettingsItem(
            icon = Icons.Default.Key,
            title = stringResource(R.string.settings_services_pexels_key_title),
            subtitle = stringResource(R.string.settings_services_pexels_key_subtitle),
            onClick = { showPexelsKey = true },
        )
        SettingsToggle(
            icon = Icons.Default.Collections,
            title = stringResource(R.string.settings_services_pixabay_enable_title),
            subtitle = if (pixabayProviderEnabled) {
                stringResource(R.string.settings_services_pixabay_on_subtitle)
            } else {
                stringResource(R.string.settings_services_pixabay_off_subtitle)
            },
            checked = pixabayProviderEnabled,
            onCheckedChange = viewModel::setPixabayProviderEnabled,
        )
        SettingsItem(
            icon = Icons.Default.Key,
            title = stringResource(R.string.settings_services_pixabay_key_title),
            subtitle = stringResource(R.string.settings_services_pixabay_key_subtitle),
            onClick = { showPixabayKey = true },
        )
        SettingsItem(
            icon = Icons.Default.MusicNote,
            title = stringResource(R.string.settings_services_freesound_key_title),
            subtitle = stringResource(R.string.settings_services_freesound_key_subtitle),
            onClick = { showFreesoundKey = true },
        )
        GeneratedWallpaperProviderSettings(
            viewModel = viewModel,
            providerKey = generatedWallpaperProviderKey,
            enabled = generatedContentProviderEnabled,
            disclosureAccepted = generatedContentDisclosureAccepted,
            onOpenStudio = onGeneratedWallpapersClick,
        )
    }

    if (showCommunityIdentity) {
        CommunityIdentityDialog(
            summary = communityIdentitySummary,
            cleanupBusy = communityIdentityCleanup.clearing,
            onRefresh = viewModel::refreshCommunityIdentitySummary,
            onClearLocal = viewModel::clearLocalCommunityIdentity,
            onCopyCode = { code ->
                copyCommunityDeletionCode(context, code, onFeedback)
            },
            onShareRequest = { summary ->
                shareCommunityDeletionRequest(context, summary, onFeedback)
            },
            onDismiss = { showCommunityIdentity = false },
        )
    }
    if (showBlockedCreators) {
        BlockedCreatorsDialog(
            blockedCreators = blockedCommunityCreators,
            actionState = communityBlockAction,
            onUnblock = viewModel::unblockCommunityCreator,
            onDismiss = { showBlockedCreators = false },
        )
    }
    if (showCommunityGuidelines) {
        CommunityGuidelinesDialog(
            onAccept = {
                viewModel.acceptCommunityGuidelines()
                showCommunityGuidelines = false
            },
            onReset = if (communityGuidelinesAccepted) {
                {
                    viewModel.resetCommunityGuidelines()
                    showCommunityGuidelines = false
                }
            } else {
                null
            },
            onDismiss = { showCommunityGuidelines = false },
        )
    }
    if (showWallhavenKey) {
        ProviderApiKeyDialog(
            title = stringResource(R.string.settings_services_wallhaven_dialog_title),
            description = stringResource(R.string.settings_services_wallhaven_dialog_desc),
            value = wallhavenApiKey,
            placeholder = stringResource(R.string.settings_services_wallhaven_dialog_placeholder),
            onSave = viewModel::setWallhavenKey,
            onDismiss = { showWallhavenKey = false },
        )
    }
    if (showPexelsKey) {
        ProviderApiKeyDialog(
            title = stringResource(R.string.settings_services_pexels_dialog_title),
            description = stringResource(R.string.settings_services_pexels_dialog_desc),
            value = pexelsApiKey,
            placeholder = stringResource(R.string.settings_services_pexels_dialog_placeholder),
            onSave = viewModel::setPexelsKey,
            onDismiss = { showPexelsKey = false },
        )
    }
    if (showPixabayKey) {
        ProviderApiKeyDialog(
            title = stringResource(R.string.settings_services_pixabay_dialog_title),
            description = stringResource(R.string.settings_services_pixabay_dialog_desc),
            value = pixabayApiKey,
            placeholder = stringResource(R.string.settings_services_pixabay_dialog_placeholder),
            onSave = viewModel::setPixabayKey,
            onDismiss = { showPixabayKey = false },
        )
    }
    if (showFreesoundKey) {
        ProviderApiKeyDialog(
            title = stringResource(R.string.settings_services_freesound_dialog_title),
            description = stringResource(R.string.settings_services_freesound_dialog_desc),
            value = freesoundApiKey,
            placeholder = stringResource(R.string.settings_services_freesound_dialog_placeholder),
            onSave = viewModel::setFreesoundKey,
            onDismiss = { showFreesoundKey = false },
        )
    }
}

@Composable
internal fun ProviderApiKeyDialog(
    title: String,
    description: String,
    value: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyText by remember(value) { mutableStateOf(value) }
    val canClear = keyText.isNotBlank() || value.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(keyText.trim())
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.settings_apikey_save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = canClear,
                    onClick = {
                        onSave("")
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.settings_apikey_clear))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
