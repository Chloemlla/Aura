package com.chloemlla.aura.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.chloemlla.aura.R
import com.chloemlla.aura.data.local.validateRedditSubredditList

@Composable
internal fun RedditSubredditListEditor(
    title: String,
    configuredSubreddits: String,
    onSave: (String) -> Unit,
) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    val selectedCount = validateRedditSubredditList(configuredSubreddits).subreddits.size

    SettingsItem(
        icon = Icons.Default.Forum,
        title = title,
        subtitle = stringResource(R.string.settings_reddit_subreddits_summary, selectedCount),
        onClick = { showEditor = true },
    )

    if (showEditor) {
        RedditSubredditListDialog(
            title = title,
            configuredSubreddits = configuredSubreddits,
            onSave = {
                onSave(it)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun RedditSubredditListDialog(
    title: String,
    configuredSubreddits: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(configuredSubreddits) { mutableStateOf(configuredSubreddits) }
    val validation = validateRedditSubredditList(draft)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_reddit_subreddits_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(1024) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_reddit_subreddits_input_label)) },
                    isError = !validation.isValid,
                    supportingText = if (!validation.isValid) {
                        { Text(stringResource(R.string.settings_reddit_subreddits_error)) }
                    } else {
                        null
                    },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = validation.isValid,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
