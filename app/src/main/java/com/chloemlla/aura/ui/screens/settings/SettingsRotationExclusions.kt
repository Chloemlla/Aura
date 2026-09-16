package com.chloemlla.aura.ui.screens.settings

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chloemlla.aura.R
import com.chloemlla.aura.data.model.RotationExclusionIndex
import com.chloemlla.aura.data.model.rotationIdentity
import com.chloemlla.aura.ui.rotation.RotationExclusionsViewModel
import kotlinx.coroutines.launch

internal data class RotationExclusionSettingsControls(
    val exclusionCount: Int,
    val openManager: () -> Unit,
)

@Composable
internal fun RotationExclusionSettingsHost(
    showLocalCatalog: Boolean,
    settingsState: SettingsScreenState,
    settingsViewModel: SettingsViewModel,
    onDismissLocalCatalog: () -> Unit,
    onAddLocalFolder: () -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: RotationExclusionsViewModel = hiltViewModel(),
): RotationExclusionSettingsControls {
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()
    val index = remember(exclusions) { RotationExclusionIndex(exclusions) }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var showManager by remember { mutableStateOf(false) }

    LocalWallpaperCatalogDialogHost(
        show = showLocalCatalog,
        state = settingsState,
        viewModel = settingsViewModel,
        onDismiss = onDismissLocalCatalog,
        onAddFolder = onAddLocalFolder,
        snackbarHostState = snackbarHostState,
        rotationExclusions = exclusions,
        onToggleRotationExclusion = { item ->
            scope.launch {
                val identity = item.rotationIdentity()
                val existing = index.find(identity)
                if (existing != null) {
                    viewModel.restoreNow(existing.stableId)
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.rotation_restored_message, item.displayName),
                    )
                } else {
                    val exclusion = viewModel.exclude(identity)
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.rotation_excluded_message, item.displayName),
                        actionLabel = resources.getString(R.string.common_undo),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreNow(exclusion.stableId)
                    }
                }
            }
        },
    )

    if (showManager) {
        RotationExclusionsManagerDialog(
            exclusions = exclusions,
            onRestore = viewModel::restore,
            onRestoreAll = viewModel::restoreAll,
            onDismiss = { showManager = false },
        )
    }

    return RotationExclusionSettingsControls(
        exclusionCount = exclusions.size,
        openManager = { showManager = true },
    )
}
