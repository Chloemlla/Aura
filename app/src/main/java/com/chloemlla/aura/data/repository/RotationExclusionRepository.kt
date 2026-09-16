package com.chloemlla.aura.data.repository

import com.chloemlla.aura.data.local.RotationExclusionDao
import com.chloemlla.aura.data.model.RotationExclusionEntity
import com.chloemlla.aura.data.model.RotationIdentity
import com.chloemlla.aura.data.model.RotationExclusionIndex
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.data.model.rotationIdentity
import com.chloemlla.aura.data.model.toRotationExclusion
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class RotationCandidateSet(
    val candidates: List<Wallpaper>,
    val excludedCount: Int,
    val allExcluded: Boolean,
)

@Singleton
class RotationExclusionRepository @Inject constructor(
    private val dao: RotationExclusionDao,
) {
    val exclusions: Flow<List<RotationExclusionEntity>> = dao.observeAll()

    suspend fun snapshot(): List<RotationExclusionEntity> = dao.getAll()

    suspend fun exclude(identity: RotationIdentity): RotationExclusionEntity =
        identity.toRotationExclusion().also { dao.upsert(it) }

    suspend fun restore(stableId: String) = dao.deleteByStableId(stableId)

    suspend fun restoreAll() = dao.clearAll()

    suspend fun isExcluded(identity: RotationIdentity): Boolean =
        RotationExclusionIndex(dao.getAll()).contains(identity)

    suspend fun filter(wallpapers: List<Wallpaper>): RotationCandidateSet =
        filterRotationCandidates(wallpapers, dao.getAll())
}

internal fun filterRotationCandidates(
    wallpapers: List<Wallpaper>,
    exclusions: List<RotationExclusionEntity>,
): RotationCandidateSet {
    if (wallpapers.isEmpty() || exclusions.isEmpty()) {
        return RotationCandidateSet(wallpapers, excludedCount = 0, allExcluded = false)
    }
    val index = RotationExclusionIndex(exclusions)
    val candidates = wallpapers.filter { wallpaper ->
        val identity = wallpaper.rotationIdentity()
        !index.contains(identity)
    }
    return RotationCandidateSet(
        candidates = candidates,
        excludedCount = wallpapers.size - candidates.size,
        allExcluded = wallpapers.isNotEmpty() && candidates.isEmpty(),
    )
}
