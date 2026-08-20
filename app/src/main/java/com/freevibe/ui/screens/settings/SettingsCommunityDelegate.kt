package com.freevibe.ui.screens.settings

import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.repository.CommunityBlockRepository
import com.freevibe.data.repository.VoteRepository
import com.freevibe.service.CommunityIdentityProvider
import com.freevibe.service.CommunityIdentitySummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns community provider settings, moderation actions, and local identity cleanup. */
internal class SettingsCommunityDelegate(
    private val prefs: PreferencesManager,
    private val voteRepo: VoteRepository,
    private val communityBlockRepo: CommunityBlockRepository,
    private val communityIdentityProvider: CommunityIdentityProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val sharing = SharingStarted.WhileSubscribed(5000)

    val communityProviderEnabled = prefs.communityProviderEnabled.stateIn(
        scope,
        sharing,
        PreferencesManager.DEFAULT_COMMUNITY_PROVIDER_ENABLED,
    )
    val communityGuidelinesAccepted = prefs.communityGuidelinesAccepted.stateIn(scope, sharing, false)
    val communityGuidelinesAcceptedVersion = prefs.communityGuidelinesAcceptedVersion.stateIn(scope, sharing, 0)
    val showSketchyContent = prefs.showSketchyContent.stateIn(scope, sharing, false)
    val showNsfwContent = prefs.showNsfwContent.stateIn(scope, sharing, false)
    val isAdmin: Boolean get() = voteRepo.isAdmin
    val blockedCommunityCreators = communityBlockRepo.blockedUsers()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val _communityBlockAction = MutableStateFlow(CommunityBlockActionState())
    val communityBlockAction = _communityBlockAction.asStateFlow()
    private val _communityIdentityCleanup = MutableStateFlow(CommunityIdentityCleanupState())
    val communityIdentityCleanup = _communityIdentityCleanup.asStateFlow()
    private val _communityIdentitySummary = MutableStateFlow(communityIdentityProvider.currentIdentitySummary())
    val communityIdentitySummary = _communityIdentitySummary.asStateFlow()

    fun setShowSketchy(show: Boolean) = scope.launch { prefs.setShowSketchy(show) }
    fun setShowNsfw(show: Boolean) = scope.launch { prefs.setShowNsfw(show) }
    fun setCommunityProviderEnabled(enabled: Boolean) = scope.launch { prefs.setCommunityProviderEnabled(enabled) }
    fun acceptCommunityGuidelines() = scope.launch { prefs.acceptCommunityGuidelines() }
    fun resetCommunityGuidelines() = scope.launch { prefs.resetCommunityGuidelines() }

    fun unblockCommunityCreator(userId: String) = scope.launch {
        if (userId.isBlank()) return@launch
        _communityBlockAction.value = CommunityBlockActionState(unblockingUserId = userId)
        communityBlockRepo.unblockUser(userId)
            .onSuccess {
                _communityBlockAction.value = CommunityBlockActionState(message = "Creator unblocked")
            }
            .onFailure { error ->
                _communityBlockAction.value = CommunityBlockActionState(
                    error = "Unblock failed: ${error.message ?: "try again"}",
                )
            }
    }

    fun clearCommunityBlockAction() {
        _communityBlockAction.value = CommunityBlockActionState()
    }

    fun refreshCommunityIdentitySummary() {
        _communityIdentitySummary.value = communityIdentityProvider.currentIdentitySummary()
    }

    fun clearLocalCommunityIdentity() = scope.launch {
        _communityIdentityCleanup.value = CommunityIdentityCleanupState(clearing = true)
        val result = withContext(ioDispatcher) {
            runCatching { communityIdentityProvider.clearLocalFallbackIdentity() }
        }
        result
            .onSuccess { cleared ->
                refreshCommunityIdentitySummary()
                _communityIdentityCleanup.value = CommunityIdentityCleanupState(
                    message = if (cleared) {
                        "Local community identity cleared"
                    } else {
                        "No local community identity was stored"
                    },
                )
            }
            .onFailure { error ->
                _communityIdentityCleanup.value = CommunityIdentityCleanupState(
                    error = "Local cleanup failed: ${error.message ?: "try again"}",
                )
            }
    }

    fun clearCommunityIdentityCleanupState() {
        _communityIdentityCleanup.value = CommunityIdentityCleanupState()
    }
}
