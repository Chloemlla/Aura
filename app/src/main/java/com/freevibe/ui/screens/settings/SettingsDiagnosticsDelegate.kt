package com.freevibe.ui.screens.settings

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import com.freevibe.R
import com.freevibe.data.local.WallpaperCacheManager
import com.freevibe.data.repository.AiWallpaperRepository
import com.freevibe.data.repository.GeneratedAssetAudit
import com.freevibe.service.BackgroundWorkDiagnostics
import com.freevibe.service.BackgroundWorkDiagnosticsReader
import com.freevibe.service.CrashDiagnosticsCollector
import com.freevibe.service.CrashDiagnosticsSummary
import com.freevibe.service.ExternalAutomationDiagnostics
import com.freevibe.service.ExternalAutomationGate
import com.freevibe.service.LibraryExporter
import com.freevibe.service.LibraryImportOutcome
import com.freevibe.service.LibraryImportSkipReason
import com.freevibe.service.LiveWallpaperLivenessMonitor
import com.freevibe.service.OfflineFavoritesManager
import com.freevibe.service.SourceMetrics
import com.freevibe.service.ThemePackRecipeManager
import com.freevibe.service.VideoWallpaperSelectionResult
import com.freevibe.service.VideoWallpaperService
import com.freevibe.service.VideoWallpaperStorage
import com.freevibe.service.WallpaperApplier
import com.freevibe.service.WallpaperHistoryManager
import com.freevibe.service.WeatherWallpaperService
import com.freevibe.service.ParallaxWallpaperService
import com.freevibe.service.YtDlpUpdateManager
import com.freevibe.service.YtDlpUpdateResult
import com.freevibe.service.YtDlpUpdateStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns storage, diagnostics, import/export, and system-entrypoint operations. */
internal class SettingsDiagnosticsDelegate(
    private val context: Context,
    private val historyManager: WallpaperHistoryManager,
    private val offlineFavorites: OfflineFavoritesManager,
    private val wallpaperCacheManager: WallpaperCacheManager,
    private val wallpaperApplier: WallpaperApplier,
    private val videoWallpaperStorage: VideoWallpaperStorage,
    private val sourceMetrics: SourceMetrics,
    private val crashDiagnosticsCollector: CrashDiagnosticsCollector,
    private val liveWallpaperLivenessMonitor: LiveWallpaperLivenessMonitor,
    private val backgroundWorkDiagnosticsReader: BackgroundWorkDiagnosticsReader,
    private val ytDlpUpdateManager: YtDlpUpdateManager,
    private val themePackRecipeManager: ThemePackRecipeManager,
    private val libraryExporter: LibraryExporter,
    private val aiWallpaperRepository: AiWallpaperRepository,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val sharing = SharingStarted.WhileSubscribed(5000)

    private val _parallaxGalleryResult = MutableStateFlow<ParallaxGalleryResult?>(null)
    val parallaxGalleryResult = _parallaxGalleryResult.asStateFlow()
    private val _videoWallpaperSelectionResult = MutableStateFlow<VideoWallpaperSelectionResult?>(null)
    val videoWallpaperSelectionResult = _videoWallpaperSelectionResult.asStateFlow()
    val wallpaperHistory = historyManager.getRecent(20).stateIn(scope, sharing, emptyList())
    private val _cacheUsage = MutableStateFlow(CacheUsageState())
    val cacheUsage = _cacheUsage.asStateFlow()
    private val _crashDiagnostics = MutableStateFlow(CrashDiagnosticsSummary())
    val crashDiagnostics = _crashDiagnostics.asStateFlow()
    private val _liveWallpaperLiveness = MutableStateFlow<com.freevibe.service.LiveWallpaperLivenessState?>(null)
    val liveWallpaperLiveness = _liveWallpaperLiveness.asStateFlow()
    private val _backgroundWorkDiagnostics = MutableStateFlow(BackgroundWorkDiagnostics())
    val backgroundWorkDiagnostics = _backgroundWorkDiagnostics.asStateFlow()
    private val _externalAutomationDiagnostics = MutableStateFlow(ExternalAutomationGate.readDiagnostics(context))
    val externalAutomationDiagnostics = _externalAutomationDiagnostics.asStateFlow()
    private val _ytDlpUpdate = MutableStateFlow(YtDlpUpdateUiState(snapshot = ytDlpUpdateManager.snapshot()))
    val ytDlpUpdate = _ytDlpUpdate.asStateFlow()
    private val _themePackTransfer = MutableStateFlow(ThemePackTransferState())
    val themePackTransfer = _themePackTransfer.asStateFlow()
    private val _generatedAssets = MutableStateFlow(GeneratedAssetAudit())
    val generatedAssets = _generatedAssets.asStateFlow()
    val diagnostics = sourceMetrics.version
        .map { sourceMetrics.snapshotAll() }
        .stateIn(scope, sharing, sourceMetrics.snapshotAll())

    init {
        refreshCacheUsage()
        refreshCrashDiagnostics()
        refreshBackgroundWorkDiagnostics()
        refreshExternalAutomationDiagnostics()
        refreshGeneratedAssetAudit()
    }

    fun clearParallaxGalleryResult() {
        _parallaxGalleryResult.value = null
    }

    fun clearVideoWallpaperSelectionResult() {
        _videoWallpaperSelectionResult.value = null
    }

    fun applyParallaxFromGallery(uri: Uri) = scope.launch {
        _parallaxGalleryResult.value = ParallaxGalleryResult.Preparing
        val result = wallpaperApplier.prepareParallaxFromUri(uri, "parallax_user_photo.jpg")
        _parallaxGalleryResult.value = result.fold(
            onSuccess = { ParallaxGalleryResult.Ready },
            onFailure = { ParallaxGalleryResult.Failure(it.message ?: "Could not prepare photo") },
        )
    }

    fun prepareVideoWallpaperFromUri(uri: Uri) = scope.launch {
        _videoWallpaperSelectionResult.value = VideoWallpaperSelectionResult.Preparing
        val result = videoWallpaperStorage.prepareFromUri(uri)
        _videoWallpaperSelectionResult.value = result.fold(
            onSuccess = { VideoWallpaperSelectionResult.Ready },
            onFailure = { VideoWallpaperSelectionResult.Failure(it.message ?: "Could not prepare video") },
        )
    }

    fun resetDiagnostics() = sourceMetrics.reset()
    fun resetSourceDiagnostics(source: String) = sourceMetrics.reset(source)

    fun refreshGeneratedAssetAudit() = scope.launch {
        _generatedAssets.value = runCatching { aiWallpaperRepository.auditGeneratedAssets() }
            .getOrDefault(GeneratedAssetAudit())
    }

    fun refreshCrashDiagnostics() = scope.launch {
        _crashDiagnostics.value = withContext(ioDispatcher) { crashDiagnosticsCollector.readSummary() }
    }

    fun refreshLiveWallpaperLiveness() = scope.launch {
        _liveWallpaperLiveness.value = withContext(ioDispatcher) { liveWallpaperLivenessMonitor.refresh() }
    }

    fun reapplyLiveWallpaper(activityContext: Context) {
        val engine = liveWallpaperLivenessMonitor.lastRunEngine() ?: return
        liveWallpaperLivenessMonitor.recordApplyRequested()
        val serviceClass = when (engine) {
            com.freevibe.service.LiveWallpaperReceiptStore.ENGINE_VIDEO -> VideoWallpaperService::class.java
            com.freevibe.service.LiveWallpaperReceiptStore.ENGINE_PARALLAX -> ParallaxWallpaperService::class.java
            else -> WeatherWallpaperService::class.java
        }
        com.freevibe.ui.launchLiveWallpaperPicker(
            context = activityContext,
            serviceComponent = ComponentName(activityContext, serviceClass),
            tag = "SettingsLivenessReapply",
        )
    }

    suspend fun buildCrashDiagnosticsBundle(): String = withContext(ioDispatcher) {
        crashDiagnosticsCollector.buildBundle()
    }

    fun refreshBackgroundWorkDiagnostics() = scope.launch {
        _backgroundWorkDiagnostics.value = withContext(ioDispatcher) { backgroundWorkDiagnosticsReader.read() }
    }

    fun setExternalAutomationEnabled(enabled: Boolean) = scope.launch {
        _externalAutomationDiagnostics.value = withContext(ioDispatcher) {
            ExternalAutomationGate.setEnabled(context, enabled)
            ExternalAutomationGate.readDiagnostics(context)
        }
    }

    fun refreshExternalAutomationDiagnostics() = scope.launch {
        _externalAutomationDiagnostics.value = withContext(ioDispatcher) {
            ExternalAutomationGate.readDiagnostics(context)
        }
    }

    fun updateYtDlp() {
        if (_ytDlpUpdate.value.isUpdating) return
        scope.launch {
            _ytDlpUpdate.update {
                it.copy(
                    snapshot = ytDlpUpdateManager.snapshot(),
                    isUpdating = true,
                    completedStatus = null,
                    error = null,
                )
            }
            val result = runCatching { ytDlpUpdateManager.updateStable() }
                .getOrElse { error ->
                    YtDlpUpdateResult(
                        status = YtDlpUpdateStatus.FAILED,
                        snapshot = ytDlpUpdateManager.snapshot().copy(
                            lastStatus = YtDlpUpdateStatus.FAILED,
                            lastError = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            _ytDlpUpdate.value = YtDlpUpdateUiState(
                snapshot = result.snapshot,
                isUpdating = false,
                completedStatus = result.status,
                error = result.snapshot.lastError,
            )
        }
    }

    fun clearYtDlpUpdateNotice() {
        _ytDlpUpdate.update { it.copy(completedStatus = null, error = null) }
    }

    fun exportThemePack(uri: Uri) {
        if (_themePackTransfer.value.inProgress) return
        scope.launch {
            _themePackTransfer.value = ThemePackTransferState(inProgress = true)
            val result = themePackRecipeManager.exportThemePack(uri)
            _themePackTransfer.value = result.fold(
                onSuccess = { report ->
                    ThemePackTransferState(
                        message = "Theme pack exported: ${report.exportedItemCount} recipes, ${report.embeddedAssetCount} local assets",
                    )
                },
                onFailure = { error ->
                    ThemePackTransferState(error = "Theme pack export failed: ${error.message ?: "try again"}")
                },
            )
        }
    }

    fun importThemePack(uri: Uri) {
        if (_themePackTransfer.value.inProgress) return
        scope.launch {
            _themePackTransfer.value = ThemePackTransferState(inProgress = true)
            val result = themePackRecipeManager.importThemePack(uri)
            _themePackTransfer.value = result.fold(
                onSuccess = { report ->
                    ThemePackTransferState(
                        message = "Theme pack imported: ${report.importedItemCount} settings restored",
                        instructions = report.instructions,
                    )
                },
                onFailure = { error ->
                    ThemePackTransferState(error = "Theme pack import failed: ${error.message ?: "try again"}")
                },
            )
        }
    }

    fun clearThemePackTransferNotice() {
        _themePackTransfer.update { it.copy(message = null, error = null, instructions = emptyList()) }
    }

    fun exportLibrary(uri: Uri) {
        if (_themePackTransfer.value.inProgress) return
        scope.launch {
            _themePackTransfer.value = ThemePackTransferState(inProgress = true)
            val result = libraryExporter.exportLibrary(uri)
            _themePackTransfer.value = result.fold(
                onSuccess = { count ->
                    ThemePackTransferState(
                        message = context.resources.getQuantityString(R.plurals.settings_library_export_done, count, count),
                    )
                },
                onFailure = { error ->
                    ThemePackTransferState(
                        error = context.getString(
                            R.string.settings_library_export_failed,
                            error.message ?: context.getString(R.string.common_retry_later),
                        ),
                    )
                },
            )
        }
    }

    fun importLibrary(uri: Uri) {
        if (_themePackTransfer.value.inProgress) return
        scope.launch {
            _themePackTransfer.value = ThemePackTransferState(inProgress = true)
            val result = libraryExporter.importLibrary(uri)
            _themePackTransfer.value = result.fold(
                onSuccess = { outcome ->
                    ThemePackTransferState(
                        message = context.resources.getQuantityString(
                            R.plurals.settings_library_import_done,
                            outcome.written,
                            outcome.written,
                        ),
                        instructions = libraryImportReport(outcome),
                    )
                },
                onFailure = { error ->
                    ThemePackTransferState(
                        error = context.getString(
                            R.string.settings_library_import_failed,
                            error.message ?: context.getString(R.string.common_retry_later),
                        ),
                    )
                },
            )
        }
    }

    fun clearWallpaperHistory() = scope.launch { historyManager.clearAll() }

    fun clearCache() = scope.launch {
        withContext(ioDispatcher) {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name != "trimmed") file.deleteRecursively()
            }
            offlineFavorites.clearAll()
            wallpaperCacheManager.clearAll()
        }
        refreshCacheUsage()
    }

    private fun refreshCacheUsage() = scope.launch {
        _cacheUsage.value = withContext(ioDispatcher) {
            val cacheBytes = context.cacheDir
                .takeIf { it.exists() }
                ?.walkTopDown()
                ?.filter { it.isFile && it.parentFile?.name != "trimmed" }
                ?.sumOf { it.length() }
                ?: 0L
            CacheUsageState(
                fileUsageLabel = formatBytes(cacheBytes + offlineFavorites.getCacheSize()),
                hasWallpaperMetadataCache = wallpaperCacheManager.countEntries() > 0,
            )
        }
    }

    private fun libraryImportReport(outcome: LibraryImportOutcome): List<String> {
        if (outcome.skipped.isEmpty()) return emptyList()
        return outcome.skipped
            .groupBy { it.reason }
            .toSortedMap(compareBy { it.ordinal })
            .map { (reason, rows) ->
                val examples = rows.map { it.label }.distinct().sorted().take(3).joinToString(", ")
                val suffix = if (rows.size > 3) context.getString(R.string.settings_library_import_more) else ""
                context.getString(
                    when (reason) {
                        LibraryImportSkipReason.INVALID -> R.string.settings_library_import_skipped_invalid
                        LibraryImportSkipReason.NON_PORTABLE -> R.string.settings_library_import_skipped_non_portable
                        LibraryImportSkipReason.DUPLICATE -> R.string.settings_library_import_skipped_duplicate
                        LibraryImportSkipReason.OVER_LIMIT -> R.string.settings_library_import_skipped_over_limit
                        LibraryImportSkipReason.DROPPED_BY_MIGRATION -> R.string.settings_library_import_skipped_migration
                    },
                    rows.size,
                    examples + suffix,
                )
            }
    }

    private fun formatBytes(bytes: Long): String {
        val root = java.util.Locale.ROOT
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(root, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(root, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(root, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
