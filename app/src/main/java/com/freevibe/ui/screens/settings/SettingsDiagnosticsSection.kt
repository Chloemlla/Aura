package com.freevibe.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freevibe.R
import com.freevibe.service.BackgroundWorkDiagnostics
import com.freevibe.service.CrashDiagnosticsSummary
import com.freevibe.service.ExternalAutomationDiagnostics
import com.freevibe.service.LiveWallpaperActivity
import com.freevibe.service.LiveWallpaperLivenessState
import com.freevibe.service.SourceMetrics
import kotlinx.coroutines.launch

@Composable
internal fun DiagnosticsSettingsSection(
    context: Context,
    viewModel: SettingsViewModel,
    diagnostics: List<SourceMetrics.SourceStats>,
    crashDiagnostics: CrashDiagnosticsSummary,
    backgroundWorkDiagnostics: BackgroundWorkDiagnostics,
    externalAutomationDiagnostics: ExternalAutomationDiagnostics,
    liveWallpaperLiveness: LiveWallpaperLivenessState?,
    onFeedback: (String) -> Unit,
    onRotationHealth: () -> Unit = {},
) {
    // Re-read every time this section is composed rather than once per ViewModel:
    // the wallpaper can be replaced from outside Aura while the app is alive, so a
    // check that only ran at construction would go stale exactly when it matters.
    LaunchedEffect(Unit) { viewModel.refreshLiveWallpaperLiveness() }

    val diagnosticsScope = rememberCoroutineScope()
    var showDiagnostics by remember { mutableStateOf(false) }
    var showBackgroundWorkDiagnostics by remember { mutableStateOf(false) }
    var showExternalAutomationDiagnostics by remember { mutableStateOf(false) }
    var showCrashDiagnostics by remember { mutableStateOf(false) }
    var crashDiagnosticsBusy by remember { mutableStateOf(false) }

    SettingsSection(
        sectionKey = SettingsSectionKeys.DIAGNOSTICS,
        title = stringResource(R.string.settings_diagnostics_section_title),
        description = stringResource(R.string.settings_diagnostics_section_description),
    ) {
        // Only shown once Aura has actually run a live wallpaper and definitely is
        // not running one now. An UNKNOWN reading says nothing, because nagging a
        // user whose wallpaper works is how the warning gets ignored when it is real.
        if (liveWallpaperLiveness?.shouldWarn == true) {
            SettingsItem(
                icon = Icons.Default.WarningAmber,
                title = stringResource(R.string.settings_diag_live_wallpaper_inactive_title),
                subtitle = when (liveWallpaperLiveness.result.activity) {
                    LiveWallpaperActivity.REPLACED_BY_OTHER_APP -> stringResource(
                        R.string.settings_diag_live_wallpaper_replaced_subtitle,
                        liveWallpaperLiveness.result.runningPackage.orEmpty(),
                    )
                    else -> stringResource(R.string.settings_diag_live_wallpaper_static_subtitle)
                },
                onClick = { viewModel.reapplyLiveWallpaper(context) },
            )
        }
        SettingsItem(
            icon = Icons.Default.Autorenew,
            title = stringResource(R.string.rotation_health_entry_title),
            subtitle = stringResource(R.string.rotation_health_entry_subtitle),
            onClick = onRotationHealth,
        )
        SettingsItem(
            icon = Icons.Default.BugReport,
            title = stringResource(R.string.settings_diag_crash_title),
            subtitle = crashDiagnosticsSubtitle(crashDiagnostics),
            onClick = {
                viewModel.refreshCrashDiagnostics()
                showCrashDiagnostics = true
            },
        )
        SettingsItem(
            icon = Icons.Default.Schedule,
            title = stringResource(R.string.settings_diag_background_title),
            subtitle = backgroundWorkDiagnosticsSubtitle(backgroundWorkDiagnostics),
            onClick = {
                viewModel.refreshBackgroundWorkDiagnostics()
                showBackgroundWorkDiagnostics = true
            },
        )
        SettingsItem(
            icon = Icons.Default.SettingsInputComponent,
            title = stringResource(R.string.settings_external_automation_title),
            subtitle = externalAutomationSubtitle(externalAutomationDiagnostics),
            onClick = {
                viewModel.refreshExternalAutomationDiagnostics()
                showExternalAutomationDiagnostics = true
            },
        )
        SettingsItem(
            icon = Icons.Default.MonitorHeart,
            title = stringResource(R.string.settings_diag_source_title),
            subtitle = if (diagnostics.isEmpty()) {
                stringResource(R.string.settings_diag_source_empty_subtitle)
            } else {
                stringResource(R.string.settings_diag_source_count_subtitle, diagnostics.size)
            },
            onClick = { showDiagnostics = true },
        )
    }

    if (showExternalAutomationDiagnostics) {
        val snapshot = externalAutomationDiagnostics
        AlertDialog(
            onDismissRequest = { showExternalAutomationDiagnostics = false },
            title = { Text(stringResource(R.string.settings_external_automation_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.settings_external_automation_dialog_body,
                            externalAutomationRateLimitLabel(snapshot.minIntervalMs),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExternalAutomationDiagnosticsSummary(snapshot)
                    Text(
                        stringResource(R.string.settings_external_automation_public_contract),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showExternalAutomationDiagnostics = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::refreshExternalAutomationDiagnostics) {
                    Text(stringResource(R.string.common_refresh))
                }
            },
        )
    }

    if (showBackgroundWorkDiagnostics) {
        val snapshot = backgroundWorkDiagnostics
        AlertDialog(
            onDismissRequest = { showBackgroundWorkDiagnostics = false },
            title = { Text(stringResource(R.string.settings_diag_background_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_diag_background_dialog_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BackgroundWorkDiagnosticsSummary(snapshot)
                    if (snapshot.rows.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_diag_background_no_rows),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        snapshot.rows.forEach { row ->
                            BackgroundWorkDiagnosticRow(row)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackgroundWorkDiagnostics = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::refreshBackgroundWorkDiagnostics) {
                    Text(stringResource(R.string.common_refresh))
                }
            },
        )
    }

    if (showDiagnostics) {
        val snapshots = diagnostics
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text(stringResource(R.string.settings_diag_source_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_diag_source_dialog_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (snapshots.isEmpty()) {
                        SourceDiagnosticsEmptyState()
                    } else {
                        SourceDiagnosticsSummary(snapshots)
                        snapshots.forEach { stat ->
                            SourceDiagnosticRow(
                                stat = stat,
                                onRetry = viewModel::resetSourceDiagnostics,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text(stringResource(R.string.common_close)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::resetDiagnostics) { Text(stringResource(R.string.common_reset)) }
            },
        )
    }

    if (showCrashDiagnostics) {
        AlertDialog(
            onDismissRequest = { if (!crashDiagnosticsBusy) showCrashDiagnostics = false },
            title = { Text(stringResource(R.string.settings_diag_crash_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        crashDiagnosticsSubtitle(crashDiagnostics),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.settings_diag_crash_dialog_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_diag_crash_dialog_no_send),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (crashDiagnosticsBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !crashDiagnosticsBusy,
                    onClick = {
                        diagnosticsScope.launch {
                            crashDiagnosticsBusy = true
                            try {
                                val bundle = viewModel.buildCrashDiagnosticsBundle()
                                copyCrashDiagnosticsBundle(
                                    context = context,
                                    bundle = bundle,
                                    onFeedback = onFeedback,
                                )
                                viewModel.refreshCrashDiagnostics()
                            } catch (_: Exception) {
                                onFeedback(context.getString(R.string.settings_feedback_diagnostics_failed))
                            } finally {
                                crashDiagnosticsBusy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_diag_crash_copy))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !crashDiagnosticsBusy,
                        onClick = { showCrashDiagnostics = false },
                    ) {
                        Text(stringResource(R.string.common_close))
                    }
                    TextButton(
                        enabled = !crashDiagnosticsBusy,
                        onClick = {
                            diagnosticsScope.launch {
                                crashDiagnosticsBusy = true
                                try {
                                    val bundle = viewModel.buildCrashDiagnosticsBundle()
                                    shareCrashDiagnosticsBundle(
                                        context = context,
                                        bundle = bundle,
                                        onFeedback = onFeedback,
                                    )
                                    viewModel.refreshCrashDiagnostics()
                                } catch (_: Exception) {
                                    onFeedback(context.getString(R.string.settings_feedback_diagnostics_failed))
                                } finally {
                                    crashDiagnosticsBusy = false
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_diag_crash_share))
                    }
                }
            },
        )
    }
}
