package com.chloemlla.aura.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.chloemlla.aura.R
import com.chloemlla.aura.data.local.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Quick Settings action that queues Aura's existing one-shot wallpaper rotation. */
@AndroidEntryPoint
class RotateTileService : TileService() {

    @Inject lateinit var prefs: PreferencesManager

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            val automaticRotationEnabled = automaticRotationEnabled()
            RotationTriggerService.enqueueRotation(applicationContext)
            publishTile(automaticRotationEnabled, queued = true)
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        serviceScope.launch {
            publishTile(automaticRotationEnabled(), queued = false)
        }
    }

    private suspend fun automaticRotationEnabled(): Boolean = isAutomaticRotationEnabled(
        legacyEnabled = prefs.autoWallpaperEnabled.first(),
        schedulerEnabled = prefs.schedulerEnabled.first(),
    )

    private fun publishTile(automaticRotationEnabled: Boolean, queued: Boolean) {
        val statusRes = when {
            queued -> R.string.quick_settings_rotate_queued
            automaticRotationEnabled -> R.string.quick_settings_rotate_enabled
            else -> R.string.quick_settings_rotate_disabled
        }
        qsTile?.apply {
            state = if (automaticRotationEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.quick_settings_rotate_title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(statusRes)
                contentDescription = getString(R.string.quick_settings_rotate_description, getString(statusRes))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = getString(statusRes)
            }
            updateTile()
        }
    }
}

internal fun isAutomaticRotationEnabled(
    legacyEnabled: Boolean,
    schedulerEnabled: Boolean,
): Boolean = legacyEnabled || schedulerEnabled
