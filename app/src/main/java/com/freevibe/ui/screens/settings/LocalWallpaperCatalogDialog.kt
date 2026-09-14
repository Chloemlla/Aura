package com.freevibe.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freevibe.R
import com.freevibe.data.model.LocalWallpaperEntity
import com.freevibe.data.model.LocalWallpaperFolderEntity
import com.freevibe.data.model.LocalWallpaperFolderScanStatus
import com.freevibe.data.model.LocalMediaStatus
import com.freevibe.data.model.RotationExclusionEntity
import com.freevibe.data.model.RotationExclusionIndex
import com.freevibe.data.model.WallpaperTarget
import com.freevibe.data.model.rotationIdentity
import com.freevibe.data.model.needsLocalMediaRelink
import com.freevibe.data.model.stableLocalMediaId
import com.freevibe.service.LocalMediaRelinkOutcome
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun LocalWallpaperCatalogDialogHost(
    show: Boolean,
    state: SettingsScreenState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    snackbarHostState: SnackbarHostState,
    rotationExclusions: List<RotationExclusionEntity>,
    onToggleRotationExclusion: (LocalWallpaperEntity) -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var pendingItem by remember { mutableStateOf<LocalWallpaperEntity?>(null) }
    var pendingFolder by remember { mutableStateOf<LocalWallpaperFolderEntity?>(null) }

    suspend fun finishItemRelink(item: LocalWallpaperEntity, uri: Uri, acceptMismatch: Boolean = false) {
        when (val outcome = viewModel.relinkLocalWallpaper(item.documentUri, uri, acceptMismatch)) {
            is LocalMediaRelinkOutcome.Relinked ->
                snackbarHostState.showSnackbar(resources.getString(R.string.media_relink_success))
            is LocalMediaRelinkOutcome.Rejected -> snackbarHostState.showSnackbar(outcome.message)
            is LocalMediaRelinkOutcome.ReviewRequired -> {
                val result = snackbarHostState.showSnackbar(
                    message = resources.getString(
                        R.string.media_relink_mismatch,
                        outcome.differences.joinToString(", "),
                    ),
                    actionLabel = resources.getString(R.string.media_relink_use_anyway),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) finishItemRelink(item, uri, acceptMismatch = true)
            }
        }
    }
    val itemRelinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val item = pendingItem
        pendingItem = null
        if (uri != null && item != null) scope.launch { finishItemRelink(item, uri) }
    }
    val folderRepairLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val folder = pendingFolder
        pendingFolder = null
        if (uri == null || folder == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            viewModel.repairLocalWallpaperFolder(folder.folderUri, uri.toString()).fold(
                onSuccess = { result ->
                    snackbarHostState.showSnackbar(
                        resources.getString(
                            R.string.settings_local_catalog_repair_summary,
                            result.relinkedCount,
                            result.remainingCount,
                        ),
                    )
                },
                onFailure = { error ->
                    snackbarHostState.showSnackbar(
                        error.message.orEmpty().ifBlank {
                            resources.getString(R.string.settings_local_catalog_repair_failed)
                        },
                    )
                },
            )
        }
    }
    LocalWallpaperCatalogDialog(
        folders = state.localWallpaperFolders,
        items = state.localWallpaperItems,
        onDismiss = onDismiss,
        onAddFolder = onAddFolder,
        onRescanAll = viewModel::rescanAllLocalWallpaperFolders,
        onRescanFolder = viewModel::rescanLocalWallpaperFolder,
        onRemoveFolder = viewModel::removeLocalWallpaperFolder,
        onRepairFolder = { folder ->
            pendingFolder = folder
            folderRepairLauncher.launch(null)
        },
        onSetFolderTarget = viewModel::setLocalWallpaperFolderTarget,
        onUpdateTags = viewModel::updateLocalWallpaperTags,
        onRelinkItem = { item ->
            pendingItem = item
            itemRelinkLauncher.launch(arrayOf("image/*"))
        },
        rotationExclusions = rotationExclusions,
        onToggleRotationExclusion = onToggleRotationExclusion,
    )
}

