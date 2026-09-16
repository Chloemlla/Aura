package com.chloemlla.aura.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chloemlla.aura.R
import com.chloemlla.aura.data.model.ROTATION_MEDIA_VIDEO
import com.chloemlla.aura.data.model.RotationExclusionEntity
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RotationExclusionsManagerDialog(
    exclusions: List<RotationExclusionEntity>,
    onRestore: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_rotation_exclusions_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_rotation_exclusions_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (exclusions.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_rotation_exclusions_empty),
                        modifier = Modifier.padding(vertical = 18.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    ) {
                        items(exclusions, key = RotationExclusionEntity::stableId) { exclusion ->
                            RotationExclusionRow(exclusion, onRestore)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (exclusions.isNotEmpty()) {
                TextButton(onClick = onRestoreAll) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Text(stringResource(R.string.settings_rotation_exclusions_restore_all))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun RotationExclusionRow(
    exclusion: RotationExclusionEntity,
    onRestore: (String) -> Unit,
) {
    val excludedDate = remember(exclusion.excludedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(exclusion.excludedAt))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (exclusion.mediaType == ROTATION_MEDIA_VIDEO) Icons.Default.VideoFile else Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(
                exclusion.title.ifBlank { exclusion.contentId },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_rotation_exclusions_item_summary, exclusion.source, excludedDate),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onRestore(exclusion.stableId) }) {
            Text(stringResource(R.string.settings_rotation_exclusions_restore))
        }
    }
}
