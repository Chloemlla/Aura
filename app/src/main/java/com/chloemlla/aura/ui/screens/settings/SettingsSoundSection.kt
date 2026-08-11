package com.chloemlla.aura.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chloemlla.aura.R
import com.chloemlla.aura.service.normalizeYouTubePoTokenProviderUrl

@Composable
internal fun SoundSettingsSection(
    viewModel: SettingsViewModel,
    autoPreview: Boolean,
    previewVolume: Float,
    ytRingtonesQuery: String,
    ytNotificationsQuery: String,
    ytAlarmsQuery: String,
    ytBlockedWords: String,
    youtubeProviderEnabled: Boolean,
    youtubePoTokenProviderUrl: String,
    ringtoneShuffleEnabled: Boolean,
    ringtoneShuffleIntervalHours: Long,
    alarmShuffleEnabled: Boolean,
    soundProfilesEnabled: Boolean,
    soundProfilesJson: String,
    ytDlpUpdate: YtDlpUpdateUiState,
    onLicensesClick: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    var showYtSoundEditor by remember { mutableStateOf(false) }
    var showYtBlockedEditor by remember { mutableStateOf(false) }
    var showPoTokenProviderEditor by remember { mutableStateOf(false) }
    val ytDlpUpdateNotice = ytDlpUpdateFeedbackMessage(ytDlpUpdate)

    LaunchedEffect(ytDlpUpdate.completedStatus, ytDlpUpdate.error) {
        ytDlpUpdateNotice?.let {
            onFeedback(it)
            viewModel.clearYtDlpUpdateNotice()
        }
    }

    SettingsSection(
        title = stringResource(R.string.settings_sounds_section_title),
        description = stringResource(R.string.settings_sounds_section_description),
    ) {
        SettingsToggle(
            icon = Icons.Default.PlayCircle,
            title = stringResource(R.string.settings_sounds_auto_preview_title),
            subtitle = if (autoPreview) {
                stringResource(R.string.settings_sounds_auto_preview_on_subtitle)
            } else {
                stringResource(R.string.settings_sounds_auto_preview_off_subtitle)
            },
            checked = autoPreview,
            onCheckedChange = viewModel::setAutoPreview,
        )
        PreviewVolumeSlider(
            previewVolume = previewVolume,
            onPreviewVolumeChange = viewModel::setPreviewVolume,
        )
        SettingsItem(
            icon = Icons.Default.SmartDisplay,
            title = stringResource(R.string.settings_sounds_yt_queries_title),
            subtitle = stringResource(R.string.settings_sounds_yt_queries_subtitle),
            onClick = { showYtSoundEditor = true },
        )
        SettingsToggle(
            icon = Icons.Default.SmartDisplay,
            title = stringResource(R.string.settings_sounds_yt_enable_title),
            subtitle = if (youtubeProviderEnabled) {
                stringResource(R.string.settings_sounds_yt_on_subtitle)
            } else {
                stringResource(R.string.settings_sounds_yt_off_subtitle)
            },
            checked = youtubeProviderEnabled,
            onCheckedChange = viewModel::setYoutubeProviderEnabled,
        )
        SettingsItem(
            icon = Icons.Default.Update,
            title = stringResource(R.string.settings_ytdlp_update_title),
            subtitle = ytDlpUpdateSubtitle(
                state = ytDlpUpdate,
                youtubeProviderEnabled = youtubeProviderEnabled,
            ),
            onClick = {
                if (youtubeProviderEnabled && !ytDlpUpdate.isUpdating) {
                    viewModel.updateYtDlp()
                }
            },
        )
        SettingsItem(
            icon = Icons.Default.SmartDisplay,
            title = stringResource(R.string.settings_youtube_pot_provider_title),
            subtitle = if (youtubePoTokenProviderUrl.isBlank()) {
                stringResource(R.string.settings_youtube_pot_provider_off)
            } else {
                stringResource(R.string.settings_youtube_pot_provider_on)
            },
            onClick = { showPoTokenProviderEditor = true },
        )
        SettingsItem(
            icon = Icons.Default.Block,
            title = stringResource(R.string.settings_sounds_blocked_words_title),
            subtitle = stringResource(
                R.string.settings_sounds_blocked_words_subtitle,
                ytBlockedWords.split(",").count { it.isNotBlank() },
            ),
            onClick = { showYtBlockedEditor = true },
        )
        SettingsItem(
            icon = Icons.Default.LibraryMusic,
            title = stringResource(R.string.settings_sounds_sources_title),
            subtitle = stringResource(R.string.settings_sounds_sources_subtitle),
            onClick = onLicensesClick,
        )
        SettingsToggle(
            icon = Icons.Default.Shuffle,
            title = stringResource(R.string.settings_sounds_ringtone_shuffle_title),
            subtitle = if (ringtoneShuffleEnabled) {
                stringResource(R.string.settings_sounds_ringtone_shuffle_on_subtitle, formatInterval(ringtoneShuffleIntervalHours * 60))
            } else {
                stringResource(R.string.settings_sounds_ringtone_shuffle_off_subtitle)
            },
            checked = ringtoneShuffleEnabled,
            onCheckedChange = viewModel::setRingtoneShuffleEnabled,
        )
        if (ringtoneShuffleEnabled) {
            RingtoneShuffleIntervalPicker(
                ringtoneShuffleIntervalHours = ringtoneShuffleIntervalHours,
                onSetIntervalHours = viewModel::setRingtoneShuffleIntervalHours,
            )
        }
        SettingsToggle(
            icon = Icons.Default.Alarm,
            title = stringResource(R.string.settings_sounds_alarm_shuffle_title),
            subtitle = if (alarmShuffleEnabled) {
                stringResource(R.string.settings_sounds_alarm_shuffle_on_subtitle)
            } else {
                stringResource(R.string.settings_sounds_alarm_shuffle_off_subtitle)
            },
            checked = alarmShuffleEnabled,
            onCheckedChange = viewModel::setAlarmShuffleEnabled,
        )
        val profileCount = remember(soundProfilesJson) {
            com.chloemlla.aura.service.parseProfiles(soundProfilesJson).size
        }
        SettingsToggle(
            icon = Icons.Default.Schedule,
            title = stringResource(R.string.settings_sounds_profiles_title),
            subtitle = if (soundProfilesEnabled) {
                stringResource(R.string.settings_sounds_profiles_on_subtitle, profileCount)
            } else {
                stringResource(R.string.settings_sounds_profiles_off_subtitle)
            },
            checked = soundProfilesEnabled,
            onCheckedChange = viewModel::setSoundProfilesEnabled,
        )
    }

    if (showYtSoundEditor) {
        YouTubeSoundQueriesDialog(
            ytRingtonesQuery = ytRingtonesQuery,
            ytNotificationsQuery = ytNotificationsQuery,
            ytAlarmsQuery = ytAlarmsQuery,
            onSave = { ringtone, notification, alarm ->
                viewModel.setYtRingtonesQuery(ringtone.trim())
                viewModel.setYtNotificationsQuery(notification.trim())
                viewModel.setYtAlarmsQuery(alarm.trim())
                showYtSoundEditor = false
            },
            onDismiss = { showYtSoundEditor = false },
        )
    }
    if (showYtBlockedEditor) {
        YouTubeBlockedWordsDialog(
            ytBlockedWords = ytBlockedWords,
            onSave = {
                viewModel.setYtBlockedWords(it.trim())
                showYtBlockedEditor = false
            },
            onDismiss = { showYtBlockedEditor = false },
        )
    }
    if (showPoTokenProviderEditor) {
        YouTubePoTokenProviderDialog(
            currentUrl = youtubePoTokenProviderUrl,
            onSave = {
                viewModel.setYoutubePoTokenProviderUrl(it)
                showPoTokenProviderEditor = false
            },
            onDismiss = { showPoTokenProviderEditor = false },
        )
    }
}

