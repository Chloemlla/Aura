package com.chloemlla.aura.ui.screens.sounds

import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.Sound
import com.chloemlla.aura.service.BundledContentProvider
import com.chloemlla.aura.util.rethrowIfCancelled
import kotlinx.coroutines.flow.first

internal data class SoundQuerySet(
    val ytQueries: List<String>,
)

internal class SoundBrowseQueries(
    private val prefs: PreferencesManager,
    private val bundledContent: BundledContentProvider,
) {
    suspend fun buildQueries(snapshot: SoundsUiState): SoundQuerySet {
        if (!isYouTubeProviderEnabled()) return SoundQuerySet(emptyList())

        fun compactQueries(vararg queries: String): List<String> =
            queries.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)

        val ringtoneQuery = prefs.ytSoundQueryRingtones.first()
            .ifBlank { PreferencesManager.defaultRingtoneQuery() }
        val notificationQuery = prefs.ytSoundQueryNotifications.first()
            .ifBlank { PreferencesManager.defaultNotificationQuery() }
        val alarmQuery = prefs.ytSoundQueryAlarms.first()
            .ifBlank { PreferencesManager.defaultAlarmQuery() }

        return when (snapshot.selectedTab) {
            SoundTab.RINGTONES -> SoundQuerySet(
                ytQueries = compactQueries(ringtoneQuery, "phone ringtone sound effect", "classic phone ringtones"),
            )
            SoundTab.NOTIFICATIONS -> SoundQuerySet(
                ytQueries = compactQueries(notificationQuery, "notification sound effect short", "phone notification sound effect"),
            )
            SoundTab.ALARMS -> SoundQuerySet(
                ytQueries = compactQueries(alarmQuery, "alarm sound effect short", "alarm clock sound effect"),
            )
            SoundTab.YOUTUBE -> SoundQuerySet(emptyList())
            SoundTab.COMMUNITY -> SoundQuerySet(emptyList())
            SoundTab.SEARCH -> SoundQuerySet(
                ytQueries = compactQueries(snapshot.query, "${snapshot.query} sound effect", "${snapshot.query} ringtone"),
            )
        }
    }

    fun bundledSoundsFor(tab: SoundTab): List<Sound> = when (tab) {
        SoundTab.RINGTONES -> bundledContent.getRingtones()
        SoundTab.NOTIFICATIONS -> bundledContent.getNotifications()
        SoundTab.ALARMS -> bundledContent.getAlarms()
        else -> emptyList()
    }

    fun tabDurationRange(snapshot: SoundsUiState): Pair<Int, Int> = when (snapshot.selectedTab) {
        SoundTab.RINGTONES -> 5 to 45
        SoundTab.NOTIFICATIONS -> 0 to 8
        SoundTab.ALARMS -> 5 to 60
        SoundTab.YOUTUBE -> 0 to 600
        SoundTab.COMMUNITY -> 0 to 600
        SoundTab.SEARCH -> 0 to 60
    }

    suspend fun blockedWords(): List<String> =
        try {
            prefs.ytSoundBlockedWords.first()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            emptyList()
        }

    suspend fun isYouTubeProviderEnabled(): Boolean = prefs.youtubeProviderEnabled.first()
}
