package com.chloemlla.aura.ui.screens.downloads

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chloemlla.aura.data.local.DownloadDao
import com.chloemlla.aura.data.model.SOURCE_AVAILABILITY_AVAILABLE
import com.chloemlla.aura.data.model.SOURCE_AVAILABILITY_UNAVAILABLE
import com.chloemlla.aura.service.DownloadManager
import com.chloemlla.aura.service.LocalMediaRelinkManager
import com.chloemlla.aura.service.LocalMediaRelinkOutcome
import com.chloemlla.aura.service.LocalMediaRelinkTarget
import com.chloemlla.aura.service.MediaCopyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadDao: DownloadDao,
    private val downloadManager: DownloadManager,
    private val mediaCopyStore: MediaCopyStore,
    private val localMediaRelinkManager: LocalMediaRelinkManager,
) : ViewModel() {
    val allDownloads = downloadDao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val wallpaperDownloads = downloadDao.getByType("WALLPAPER").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val soundDownloads = downloadDao.getByType("SOUND").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeDownloads = downloadManager.activeDownloads

    fun deleteDownload(id: String) = viewModelScope.launch { downloadManager.deleteDownload(id) }
    suspend fun deleteOptimizedCopy(id: String): Boolean = mediaCopyStore.deleteOptimizedCopy(id)
    suspend fun relinkDownload(id: String, uri: Uri, acceptMismatch: Boolean = false): LocalMediaRelinkOutcome =
        localMediaRelinkManager.relink(LocalMediaRelinkTarget.Download(id), uri, acceptMismatch)

    /** Puts a staged download back, file included, from the Undo action. */
    fun restoreDownload(id: String) = viewModelScope.launch { downloadManager.restoreDownload(id) }
    fun dismissActive(id: String) = downloadManager.clearCompleted(id)
    fun markSourceUnavailable(id: String, reason: String? = null) = viewModelScope.launch {
        downloadDao.updateSourceAvailability(
            id,
            SOURCE_AVAILABILITY_UNAVAILABLE,
            reason?.takeIf { it.isNotBlank() },
        )
    }
    fun clearSourceUnavailable(id: String) = viewModelScope.launch {
        downloadDao.updateSourceAvailability(id, SOURCE_AVAILABILITY_AVAILABLE, null)
    }
}
