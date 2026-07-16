package com.freevibe.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val MEDIA_PLAYBACK = "media_playback"
    const val DAILY_WALLPAPER = "daily_wallpaper"
    const val ROTATION_TRIGGERS = "aura_rotation_triggers"
    const val ROTATION_RECOVERY = "aura_rotation_recovery"

    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    MEDIA_PLAYBACK,
                    "Sound Preview",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Playback controls for sound previews"
                },
                NotificationChannel(
                    DAILY_WALLPAPER,
                    "Daily Wallpaper",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Daily wallpaper picks from active wallpaper sources"
                },
                NotificationChannel(
                    ROTATION_TRIGGERS,
                    "Wallpaper triggers",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Listens for unlock/screen-off to rotate the wallpaper."
                    setShowBadge(false)
                },
                NotificationChannel(
                    ROTATION_RECOVERY,
                    "Wallpaper trigger alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Alerts when wallpaper triggers need Aura to be reopened."
                },
            ),
        )
    }
}
