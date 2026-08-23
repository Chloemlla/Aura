package com.chloemlla.aura.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.chloemlla.aura.R
import com.chloemlla.aura.data.model.WALLPAPER_SOURCE_LOCAL_FOLDER

@Composable
internal fun rememberLocalWallpaperFolderPicker(
    context: Context,
    resources: Resources,
    viewModel: SettingsViewModel,
    onFeedback: (String) -> Unit,
): (String?) -> Unit {
    var pendingTarget by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val target = pendingTarget
        pendingTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        viewModel.addLocalWallpaperFolder(uri.toString(), makePrimary = target != "catalog")
        when (target) {
            "auto" -> viewModel.setAutoWpSource(WALLPAPER_SOURCE_LOCAL_FOLDER)
            "scheduler" -> viewModel.setSchedulerSource(WALLPAPER_SOURCE_LOCAL_FOLDER)
            "scheduler_day" -> viewModel.setSchedulerSource(SchedulerSourceTarget.DAY, WALLPAPER_SOURCE_LOCAL_FOLDER)
            "scheduler_night" -> viewModel.setSchedulerSource(SchedulerSourceTarget.NIGHT, WALLPAPER_SOURCE_LOCAL_FOLDER)
        }
        onFeedback(
            resources.getString(
                if (persisted) R.string.settings_feedback_local_folder_saved
                else R.string.settings_feedback_local_folder_no_persist,
            ),
        )
    }
    return { target ->
        pendingTarget = target
        launcher.launch(null)
    }
}
