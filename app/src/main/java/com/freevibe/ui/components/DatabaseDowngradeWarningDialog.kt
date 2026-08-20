package com.freevibe.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.freevibe.R
import com.freevibe.data.local.DatabaseDowngradeReceipt

/**
 * Tells the user their library was reset by installing an older Aura.
 *
 * Room cannot open a database written by a newer build, so an older APK either
 * crashes on every launch or resets and says nothing. Neither is acceptable, and
 * the second is worse than the first because the user finds out by noticing
 * their favorites are gone.
 *
 * This is raised at the app root rather than inside Settings: the reset already
 * happened, and a warning nobody navigates to is not a warning. Whether the
 * previous database was copied aside changes what the user can actually do, so
 * the two cases say different things instead of sharing a vague one.
 */
@Composable
fun DatabaseDowngradeWarningDialog(
    receipt: DatabaseDowngradeReceipt,
    onAcknowledge: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(stringResource(R.string.database_downgrade_title)) },
        text = {
            Text(
                if (receipt.dataWasPreserved) {
                    stringResource(
                        R.string.database_downgrade_preserved_body,
                        receipt.fromVersion,
                        receipt.toVersion,
                    )
                } else {
                    stringResource(
                        R.string.database_downgrade_lost_body,
                        receipt.fromVersion,
                        receipt.toVersion,
                    )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenBackup) {
                Text(stringResource(R.string.database_downgrade_open_backup))
            }
        },
        dismissButton = {
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.database_downgrade_dismiss))
            }
        },
    )
}