@Composable
internal fun LocalWallpaperCatalogDialog(
    folders: List<LocalWallpaperFolderEntity>,
    items: List<LocalWallpaperEntity>,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onRescanAll: () -> Unit,
    onRescanFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRepairFolder: (LocalWallpaperFolderEntity) -> Unit,
    onSetFolderTarget: (String, WallpaperTarget) -> Unit,
    onUpdateTags: (String, String) -> Unit,
    onRelinkItem: (LocalWallpaperEntity) -> Unit,
    rotationExclusions: List<RotationExclusionEntity> = emptyList(),
    onToggleRotationExclusion: (LocalWallpaperEntity) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var editingUri by remember { mutableStateOf<String?>(null) }
    var editingTags by remember { mutableStateOf("") }
    val rotationExclusionIndex = remember(rotationExclusions) { RotationExclusionIndex(rotationExclusions) }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val duplicateCounts = remember(items) {
        items.asSequence()
            .map { it.contentHash }
            .filter(String::isNotBlank)
            .groupBy { it }
            .mapValues { (_, group) -> group.size }
    }
    val visibleItems = remember(items, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            items
        } else {
            items.filter { item ->
                item.displayName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    item.tags.lowercase(Locale.ROOT).contains(normalizedQuery)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_local_catalog_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onAddFolder) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text(stringResource(R.string.settings_local_catalog_add))
                    }
                    TextButton(onClick = onRescanAll, enabled = folders.isNotEmpty()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(stringResource(R.string.settings_local_catalog_rescan_all))
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_local_catalog_search_hint)) },
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_local_catalog_folders_heading),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (folders.isEmpty()) {
                        item { Text(stringResource(R.string.settings_local_catalog_no_folders)) }
                    } else {
                        items(folders, key = LocalWallpaperFolderEntity::folderUri) { folder ->
                            LocalWallpaperFolderRow(
                                folder = folder,
                                onRescan = { onRescanFolder(folder.folderUri) },
                                onRemove = { onRemoveFolder(folder.folderUri) },
                                onRepair = { onRepairFolder(folder) },
                                onSetTarget = { onSetFolderTarget(folder.folderUri, it) },
                            )
                        }
                    }
                    item {
                        Text(
                            text = stringResource(
                                R.string.settings_local_catalog_items_heading,
                                visibleItems.size,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (visibleItems.isEmpty()) {
                        item { Text(stringResource(R.string.settings_local_catalog_no_items)) }
                    } else {
                        items(visibleItems, key = LocalWallpaperEntity::stableLocalMediaId) { item ->
                            LocalWallpaperItemRow(
                                item = item,
                                duplicateCount = duplicateCounts[item.contentHash] ?: 0,
                                editing = editingUri == item.documentUri,
                                editingTags = editingTags,
                                onBeginEdit = {
                                    editingUri = item.documentUri
                                    editingTags = item.tags
                                },
                                onTagsChange = { editingTags = it },
                                onSaveTags = {
                                    onUpdateTags(item.documentUri, editingTags)
                                    editingUri = null
                                },
                                onCancelEdit = { editingUri = null },
                                onRelink = { onRelinkItem(item) },
                                excluded = rotationExclusionIndex.contains(item.rotationIdentity()),
                                onToggleRotationExclusion = { onToggleRotationExclusion(item) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun LocalWallpaperFolderRow(
    folder: LocalWallpaperFolderEntity,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
    onRepair: () -> Unit,
    onSetTarget: (WallpaperTarget) -> Unit,
) {
    val target = WallpaperTarget.entries.firstOrNull { it.name == folder.target } ?: WallpaperTarget.BOTH
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(folder.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.settings_local_catalog_folder_summary,
                        folder.itemCount,
                        targetLabel(target),
                        scanStatusLabel(folder.scanStatus),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (folder.lastError.isNotBlank()) {
                    Text(
                        scanErrorLabel(folder.scanStatus),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onRescan) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_local_catalog_rescan))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.settings_local_catalog_remove))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onSetTarget(nextTarget(target)) }) {
                Text(stringResource(R.string.settings_local_catalog_target_button, targetLabel(target)))
            }
            if (folder.scanStatus == LocalWallpaperFolderScanStatus.PERMISSION_REVOKED) {
                TextButton(onClick = onRepair) {
                    Text(stringResource(R.string.settings_local_catalog_repair))
                }
            }
        }
    }
}

@Composable
private fun LocalWallpaperItemRow(
    item: LocalWallpaperEntity,
    duplicateCount: Int,
    editing: Boolean,
    editingTags: String,
    onBeginEdit: () -> Unit,
    onTagsChange: (String) -> Unit,
    onSaveTags: () -> Unit,
    onCancelEdit: () -> Unit,
    onRelink: () -> Unit,
    excluded: Boolean,
    onToggleRotationExclusion: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.settings_local_catalog_item_summary, item.mimeType, item.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.needsLocalMediaRelink()) {
            Text(
                text = localMediaStatusLabel(item.localMediaStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (item.localMediaReason.isNotBlank()) {
                Text(
                    text = item.localMediaReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (duplicateCount > 1) {
            Text(
                stringResource(R.string.settings_local_catalog_duplicate, duplicateCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (editing) {
            OutlinedTextField(
                value = editingTags,
                onValueChange = onTagsChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_local_catalog_tags_hint)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSaveTags) { Text(stringResource(R.string.settings_local_catalog_save_tags)) }
                TextButton(onClick = onCancelEdit) { Text(stringResource(R.string.common_cancel)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBeginEdit) {
                    Text(
                        if (item.tags.isBlank()) {
                            stringResource(R.string.settings_local_catalog_add_tags)
                        } else {
                            stringResource(R.string.settings_local_catalog_tags, item.tags)
                        },
                    )
                }
                TextButton(onClick = onToggleRotationExclusion) {
                    Icon(
                        if (excluded) Icons.Default.Restore else Icons.Default.PlaylistRemove,
                        contentDescription = null,
                    )
                    Text(
                        stringResource(
                            if (excluded) R.string.rotation_restore_action else R.string.rotation_exclude_action,
                        ),
                    )
                }
                if (item.needsLocalMediaRelink()) {
                    TextButton(onClick = onRelink) {
                        Icon(Icons.Default.FindReplace, contentDescription = null)
                        Text(stringResource(R.string.media_relink_action))
                    }
                }
            }
        }
    }
}

private fun nextTarget(target: WallpaperTarget): WallpaperTarget = when (target) {
    WallpaperTarget.BOTH -> WallpaperTarget.HOME
    WallpaperTarget.HOME -> WallpaperTarget.LOCK
    WallpaperTarget.LOCK -> WallpaperTarget.BOTH
}

@Composable
private fun targetLabel(target: WallpaperTarget): String = stringResource(
    when (target) {
        WallpaperTarget.HOME -> R.string.settings_local_catalog_target_home
        WallpaperTarget.LOCK -> R.string.settings_local_catalog_target_lock
        WallpaperTarget.BOTH -> R.string.settings_local_catalog_target_both
    },
)

@Composable
private fun scanStatusLabel(status: String): String = stringResource(
    when (status) {
        LocalWallpaperFolderScanStatus.NEVER_SCANNED -> R.string.settings_local_catalog_status_never
        LocalWallpaperFolderScanStatus.SCANNING -> R.string.settings_local_catalog_status_scanning
        LocalWallpaperFolderScanStatus.READY -> R.string.settings_local_catalog_status_ready
        LocalWallpaperFolderScanStatus.READY_LIMITED -> R.string.settings_local_catalog_status_limited
        LocalWallpaperFolderScanStatus.READY_PARTIAL -> R.string.settings_local_catalog_status_partial
        LocalWallpaperFolderScanStatus.PERMISSION_REVOKED -> R.string.settings_local_catalog_status_revoked
        LocalWallpaperFolderScanStatus.SCAN_FAILED -> R.string.settings_local_catalog_status_failed
        else -> R.string.settings_local_catalog_status_unknown
    },
)

@Composable
private fun scanErrorLabel(status: String): String = stringResource(
    if (status == LocalWallpaperFolderScanStatus.PERMISSION_REVOKED) {
        R.string.settings_local_catalog_permission_error
    } else {
        R.string.settings_local_catalog_scan_error
    },
)

@Composable
private fun localMediaStatusLabel(status: String): String = stringResource(
    when (status) {
        LocalMediaStatus.MISSING -> R.string.media_status_missing
        LocalMediaStatus.PERMISSION_REVOKED -> R.string.media_status_permission_revoked
        LocalMediaStatus.CORRUPT -> R.string.media_status_corrupt
        else -> R.string.media_status_available
    },
)
