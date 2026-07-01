package com.freevibe.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freevibe.R
import com.freevibe.data.model.WALLPAPER_SOURCE_LOCAL_FOLDER

@Composable
internal fun SchedulerSettingsSection(
    context: Context,
    viewModel: SettingsViewModel,
    schedulerEnabled: Boolean,
    schedulerInterval: Long,
    schedulerSource: String,
    schedulerHome: Boolean,
    schedulerLock: Boolean,
    schedulerShuffle: Boolean,
    localWallpaperFolderUri: String,
    localFolderPermissionActive: Boolean,
    wallhavenProviderEnabled: Boolean,
    pixabayProviderEnabled: Boolean,
    bingProviderEnabled: Boolean,
    onChooseLocalWallpaperFolder: (String?) -> Unit,
) {
    var showSchedulerInterval by remember { mutableStateOf(false) }
    var showSchedulerSource by remember { mutableStateOf(false) }
    var showCollectionPicker by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(R.string.settings_scheduler_section_title),
        description = stringResource(R.string.settings_scheduler_section_description),
    ) {
        SettingsToggle(
            icon = Icons.Default.Schedule,
            title = stringResource(R.string.settings_sched_auto_rotate_title),
            subtitle = if (schedulerEnabled) {
                stringResource(R.string.settings_sched_auto_rotate_on_subtitle, formatInterval(schedulerInterval))
            } else {
                stringResource(R.string.settings_sched_auto_rotate_off_subtitle)
            },
            checked = schedulerEnabled,
            onCheckedChange = viewModel::setSchedulerEnabled,
        )
        if (schedulerEnabled) {
            SettingsItem(
                icon = Icons.Default.Timer,
                title = stringResource(R.string.settings_sched_interval_title),
                subtitle = formatInterval(schedulerInterval),
                onClick = { showSchedulerInterval = true },
            )
            val collectionsList by viewModel.collections.collectAsStateWithLifecycle()
            val activeCollectionId by viewModel.schedulerCollectionId.collectAsStateWithLifecycle()
            val activeCollectionName = remember(collectionsList, activeCollectionId) {
                collectionsList.firstOrNull { it.collectionId == activeCollectionId }?.name
            }
            val sourceSubtitle = when {
                schedulerSource == "collection" && activeCollectionName != null ->
                    context.getString(R.string.settings_sched_collection_prefix, activeCollectionName)
                schedulerSource == "collection" ->
                    context.getString(R.string.settings_sched_collection_none)
                else ->
                    wallpaperRotationSourceLabel(
                        source = schedulerSource,
                        localFolderUri = localWallpaperFolderUri,
                        localFolderPermissionActive = localFolderPermissionActive,
                    )
            }
            SettingsItem(
                icon = Icons.Default.Source,
                title = stringResource(R.string.settings_sched_source_title),
                subtitle = sourceSubtitle,
                onClick = { showSchedulerSource = true },
            )
            SettingsToggle(
                icon = Icons.Default.Home,
                title = stringResource(R.string.settings_sched_home_title),
                subtitle = stringResource(R.string.settings_sched_home_subtitle),
                checked = schedulerHome,
                onCheckedChange = viewModel::setSchedulerHome,
            )
            SettingsToggle(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.settings_sched_lock_title),
                subtitle = stringResource(R.string.settings_sched_lock_subtitle),
                checked = schedulerLock,
                onCheckedChange = viewModel::setSchedulerLock,
            )
            SettingsToggle(
                icon = Icons.Default.Shuffle,
                title = stringResource(R.string.settings_sched_shuffle_title),
                subtitle = if (schedulerShuffle) {
                    stringResource(R.string.settings_sched_shuffle_on_subtitle)
                } else {
                    stringResource(R.string.settings_sched_shuffle_off_subtitle)
                },
                checked = schedulerShuffle,
                onCheckedChange = viewModel::setSchedulerShuffle,
            )
        }
    }

    if (showSchedulerInterval) {
        val intervals = listOf(
            15L to stringResource(R.string.settings_sched_interval_15m),
            30L to stringResource(R.string.settings_sched_interval_30m),
            60L to stringResource(R.string.settings_sched_interval_1h),
            120L to stringResource(R.string.settings_sched_interval_2h),
            360L to stringResource(R.string.settings_sched_interval_6h),
            720L to stringResource(R.string.settings_sched_interval_12h),
            1440L to stringResource(R.string.settings_sched_interval_24h),
            2880L to stringResource(R.string.settings_sched_interval_2d),
        )
        AlertDialog(
            onDismissRequest = { showSchedulerInterval = false },
            title = { Text(stringResource(R.string.settings_sched_interval_title)) },
            text = {
                Column {
                    intervals.forEach { (min, label) ->
                        SettingsRadioOptionRow(
                            label = label,
                            selected = schedulerInterval == min,
                            onClick = {
                                viewModel.setSchedulerInterval(min)
                                showSchedulerInterval = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSchedulerInterval = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showSchedulerSource) {
        val sources = listOf(
            "discover" to stringResource(R.string.settings_sched_source_discover),
            "favorites" to stringResource(R.string.settings_sched_source_favorites),
            WALLPAPER_SOURCE_LOCAL_FOLDER to stringResource(R.string.settings_sched_source_local),
            "wallhaven" to stringResource(R.string.settings_sched_source_wallhaven),
            "pixabay" to stringResource(R.string.settings_sched_source_pixabay),
            "bing" to stringResource(R.string.settings_sched_source_bing),
            "collection" to stringResource(R.string.settings_sched_source_collection),
        ).filter { (key, _) ->
            when (key) {
                "wallhaven" -> wallhavenProviderEnabled || schedulerSource == "wallhaven"
                "pixabay" -> pixabayProviderEnabled || schedulerSource == "pixabay"
                "bing" -> bingProviderEnabled || schedulerSource == "bing"
                else -> true
            }
        }
        AlertDialog(
            onDismissRequest = { showSchedulerSource = false },
            title = { Text(stringResource(R.string.settings_sched_wp_source_title)) },
            text = {
                Column {
                    sources.forEach { (key, label) ->
                        SettingsRadioOptionRow(
                            label = label,
                            selected = schedulerSource == key,
                            onClick = {
                                if (key == "collection") {
                                    showSchedulerSource = false
                                    showCollectionPicker = true
                                } else if (
                                    key == WALLPAPER_SOURCE_LOCAL_FOLDER &&
                                    !isLocalWallpaperFolderReady(
                                        localWallpaperFolderUri,
                                        localFolderPermissionActive,
                                    )
                                ) {
                                    showSchedulerSource = false
                                    onChooseLocalWallpaperFolder("scheduler")
                                } else {
                                    viewModel.setSchedulerSource(key)
                                    showSchedulerSource = false
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSchedulerSource = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showCollectionPicker) {
        val collections by viewModel.collections.collectAsStateWithLifecycle()
        val activeId by viewModel.schedulerCollectionId.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showCollectionPicker = false },
            title = { Text(stringResource(R.string.settings_sched_collection_picker_title)) },
            text = {
                if (collections.isEmpty()) {
                    Column {
                        Text(
                            stringResource(R.string.settings_sched_collection_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.settings_sched_collection_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        collections.forEach { collection ->
                            SettingsRadioOptionRow(
                                label = collection.name,
                                selected = activeId == collection.collectionId,
                                onClick = {
                                    viewModel.setSchedulerCollection(collection.collectionId)
                                    showCollectionPicker = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCollectionPicker = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