@Composable
private fun YouTubePoTokenProviderDialog(
    currentUrl: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(currentUrl) { mutableStateOf(currentUrl) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_youtube_pot_provider_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.settings_youtube_pot_provider_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.settings_youtube_pot_provider_url_label)) },
                    placeholder = { Text("https://pot.example.org") },
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.settings_youtube_pot_provider_invalid)) }
                    } else null,
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = normalizeYouTubePoTokenProviderUrl(value)
                    if (normalized == null) {
                        invalid = true
                    } else {
                        onSave(normalized)
                    }
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PreviewVolumeSlider(
    previewVolume: Float,
    onPreviewVolumeChange: (Float) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
        ),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_sounds_volume_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.settings_sounds_volume_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = previewVolume,
                    onValueChange = onPreviewVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                stringResource(R.string.settings_sounds_volume_percent, (previewVolume * 100).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RingtoneShuffleIntervalPicker(
    ringtoneShuffleIntervalHours: Long,
    onSetIntervalHours: (Long) -> Unit,
) {
    var showShuffleIntervalPicker by remember { mutableStateOf(false) }
    SettingsItem(
        icon = Icons.Default.Timer,
        title = stringResource(R.string.settings_sounds_shuffle_interval_title),
        subtitle = formatInterval(ringtoneShuffleIntervalHours * 60),
        onClick = { showShuffleIntervalPicker = true },
    )
    if (showShuffleIntervalPicker) {
        val intervals = listOf(
            1L to stringResource(R.string.settings_sounds_shuffle_every_hour),
            6L to stringResource(R.string.settings_sounds_shuffle_every_6h),
            12L to stringResource(R.string.settings_sounds_shuffle_every_12h),
            24L to stringResource(R.string.settings_sounds_shuffle_every_day),
            72L to stringResource(R.string.settings_sounds_shuffle_every_3d),
        )
        AlertDialog(
            onDismissRequest = { showShuffleIntervalPicker = false },
            title = { Text(stringResource(R.string.settings_sounds_shuffle_interval_title)) },
            text = {
                Column {
                    intervals.forEach { (hours, label) ->
                        SettingsRadioOptionRow(
                            label = label,
                            selected = ringtoneShuffleIntervalHours == hours,
                            onClick = {
                                onSetIntervalHours(hours)
                                showShuffleIntervalPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShuffleIntervalPicker = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun YouTubeSoundQueriesDialog(
    ytRingtonesQuery: String,
    ytNotificationsQuery: String,
    ytAlarmsQuery: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var ringQ by remember { mutableStateOf(ytRingtonesQuery) }
    var notifQ by remember { mutableStateOf(ytNotificationsQuery) }
    var alarmQ by remember { mutableStateOf(ytAlarmsQuery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_picker_yt_queries_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.settings_picker_yt_queries_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ringQ,
                    onValueChange = { ringQ = it },
                    label = { Text(stringResource(R.string.settings_picker_yt_ringtones_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = notifQ,
                    onValueChange = { notifQ = it },
                    label = { Text(stringResource(R.string.settings_picker_yt_notifications_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = alarmQ,
                    onValueChange = { alarmQ = it },
                    label = { Text(stringResource(R.string.settings_picker_yt_alarms_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ringQ, notifQ, alarmQ) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun YouTubeBlockedWordsDialog(
    ytBlockedWords: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var blockedText by remember { mutableStateOf(ytBlockedWords) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_picker_blocked_words_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_picker_blocked_words_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = blockedText,
                    onValueChange = { blockedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 5,
                    placeholder = { Text(stringResource(R.string.settings_picker_blocked_words_placeholder)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Text(
                    stringResource(
                        R.string.settings_picker_blocked_words_count,
                        blockedText.split(",").filter { it.isNotBlank() }.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(blockedText) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
