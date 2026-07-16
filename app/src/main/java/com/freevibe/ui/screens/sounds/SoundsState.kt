package com.freevibe.ui.screens.sounds

import android.net.Uri
import com.freevibe.data.model.Sound
import com.freevibe.data.repository.YouTubeExtractionStatus

@androidx.compose.runtime.Immutable
data class SoundsUiState(
    val sounds: List<Sound> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val selectedTab: SoundTab = SoundTab.RINGTONES,
    val playingId: String? = null,
    val resolvingId: String? = null,
    val isApplying: Boolean = false,
    val applySuccess: String? = null,
    val filterKey: Int = 0,
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val searchReturnTab: SoundTab = SoundTab.RINGTONES,
    val qualityFilter: SoundQualityFilter = SoundQualityFilter.BEST,
    val isRecordingUpload: Boolean = false,
    val recordingStartedAtMs: Long = 0L,
    val recordedUploadUri: Uri? = null,
    val isRecordingPersonal: Boolean = false,
    val personalRecordingUri: Uri? = null,
    val degradedSources: Set<String> = emptySet(),
    val youtubeExtractionStatus: YouTubeExtractionStatus = YouTubeExtractionStatus(),
)

enum class SoundTab {
    RINGTONES,
    NOTIFICATIONS,
    ALARMS,
    YOUTUBE,
    COMMUNITY,
    SEARCH,
}
