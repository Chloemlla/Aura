package com.freevibe.ui.screens.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freevibe.R
import com.freevibe.data.model.WALLPAPER_SOURCE_LOCAL_FOLDER
import com.freevibe.service.AuraPickVisualMedia
import com.freevibe.service.DailyWallpaperWorker
import com.freevibe.service.ParallaxWallpaperService
import com.freevibe.service.VideoWallpaperSelectionResult
import com.freevibe.service.VideoWallpaperService
import com.freevibe.service.WeatherUpdateWorker
import com.freevibe.service.videoWallpaperMimeTypes
import com.freevibe.ui.LiveWallpaperLaunchMode
import com.freevibe.ui.components.AuraSnackbarHost
import com.freevibe.ui.launchLiveWallpaperPicker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDownloadsClick: () -> Unit = {},
    onLicensesClick: () -> Unit = {},
    onCategoriesClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onCollectionsClick: () -> Unit = {},
    onCreatorProfileClick: () -> Unit = {},
    onCommunityReportsClick: () -> Unit = {},
    onGeneratedWallpapersClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val feedbackScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    fun showSettingsFeedback(message: String) {
        feedbackScope.launch { snackbarHostState.showSnackbar(message) }
    }
    val settingsState = rememberSettingsScreenState(viewModel, context)
    var dailyWallpaperEnabled by remember {
        mutableStateOf(
            context.getSharedPreferences("freevibe_weather_wp", Context.MODE_PRIVATE)
                .getBoolean("daily_wallpaper_enabled", false),
        )
    }
    fun setDailyWallpaperEnabled(enabled: Boolean) {
        dailyWallpaperEnabled = enabled
        context.getSharedPreferences("freevibe_weather_wp", Context.MODE_PRIVATE)
            .edit().putBoolean("daily_wallpaper_enabled", enabled).apply()
        if (enabled) DailyWallpaperWorker.schedule(context) else DailyWallpaperWorker.cancel(context)
    }

    fun enableWeatherEffects() {
        viewModel.setWeatherEffects(true)
        WeatherUpdateWorker.schedule(context)
    }

    fun disableWeatherEffects() {
        viewModel.setWeatherEffects(false)
        WeatherUpdateWorker.cancel(context)
        WeatherUpdateWorker.clearStoredWeatherState(context)
    }

    fun openAppSettings(): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null)),
        )
        true
    } catch (_: Exception) {
        false
    }

    fun openNotificationSettings(): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            openAppSettings()
        } catch (_: Exception) {
            false
        }
    }

    var pendingLocalFolderSource by remember { mutableStateOf<String?>(null) }
    val localFolderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val target = pendingLocalFolderSource
        pendingLocalFolderSource = null
        if (uri != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            viewModel.setLocalWallpaperFolderUri(uri.toString())
            when (target) {
                "auto" -> viewModel.setAutoWpSource(WALLPAPER_SOURCE_LOCAL_FOLDER)
                "scheduler" -> viewModel.setSchedulerSource(WALLPAPER_SOURCE_LOCAL_FOLDER)
            }
            showSettingsFeedback(
                if (persisted) {
                    context.getString(R.string.settings_feedback_local_folder_saved)
                } else {
                    context.getString(R.string.settings_feedback_local_folder_no_persist)
                },
            )
        }
    }

    fun chooseLocalWallpaperFolder(target: String? = null) {
        pendingLocalFolderSource = target
        localFolderPickerLauncher.launch(null)
    }
    var enableAutoBackupAfterFolder by remember { mutableStateOf(false) }
    val backupFolderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val shouldEnableAfterFolder = enableAutoBackupAfterFolder
        enableAutoBackupAfterFolder = false
        if (uri != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            viewModel.setAutoBackupFolderUri(uri.toString())
            if (persisted && shouldEnableAfterFolder) {
                viewModel.setAutoBackupEnabled(true)
                showSettingsFeedback(context.getString(R.string.settings_feedback_backup_folder_on))
            } else if (persisted) {
                showSettingsFeedback(context.getString(R.string.settings_feedback_backup_folder_saved))
            } else {
                showSettingsFeedback(context.getString(R.string.settings_feedback_backup_folder_no_persist))
            }
        }
    }

    fun chooseAutoBackupFolder(enableAfterSelection: Boolean = false) {
        enableAutoBackupAfterFolder = enableAfterSelection
        backupFolderPickerLauncher.launch(null)
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.prepareVideoWallpaperFromUri(it) }
    }
    val parallaxGalleryLauncher = rememberLauncherForActivityResult(
        AuraPickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let { viewModel.applyParallaxFromGallery(it) }
    }

    LaunchedEffect(settingsState.parallaxGalleryResult) {
        when (val result = settingsState.parallaxGalleryResult) {
            ParallaxGalleryResult.Ready -> {
                when (
                    launchLiveWallpaperPicker(
                        context = context,
                        serviceComponent = ComponentName(context, ParallaxWallpaperService::class.java),
                        tag = "SettingsParallaxGallery",
                    )
                ) {
                    LiveWallpaperLaunchMode.DIRECT -> showSettingsFeedback(context.getString(R.string.settings_feedback_parallax_direct))
                    LiveWallpaperLaunchMode.CHOOSER -> showSettingsFeedback(context.getString(R.string.settings_feedback_parallax_chooser))
                    null -> showSettingsFeedback(context.getString(R.string.settings_feedback_parallax_manual))
                }
                viewModel.clearParallaxGalleryResult()
            }
            is ParallaxGalleryResult.Failure -> {
                showSettingsFeedback(context.getString(R.string.settings_feedback_parallax_failed, result.message))
                viewModel.clearParallaxGalleryResult()
            }
            else -> Unit
        }
    }

    LaunchedEffect(settingsState.videoWallpaperSelectionResult) {
        when (val result = settingsState.videoWallpaperSelectionResult) {
            VideoWallpaperSelectionResult.Ready -> {
                when (
                    launchLiveWallpaperPicker(
                        context = context,
                        serviceComponent = ComponentName(context, VideoWallpaperService::class.java),
                        tag = "SettingsVideoWallpaper",
                    )
                ) {
                    LiveWallpaperLaunchMode.DIRECT -> showSettingsFeedback(context.getString(R.string.settings_feedback_video_direct))
                    LiveWallpaperLaunchMode.CHOOSER -> showSettingsFeedback(context.getString(R.string.settings_feedback_video_chooser))
                    null -> showSettingsFeedback(context.getString(R.string.settings_feedback_video_manual))
                }
                viewModel.clearVideoWallpaperSelectionResult()
            }
            is VideoWallpaperSelectionResult.Failure -> {
                showSettingsFeedback(result.message)
                viewModel.clearVideoWallpaperSelectionResult()
            }
            else -> Unit
        }
    }

    var settingsPermissionPrompt by remember { mutableStateOf<SettingsPermissionPrompt?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            setDailyWallpaperEnabled(true)
        } else {
            setDailyWallpaperEnabled(false)
            settingsPermissionPrompt = SettingsPermissionPrompt.DAILY_NOTIFICATION_RECOVERY
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableWeatherEffects()
        } else {
            disableWeatherEffects()
            settingsPermissionPrompt = SettingsPermissionPrompt.WEATHER_LOCATION_RECOVERY
        }
    }

    settingsPermissionPrompt?.let { prompt ->
        SettingsPermissionPromptDialog(
            prompt = prompt,
            onDismiss = { settingsPermissionPrompt = null },
            onLaunchNotificationPermission = {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onLaunchLocationPermission = {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
            onEnableDailyWallpaper = { setDailyWallpaperEnabled(true) },
            onOpenNotificationSettings = ::openNotificationSettings,
            onOpenAppSettings = ::openAppSettings,
            onFeedback = ::showSettingsFeedback,
        )
    }

    with(settingsState) {
    Scaffold(
        snackbarHost = { AuraSnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
                        ),
                    ),
                )
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopAppBar(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )

            SettingsOverviewCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                selectedStyleCount = selectedStyleCount,
                schedulerEnabled = schedulerEnabled,
                schedulerInterval = schedulerInterval,
                weatherEffects = weatherEffects,
                adaptiveTint = adaptiveTint,
                autoPreview = autoPreview,
                videoFpsLimit = videoFpsLimit,
                cacheUsage = cacheUsage,
                configuredApiKeys = configuredApiKeys,
            )

            WallpaperRotationSettingsSection(
                context = context,
                viewModel = viewModel,
                autoWpEnabled = autoWpEnabled,
                autoWpInterval = autoWpInterval,
                autoWpSource = autoWpSource,
                localWallpaperFolderUri = localWallpaperFolderUri,
                localFolderPermissionActive = localFolderPermissionActive,
                autoWpRequiresCharging = autoWpRequiresCharging,
                autoWpRequiresWiFi = autoWpRequiresWiFi,
                autoWpRequiresIdle = autoWpRequiresIdle,
                autoWallpaperDarkenPercent = autoWallpaperDarkenPercent,
                schedulerEnabled = schedulerEnabled,
                rotateOnUnlock = rotateOnUnlock,
                rotateOnScreenOff = rotateOnScreenOff,
                avoidRecentRepeats = avoidRecentRepeats,
                wallpaperPackEnabled = wallpaperPackEnabled,
                wallpaperPackJson = wallpaperPackJson,
                externalAutomationDiagnostics = externalAutomationDiagnostics,
                gridColumns = gridColumns,
                preferredRes = preferredRes,
                userStyles = userStyles,
                wallpaperStyleLearningSignalCount = wallpaperStyleLearningSignalCount,
                bingProviderEnabled = bingProviderEnabled,
                wallhavenProviderEnabled = wallhavenProviderEnabled,
                pixabayProviderEnabled = pixabayProviderEnabled,
                wallpaperHistoryCount = wallpaperHistory.size,
                onChooseLocalWallpaperFolder = ::chooseLocalWallpaperFolder,
                onPickVideoWallpaper = { videoPickerLauncher.launch(videoWallpaperMimeTypes()) },
                onPickParallaxImage = {
                    parallaxGalleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onCategoriesClick = onCategoriesClick,
                onCollectionsClick = onCollectionsClick,
                onHistoryClick = onHistoryClick,
                onFeedback = ::showSettingsFeedback,
            )

            SchedulerSettingsSection(
                context = context,
                viewModel = viewModel,
                schedulerEnabled = schedulerEnabled,
                schedulerInterval = schedulerInterval,
                schedulerSource = schedulerSource,
                schedulerHome = schedulerHome,
                schedulerLock = schedulerLock,
                schedulerShuffle = schedulerShuffle,
                localWallpaperFolderUri = localWallpaperFolderUri,
                localFolderPermissionActive = localFolderPermissionActive,
                wallhavenProviderEnabled = wallhavenProviderEnabled,
                pixabayProviderEnabled = pixabayProviderEnabled,
                bingProviderEnabled = bingProviderEnabled,
                onChooseLocalWallpaperFolder = ::chooseLocalWallpaperFolder,
            )

            BackupSettingsSection(
                context = context,
                viewModel = viewModel,
                autoBackupEnabled = autoBackupEnabled,
                autoBackupFolderUri = autoBackupFolderUri,
                autoBackupFolderPermissionActive = autoBackupFolderPermissionActive,
                autoBackupIntervalHours = autoBackupIntervalHours,
                autoBackupKeepCount = autoBackupKeepCount,
                themePackTransfer = themePackTransfer,
                onChooseAutoBackupFolder = ::chooseAutoBackupFolder,
                onFeedback = ::showSettingsFeedback,
            )

            SmartLiveWallpaperSettingsSection(
                context = context,
                viewModel = viewModel,
                dailyWallpaperEnabled = dailyWallpaperEnabled,
                adaptiveTint = adaptiveTint,
                adaptiveTintIntensity = adaptiveTintIntensity,
                weatherEffects = weatherEffects,
                darkModeSwitch = darkModeSwitch,
                darkModeWallpaperId = darkModeWallpaperId,
                lightModeWallpaperId = lightModeWallpaperId,
                liveWallpaperShaderPreset = liveWallpaperShaderPreset,
                wallpaperHistory = wallpaperHistory,
                reduceAnimations = reduceAnimations,
                liveWallpaperDimEnabled = liveWallpaperDimEnabled,
                onSetDailyWallpaperEnabled = ::setDailyWallpaperEnabled,
                onEnableWeatherEffects = ::enableWeatherEffects,
                onDisableWeatherEffects = ::disableWeatherEffects,
                onPermissionPrompt = { settingsPermissionPrompt = it },
            )

            SoundSettingsSection(
                viewModel = viewModel,
                autoPreview = autoPreview,
                previewVolume = previewVolume,
                ytRingtonesQuery = ytRingtonesQuery,
                ytNotificationsQuery = ytNotificationsQuery,
                ytAlarmsQuery = ytAlarmsQuery,
                ytBlockedWords = ytBlockedWords,
                youtubeProviderEnabled = youtubeProviderEnabled,
                ringtoneShuffleEnabled = ringtoneShuffleEnabled,
                ringtoneShuffleIntervalHours = ringtoneShuffleIntervalHours,
                alarmShuffleEnabled = alarmShuffleEnabled,
                soundProfilesEnabled = soundProfilesEnabled,
                soundProfilesJson = soundProfilesJson,
                ytDlpUpdate = ytDlpUpdate,
                onLicensesClick = onLicensesClick,
                onFeedback = ::showSettingsFeedback,
            )

            VideoSettingsSection(
                viewModel = viewModel,
                videoFpsLimit = videoFpsLimit,
                videoFpsOverlayEnabled = videoFpsOverlayEnabled,
                videoAutoBatterySaver = videoAutoBatterySaver,
                videoBatteryDashboard = videoBatteryDashboard,
            )

            ServicesCommunitySettingsSection(
                context = context,
                viewModel = viewModel,
                communityProviderEnabled = communityProviderEnabled,
                communityGuidelinesAccepted = communityGuidelinesAccepted,
                communityGuidelinesAcceptedVersion = communityGuidelinesAcceptedVersion,
                communityIdentitySummary = communityIdentitySummary,
                communityIdentityCleanup = communityIdentityCleanup,
                blockedCommunityCreators = blockedCommunityCreators,
                communityBlockAction = communityBlockAction,
                wallhavenApiKey = wallhavenApiKey,
                pexelsApiKey = pexelsApiKey,
                pixabayApiKey = pixabayApiKey,
                freesoundApiKey = freesoundApiKey,
                stabilityAiKey = stabilityAiKey,
                providerCredentialStorageUnavailable = providerCredentialStorageUnavailable,
                generatedContentProviderEnabled = generatedContentProviderEnabled,
                generatedContentDisclosureAccepted = generatedContentDisclosureAccepted,
                wallhavenProviderEnabled = wallhavenProviderEnabled,
                pexelsProviderEnabled = pexelsProviderEnabled,
                pixabayProviderEnabled = pixabayProviderEnabled,
                showSketchyContent = showSketchyContent,
                showNsfwContent = showNsfwContent,
                onCreatorProfileClick = onCreatorProfileClick,
                onCommunityReportsClick = onCommunityReportsClick,
                onGeneratedWallpapersClick = onGeneratedWallpapersClick,
                onFeedback = ::showSettingsFeedback,
            )

            StorageSettingsSection(
                viewModel = viewModel,
                cacheUsage = cacheUsage,
                onDownloadsClick = onDownloadsClick,
            )

            DiagnosticsSettingsSection(
                context = context,
                viewModel = viewModel,
                diagnostics = diagnostics,
                crashDiagnostics = crashDiagnostics,
                backgroundWorkDiagnostics = backgroundWorkDiagnostics,
                externalAutomationDiagnostics = externalAutomationDiagnostics,
                onFeedback = ::showSettingsFeedback,
            )

            PermissionsSettingsSection(context)
            AboutSettingsSection(
                context = context,
                onLicensesClick = onLicensesClick,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
    }
}
