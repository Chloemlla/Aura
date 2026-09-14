package com.freevibe.ui.rotation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freevibe.data.model.RotationExclusionEntity
import com.freevibe.data.model.RotationIdentity
import com.freevibe.data.repository.RotationExclusionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RotationExclusionsViewModel @Inject constructor(
    private val repository: RotationExclusionRepository,
) : ViewModel() {
    val exclusions = repository.exclusions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    suspend fun exclude(identity: RotationIdentity): RotationExclusionEntity =
        repository.exclude(identity)

    suspend fun restoreNow(stableId: String) = repository.restore(stableId)

    fun restore(stableId: String) {
        viewModelScope.launch { repository.restore(stableId) }
    }

    fun restoreAll() {
        viewModelScope.launch { repository.restoreAll() }
    }
}
