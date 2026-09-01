package com.chloemlla.aura.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chloemlla.aura.data.local.*
import com.chloemlla.aura.data.repository.*
import com.chloemlla.aura.di.IoDispatcher
import com.chloemlla.aura.service.*
import com.chloemlla.aura.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

data class CacheUsageState(
    val fileUsageLabel: String = "",
    val hasWallpaperMetadataCache: Boolean = false,
)

enum class SchedulerSourceTarget { DEFAULT, DAY, NIGHT }

data class CommunityBlockActionState(
    val unblockingUserId: String? = null,
    val message: String? = null,
    val error: String? = null,
)

data class CommunityIdentityCleanupState(
    val clearing: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

data class YtDlpUpdateUiState(
    val snapshot: YtDlpUpdateSnapshot = YtDlpUpdateSnapshot(),
    val isUpdating: Boolean = false,
    val completedStatus: YtDlpUpdateStatus? = null,
    val error: String? = null,
)

data class ThemePackTransferState(
    val inProgress: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val instructions: List<String> = emptyList(),
)

sealed interface ParallaxGalleryResult {
    data object Preparing : ParallaxGalleryResult
    data object Ready : ParallaxGalleryResult
    data class Failure(val message: String) : ParallaxGalleryResult
}

/** Thin UI facade. Feature delegates own state, jobs, and the side effects behind it. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    // Held as a property, not just a constructor argument, because setAppLocale
    // needs it after construction.
    @ApplicationContext private val context: android.content.Context,
    prefs: PreferencesManager,
    historyManager: WallpaperHistoryManager,
    offlineFavorites: OfflineFavoritesManager,
    wallpaperCacheManager: WallpaperCacheManager,
    collectionRepo: CollectionRepository,
    wallpaperApplier: WallpaperApplier,
    localWallpaperCatalog: LocalWallpaperCatalog,
    videoWallpaperStorage: VideoWallpaperStorage,
    sourceMetrics: SourceMetrics,
    crashDiagnosticsCollector: CrashDiagnosticsCollector,
    liveWallpaperLivenessMonitor: LiveWallpaperLivenessMonitor,
    backgroundWorkDiagnosticsReader: BackgroundWorkDiagnosticsReader,
    voteRepo: VoteRepository,
    communityBlockRepo: CommunityBlockRepository,
    communityIdentityProvider: CommunityIdentityProvider,
    ytDlpUpdateManager: YtDlpUpdateManager,
    themePackRecipeManager: ThemePackRecipeManager,
    libraryExporter: LibraryExporter,
    aiWallpaperRepository: AiWallpaperRepository,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val rotation = SettingsRotationDelegate(context, prefs, collectionRepo, localWallpaperCatalog, viewModelScope)
    private val media = SettingsMediaDelegate(context, prefs, viewModelScope)
    private val community = SettingsCommunityDelegate(
        context = context,
        prefs = prefs,
        voteRepo = voteRepo,
        communityBlockRepo = communityBlockRepo,
        communityIdentityProvider = communityIdentityProvider,
        ioDispatcher = ioDispatcher,
        scope = viewModelScope,
    )
    private val diagnosticsDelegate = SettingsDiagnosticsDelegate(
        context = context,
        historyManager = historyManager,
        offlineFavorites = offlineFavorites,
        wallpaperCacheManager = wallpaperCacheManager,
        wallpaperApplier = wallpaperApplier,
        videoWallpaperStorage = videoWallpaperStorage,
        sourceMetrics = sourceMetrics,
        crashDiagnosticsCollector = crashDiagnosticsCollector,
        liveWallpaperLivenessMonitor = liveWallpaperLivenessMonitor,
        backgroundWorkDiagnosticsReader = backgroundWorkDiagnosticsReader,
        ytDlpUpdateManager = ytDlpUpdateManager,
        themePackRecipeManager = themePackRecipeManager,
        libraryExporter = libraryExporter,
        aiWallpaperRepository = aiWallpaperRepository,
        ioDispatcher = ioDispatcher,
        scope = viewModelScope,
    )

    val parallaxGalleryResult get() = diagnosticsDelegate.parallaxGalleryResult
    val videoWallpaperSelectionResult get() = diagnosticsDelegate.videoWallpaperSelectionResult

    // App language (LocaleHelper-backed). Callers observe localeChanged and
    // recreate the Activity so attachBaseContext re-wraps with the new locale.
    // Kept on the facade rather than in a delegate: it owns no preferences and no
    // jobs, only the process-wide app locale.
    private val _localeOptions = MutableStateFlow(LocaleHelper.getSupportedLanguages())
    val localeOptions: StateFlow<List<LocaleHelper.LanguageOption>> = _localeOptions.asStateFlow()
    private val _currentLocaleTag = MutableStateFlow(LocaleHelper.getAppLocaleTag(context))
    val currentLocaleTag: StateFlow<String> = _currentLocaleTag.asStateFlow()
    private val _localeChanged = Channel<Unit>(Channel.BUFFERED)
    val localeChanged: Flow<Unit> = _localeChanged.receiveAsFlow()

    fun setAppLocale(localeTag: String) {
        if (localeTag == _currentLocaleTag.value) return
        LocaleHelper.setAppLocale(context, localeTag)
        _currentLocaleTag.value = localeTag
        _localeChanged.trySend(Unit)
    }

    val autoWpEnabled get() = rotation.autoWpEnabled
    val autoWpInterval get() = rotation.autoWpInterval
    val autoWpSource get() = rotation.autoWpSource
    val localWallpaperFolderUri get() = rotation.localWallpaperFolderUri
    val autoWpRequiresCharging get() = rotation.autoWpRequiresCharging
    val autoWpRequiresWiFi get() = rotation.autoWpRequiresWiFi
    val autoWpRequiresIdle get() = rotation.autoWpRequiresIdle
    val autoWallpaperDarkenPercent get() = rotation.autoWallpaperDarkenPercent
    val autoWallpaperNightVariantEnabled get() = rotation.autoWallpaperNightVariantEnabled
    val autoBackupEnabled get() = rotation.autoBackupEnabled
    val autoBackupFolderUri get() = rotation.autoBackupFolderUri
    val autoBackupIntervalHours get() = rotation.autoBackupIntervalHours
    val autoBackupKeepCount get() = rotation.autoBackupKeepCount
    val rotateOnUnlock get() = rotation.rotateOnUnlock
    val rotateOnScreenOff get() = rotation.rotateOnScreenOff
    val avoidRecentRepeats get() = rotation.avoidRecentRepeats
    val schedulerEnabled get() = rotation.schedulerEnabled
    val schedulerInterval get() = rotation.schedulerInterval
    val schedulerSource get() = rotation.schedulerSource
    val schedulerDaySource get() = rotation.schedulerDaySource
    val schedulerNightSource get() = rotation.schedulerNightSource
    val schedulerDayNightMode get() = rotation.schedulerDayNightMode
    val schedulerDayStartHour get() = rotation.schedulerDayStartHour
    val schedulerNightStartHour get() = rotation.schedulerNightStartHour
    val schedulerHome get() = rotation.schedulerHome
    val schedulerLock get() = rotation.schedulerLock
    val schedulerShuffle get() = rotation.schedulerShuffle
    val weatherEffects get() = rotation.weatherEffects
    val adaptiveTint get() = rotation.adaptiveTint
    val adaptiveTintIntensity get() = rotation.adaptiveTintIntensity
    val reduceAnimations get() = rotation.reduceAnimations
    val darkModeSwitch get() = rotation.darkModeSwitch
    val darkModeWallpaperId get() = rotation.darkModeWallpaperId
    val lightModeWallpaperId get() = rotation.lightModeWallpaperId
    val liveWallpaperShaderPreset get() = rotation.liveWallpaperShaderPreset
    val collections get() = rotation.collections
    val schedulerCollectionId get() = rotation.schedulerCollectionId
    val localWallpaperFolders get() = rotation.localWallpaperFolders
    val localWallpaperItems get() = rotation.localWallpaperItems
    val autoPreview get() = media.autoPreview
    val gridColumns get() = media.gridColumns
    val previewVolume get() = media.previewVolume
    val ringtoneShuffleEnabled get() = media.ringtoneShuffleEnabled
    val ringtoneShuffleIntervalHours get() = media.ringtoneShuffleIntervalHours
    val alarmShuffleEnabled get() = media.alarmShuffleEnabled
    val soundProfilesEnabled get() = media.soundProfilesEnabled
    val liveWallpaperDimEnabled get() = media.liveWallpaperDimEnabled
    val liveWallpaperColorsEnabled get() = media.liveWallpaperColorsEnabled
    val wallpaperClockOverlayEnabled get() = media.wallpaperClockOverlayEnabled
    val wallpaperClockOverlayMode get() = media.wallpaperClockOverlayMode
    val wallpaperClockOverlayPosition get() = media.wallpaperClockOverlayPosition
    val soundProfilesJson get() = media.soundProfilesJson
    val wallpaperPackEnabled get() = media.wallpaperPackEnabled
    val wallpaperPackJson get() = media.wallpaperPackJson
    val redditSubs get() = media.redditSubs
    val redditVideoSubs get() = media.redditVideoSubs
    val redditProviderEnabled get() = media.redditProviderEnabled
    val preferredRes get() = media.preferredRes
    val userStyles get() = media.userStyles
    val wallpaperStyleLearningSignalCount get() = media.wallpaperStyleLearningSignalCount
    val ytRingtonesQuery get() = media.ytRingtonesQuery
    val ytNotificationsQuery get() = media.ytNotificationsQuery
    val ytAlarmsQuery get() = media.ytAlarmsQuery
    val ytBlockedWords get() = media.ytBlockedWords
    val youtubeProviderEnabled get() = media.youtubeProviderEnabled
    val youtubePoTokenProviderUrl get() = media.youtubePoTokenProviderUrl
    val videoFpsLimit get() = media.videoFpsLimit
    val videoFpsOverlayEnabled get() = media.videoFpsOverlayEnabled
    val videoAutoBatterySaver get() = media.videoAutoBatterySaver
    val wallhavenApiKey get() = media.wallhavenApiKey
    val pexelsApiKey get() = media.pexelsApiKey
    val pixabayApiKey get() = media.pixabayApiKey
    val freesoundApiKey get() = media.freesoundApiKey
    val generatedWallpaperProviderKey get() = media.generatedWallpaperProviderKey
    val providerCredentialStorageUnavailable get() = media.providerCredentialStorageUnavailable
    val generatedContentProviderEnabled get() = media.generatedContentProviderEnabled
    val generatedContentDisclosureAccepted get() = media.generatedContentDisclosureAccepted
    val wallhavenProviderEnabled get() = media.wallhavenProviderEnabled
    val bingProviderEnabled get() = media.bingProviderEnabled
    val pexelsProviderEnabled get() = media.pexelsProviderEnabled
    val pixabayProviderEnabled get() = media.pixabayProviderEnabled
    val communityProviderEnabled get() = community.communityProviderEnabled
    val communityGuidelinesAccepted get() = community.communityGuidelinesAccepted
    val communityGuidelinesAcceptedVersion get() = community.communityGuidelinesAcceptedVersion
    val showSketchyContent get() = community.showSketchyContent
    val showNsfwContent get() = community.showNsfwContent
    val isAdmin get() = community.isAdmin
    val blockedCommunityCreators get() = community.blockedCommunityCreators
    val communityBlockAction get() = community.communityBlockAction
    val communityIdentityCleanup get() = community.communityIdentityCleanup
    val communityIdentitySummary get() = community.communityIdentitySummary
    val wallpaperHistory get() = diagnosticsDelegate.wallpaperHistory
    val cacheUsage get() = diagnosticsDelegate.cacheUsage
    val crashDiagnostics get() = diagnosticsDelegate.crashDiagnostics
    val liveWallpaperLiveness get() = diagnosticsDelegate.liveWallpaperLiveness
    val backgroundWorkDiagnostics get() = diagnosticsDelegate.backgroundWorkDiagnostics
    val externalAutomationDiagnostics get() = diagnosticsDelegate.externalAutomationDiagnostics
    val ytDlpUpdate get() = diagnosticsDelegate.ytDlpUpdate
    val themePackTransfer get() = diagnosticsDelegate.themePackTransfer
    val pendingThemePackSounds get() = diagnosticsDelegate.pendingThemePackSounds
    val generatedAssets get() = diagnosticsDelegate.generatedAssets
    val diagnostics get() = diagnosticsDelegate.diagnostics

    fun clearParallaxGalleryResult() = diagnosticsDelegate.clearParallaxGalleryResult()
    fun clearVideoWallpaperSelectionResult() = diagnosticsDelegate.clearVideoWallpaperSelectionResult()
    fun applyParallaxFromGallery(uri: Uri) = diagnosticsDelegate.applyParallaxFromGallery(uri)
    fun prepareVideoWallpaperFromUri(uri: Uri) = diagnosticsDelegate.prepareVideoWallpaperFromUri(uri)
    fun setAutoWallpaper(enabled: Boolean) = rotation.setAutoWallpaper(enabled)
    fun setAutoWpInterval(hours: Long) = rotation.setAutoWpInterval(hours)
    fun setAutoWpSource(source: String) = rotation.setAutoWpSource(source)
    fun setLocalWallpaperFolderUri(uri: String) = rotation.setLocalWallpaperFolderUri(uri)
    fun addLocalWallpaperFolder(uri: String, makePrimary: Boolean = true) =
        rotation.addLocalWallpaperFolder(uri, makePrimary)
    fun clearLocalWallpaperFolderUri() = rotation.clearLocalWallpaperFolderUri()
    fun removeLocalWallpaperFolder(uri: String) = rotation.removeLocalWallpaperFolder(uri)
    fun rescanLocalWallpaperFolder(uri: String) = rotation.rescanLocalWallpaperFolder(uri)
    fun rescanAllLocalWallpaperFolders() = rotation.rescanAllLocalWallpaperFolders()
    fun setLocalWallpaperFolderTarget(uri: String, target: com.chloemlla.aura.data.model.WallpaperTarget) =
        rotation.setLocalWallpaperFolderTarget(uri, target)
    fun updateLocalWallpaperTags(documentUri: String, tags: String) =
        rotation.updateLocalWallpaperTags(documentUri, tags)
    fun setAutoWallpaperRequiresCharging(value: Boolean) = rotation.setAutoWallpaperRequiresCharging(value)
    fun setAutoWallpaperRequiresWiFiOnly(value: Boolean) = rotation.setAutoWallpaperRequiresWiFiOnly(value)
    fun setRotateOnUnlock(value: Boolean) = rotation.setRotateOnUnlock(value)
    fun setRotateOnScreenOff(value: Boolean) = rotation.setRotateOnScreenOff(value)
    fun setAvoidRecentRepeats(value: Boolean) = rotation.setAvoidRecentRepeats(value)
    fun setAutoWallpaperRequiresIdle(value: Boolean) = rotation.setAutoWallpaperRequiresIdle(value)
    fun setAutoWallpaperDarkenPercent(percent: Int) = rotation.setAutoWallpaperDarkenPercent(percent)
    fun setAutoWallpaperNightVariantEnabled(enabled: Boolean) = rotation.setAutoWallpaperNightVariantEnabled(enabled)
    fun setAutoBackupEnabled(enabled: Boolean) = rotation.setAutoBackupEnabled(enabled)
    fun setAutoBackupFolderUri(uri: String) = rotation.setAutoBackupFolderUri(uri)
    fun clearAutoBackupFolderUri() = rotation.clearAutoBackupFolderUri()
    fun setAutoBackupIntervalHours(hours: Long) = rotation.setAutoBackupIntervalHours(hours)
    fun setAutoBackupKeepCount(count: Int) = rotation.setAutoBackupKeepCount(count)
    fun setSchedulerEnabled(enabled: Boolean) = rotation.setSchedulerEnabled(enabled)
    fun setSchedulerInterval(minutes: Long) = rotation.setSchedulerInterval(minutes)
    fun setSchedulerSource(source: String) = rotation.setSchedulerSource(source)
    fun setSchedulerSource(target: SchedulerSourceTarget, source: String) = rotation.setSchedulerSource(target, source)
    fun setSchedulerDayNightMode(mode: String) = rotation.setSchedulerDayNightMode(mode)
    fun setSchedulerDayStartHour(hour: Int) = rotation.setSchedulerDayStartHour(hour)
    fun setSchedulerNightStartHour(hour: Int) = rotation.setSchedulerNightStartHour(hour)
    fun setSchedulerCollection(id: Long, target: SchedulerSourceTarget = SchedulerSourceTarget.DEFAULT) =
        rotation.setSchedulerCollection(id, target)
    fun setSchedulerHome(enabled: Boolean) = rotation.setSchedulerHome(enabled)
    fun setSchedulerLock(enabled: Boolean) = rotation.setSchedulerLock(enabled)
    fun setSchedulerShuffle(shuffle: Boolean) = rotation.setSchedulerShuffle(shuffle)
    fun setWeatherEffects(enabled: Boolean) = rotation.setWeatherEffects(enabled)
    fun isDailyWallpaperEnabled() = rotation.isDailyWallpaperEnabled()
    fun weatherVfxEffect() = rotation.weatherVfxEffect()
    fun touchEffectStrength() = rotation.touchEffectStrength()
    fun setDailyWallpaperEnabled(enabled: Boolean) = rotation.setDailyWallpaperEnabled(enabled)
    fun setWeatherVfxEffect(effect: String) = rotation.setWeatherVfxEffect(effect)
    fun setTouchEffectStrength(strength: String) = rotation.setTouchEffectStrength(strength)
    fun setReduceAnimations(enabled: Boolean) = rotation.setReduceAnimations(enabled)
    fun setAdaptiveTint(enabled: Boolean) = rotation.setAdaptiveTint(enabled)
    fun setAdaptiveTintIntensity(intensity: Float) = rotation.setAdaptiveTintIntensity(intensity)
    fun setDarkModeSwitch(enabled: Boolean) = rotation.setDarkModeSwitch(enabled)
    fun setDarkModeWallpaperId(id: String) = rotation.setDarkModeWallpaperId(id)
    fun setLightModeWallpaperId(id: String) = rotation.setLightModeWallpaperId(id)
    fun setLiveWallpaperShaderPreset(id: String) = rotation.setLiveWallpaperShaderPreset(id)
    fun setYtRingtonesQuery(query: String) = media.setYtRingtonesQuery(query)
    fun setYtNotificationsQuery(query: String) = media.setYtNotificationsQuery(query)
    fun setYtAlarmsQuery(query: String) = media.setYtAlarmsQuery(query)
    fun setYtBlockedWords(words: String) = media.setYtBlockedWords(words)
    fun setYoutubeProviderEnabled(enabled: Boolean) = media.setYoutubeProviderEnabled(enabled)
    fun setYoutubePoTokenProviderUrl(url: String) = media.setYoutubePoTokenProviderUrl(url)
    fun setAutoPreview(enabled: Boolean) = media.setAutoPreview(enabled)
    fun setGridColumns(columns: Int) = media.setGridColumns(columns)
    fun setPreviewVolume(volume: Float) = media.setPreviewVolume(volume)
    fun setRingtoneShuffleEnabled(enabled: Boolean) = media.setRingtoneShuffleEnabled(enabled)
    fun setRingtoneShuffleIntervalHours(hours: Long) = media.setRingtoneShuffleIntervalHours(hours)
    fun setAlarmShuffleEnabled(enabled: Boolean) = media.setAlarmShuffleEnabled(enabled)
    fun setSoundProfilesEnabled(enabled: Boolean) = media.setSoundProfilesEnabled(enabled)
    fun setSoundProfilesJson(json: String) = media.setSoundProfilesJson(json)
    fun setWallpaperPackEnabled(enabled: Boolean) = media.setWallpaperPackEnabled(enabled)
    fun setWallpaperPackJson(json: String) = media.setWallpaperPackJson(json)
    fun setRedditSubs(subs: String) = media.setRedditSubs(subs)
    fun setRedditVideoSubs(subs: String) = media.setRedditVideoSubs(subs)
    fun setRedditProviderEnabled(enabled: Boolean) = media.setRedditProviderEnabled(enabled)
    fun setPreferredRes(resolution: String) = media.setPreferredRes(resolution)
    fun setUserStyles(styles: String) = media.setUserStyles(styles)
    fun resetWallpaperStyleLearning() = media.resetWallpaperStyleLearning()
    fun setWallhavenKey(key: String) = media.setWallhavenKey(key)
    fun setPexelsKey(key: String) = media.setPexelsKey(key)
    fun setPixabayKey(key: String) = media.setPixabayKey(key)
    fun setFreesoundKey(key: String) = media.setFreesoundKey(key)
    fun setGeneratedWallpaperProviderKey(key: String) = media.setGeneratedWallpaperProviderKey(key)
    fun setWallhavenProviderEnabled(enabled: Boolean) = media.setWallhavenProviderEnabled(enabled)
    fun setBingProviderEnabled(enabled: Boolean) = media.setBingProviderEnabled(enabled)
    fun setPexelsProviderEnabled(enabled: Boolean) = media.setPexelsProviderEnabled(enabled)
    fun setPixabayProviderEnabled(enabled: Boolean) = media.setPixabayProviderEnabled(enabled)
    fun setVideoFpsLimit(fps: Int) = media.setVideoFpsLimit(fps)
    fun setVideoFpsOverlayEnabled(enabled: Boolean) = media.setVideoFpsOverlayEnabled(enabled)
    fun setVideoAutoBatterySaver(enabled: Boolean) = media.setVideoAutoBatterySaver(enabled)
    fun setLiveWallpaperDimEnabled(enabled: Boolean) = media.setLiveWallpaperDimEnabled(enabled)
    fun setLiveWallpaperColorsEnabled(enabled: Boolean) = media.setLiveWallpaperColorsEnabled(enabled)
    fun setWallpaperClockOverlayEnabled(enabled: Boolean) = media.setWallpaperClockOverlayEnabled(enabled)
    fun setWallpaperClockOverlayMode(mode: String) = media.setWallpaperClockOverlayMode(mode)
    fun setWallpaperClockOverlayPosition(position: String) = media.setWallpaperClockOverlayPosition(position)
    fun setGeneratedContentProviderEnabled(enabled: Boolean) = media.setGeneratedContentProviderEnabled(enabled)
    fun acceptGeneratedContentDisclosure() = media.acceptGeneratedContentDisclosure()
    fun resetGeneratedContentDisclosure() = media.resetGeneratedContentDisclosure()
    fun setShowSketchy(show: Boolean) = community.setShowSketchy(show)
    fun setShowNsfw(show: Boolean) = community.setShowNsfw(show)
    fun setCommunityProviderEnabled(enabled: Boolean) = community.setCommunityProviderEnabled(enabled)
    fun acceptCommunityGuidelines() = community.acceptCommunityGuidelines()
    fun resetCommunityGuidelines() = community.resetCommunityGuidelines()
    fun unblockCommunityCreator(userId: String) = community.unblockCommunityCreator(userId)
    fun clearCommunityBlockAction() = community.clearCommunityBlockAction()
    fun refreshCommunityIdentitySummary() = community.refreshCommunityIdentitySummary()
    fun clearLocalCommunityIdentity() = community.clearLocalCommunityIdentity()
    fun clearCommunityIdentityCleanupState() = community.clearCommunityIdentityCleanupState()
    fun refreshGeneratedAssetAudit() = diagnosticsDelegate.refreshGeneratedAssetAudit()
    fun resetDiagnostics() = diagnosticsDelegate.resetDiagnostics()
    fun resetSourceDiagnostics(source: String) = diagnosticsDelegate.resetSourceDiagnostics(source)
    fun refreshCrashDiagnostics() = diagnosticsDelegate.refreshCrashDiagnostics()
    fun refreshLiveWallpaperLiveness() = diagnosticsDelegate.refreshLiveWallpaperLiveness()
    fun reapplyLiveWallpaper(context: android.content.Context) = diagnosticsDelegate.reapplyLiveWallpaper(context)
    suspend fun buildCrashDiagnosticsBundle() = diagnosticsDelegate.buildCrashDiagnosticsBundle()
    fun refreshBackgroundWorkDiagnostics() = diagnosticsDelegate.refreshBackgroundWorkDiagnostics()
    fun setExternalAutomationEnabled(enabled: Boolean) = diagnosticsDelegate.setExternalAutomationEnabled(enabled)
    fun refreshExternalAutomationDiagnostics() = diagnosticsDelegate.refreshExternalAutomationDiagnostics()
    fun updateYtDlp(consent: com.chloemlla.aura.service.YtDlpUpdateConsent) = diagnosticsDelegate.updateYtDlp(consent)
    fun clearYtDlpUpdateNotice() = diagnosticsDelegate.clearYtDlpUpdateNotice()
    fun exportThemePack(uri: Uri) = diagnosticsDelegate.exportThemePack(uri)
    fun importThemePack(uri: Uri) = diagnosticsDelegate.importThemePack(uri)
    fun clearThemePackTransferNotice() = diagnosticsDelegate.clearThemePackTransferNotice()
    fun applyPendingThemePackSounds() = diagnosticsDelegate.applyPendingThemePackSounds()
    fun exportLibrary(uri: Uri) = diagnosticsDelegate.exportLibrary(uri)
    fun importLibrary(uri: Uri) = diagnosticsDelegate.importLibrary(uri)
    fun clearWallpaperHistory() = diagnosticsDelegate.clearWallpaperHistory()
    fun clearCache() = diagnosticsDelegate.clearCache()
}
