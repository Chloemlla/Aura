package com.chloemlla.aura.ui.screens.sounds

import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.FavoriteIdentity
import com.chloemlla.aura.data.model.Sound
import com.chloemlla.aura.data.remote.toSound
import com.chloemlla.aura.data.repository.FavoritesRepository
import com.chloemlla.aura.service.BundledContentProvider
import com.chloemlla.aura.service.SelectedContentHolder
import kotlinx.coroutines.flow.StateFlow

internal class SoundSelectionResolver(
    private val selectedContent: SelectedContentHolder,
    private val favoritesRepo: FavoritesRepository,
    private val bundledContent: BundledContentProvider,
    private val state: StateFlow<SoundsUiState>,
    private val communityUploads: StateFlow<List<Sound>>,
) {
    fun selectSound(sound: Sound) {
        selectedContent.selectSound(sound)
    }

    suspend fun resolveSound(
        id: String,
        source: ContentSource? = null,
        previewUrl: String? = null,
        downloadUrl: String? = null,
    ): Sound? {
        selectedContent.selectedSound.value
            ?.takeIf { matchesSoundIdentity(it, id, source, previewUrl, downloadUrl) }
            ?.let { return it }

        state.value.sounds.firstOrNull { matchesSoundIdentity(it, id, source, previewUrl, downloadUrl) }?.let { return it }

        communityUploads.value.firstOrNull { matchesSoundIdentity(it, id, source, previewUrl, downloadUrl) }?.let { return it }

        listOf(
            bundledContent.getRingtones(),
            bundledContent.getNotifications(),
            bundledContent.getAlarms(),
        ).flatten().firstOrNull { matchesSoundIdentity(it, id, source, previewUrl, downloadUrl) }?.let { return it }

        (source?.let {
            favoritesRepo.getByIdentity(
                FavoriteIdentity(
                    id = id,
                    source = it.name,
                    type = "SOUND",
                ),
            )
        } ?: favoritesRepo.getLatestByIdAndType(id, "SOUND"))
            ?.takeIf { it.type == "SOUND" }
            ?.toSound()
            ?.takeIf { matchesSoundIdentity(it, id, source, previewUrl, downloadUrl) }
            ?.let { return it }

        return null
    }

    suspend fun ensureSelectedSound(
        id: String,
        source: ContentSource? = null,
        previewUrl: String? = null,
        downloadUrl: String? = null,
    ): Boolean {
        val resolved = resolveSound(id, source, previewUrl, downloadUrl) ?: return false
        selectedContent.selectSound(resolved)
        return true
    }
}
