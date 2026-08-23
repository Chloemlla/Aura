package com.chloemlla.aura.service

import android.app.wallpaper.WallpaperDescription
import android.os.PersistableBundle
import androidx.annotation.RequiresApi

internal const val WALLPAPER_DESCRIPTION_SOURCE_KEY = "aura.source"
internal const val WALLPAPER_DESCRIPTION_SHADER_PRESET_KEY = "aura.shader_preset"
internal const val WALLPAPER_DESCRIPTION_WEATHER_EFFECT_KEY = "aura.weather_effect"
internal const val WALLPAPER_DESCRIPTION_WIND_SPEED_KEY = "aura.wind_speed"

internal data class AuraWallpaperDescriptionContent(
    val source: String? = null,
    val shaderPresetId: String? = null,
    val weatherEffect: String? = null,
    val windSpeed: Double? = null,
)

internal fun auraWallpaperDescriptionContent(
    source: String? = null,
    shaderPresetId: String? = null,
    weatherEffect: String? = null,
    windSpeed: Double? = null,
): PersistableBundle = PersistableBundle().apply {
    source?.takeIf { it.isNotBlank() }?.let { putString(WALLPAPER_DESCRIPTION_SOURCE_KEY, it) }
    shaderPresetId?.takeIf { it.isNotBlank() }?.let {
        putString(WALLPAPER_DESCRIPTION_SHADER_PRESET_KEY, it)
    }
    weatherEffect?.takeIf { it.isNotBlank() }?.let {
        putString(WALLPAPER_DESCRIPTION_WEATHER_EFFECT_KEY, it)
    }
    windSpeed?.let { putDouble(WALLPAPER_DESCRIPTION_WIND_SPEED_KEY, it) }
}

internal fun auraWallpaperDescriptionId(
    kind: String,
    content: AuraWallpaperDescriptionContent,
): String = "aura-$kind-${content.hashCode()}"

@RequiresApi(36)
internal fun readAuraWallpaperDescriptionContent(
    description: WallpaperDescription,
): AuraWallpaperDescriptionContent? {
    val content = description.content ?: return null
    val source = content.getString(WALLPAPER_DESCRIPTION_SOURCE_KEY)
    val shaderPresetId = content.getString(WALLPAPER_DESCRIPTION_SHADER_PRESET_KEY)
    val weatherEffect = content.getString(WALLPAPER_DESCRIPTION_WEATHER_EFFECT_KEY)
    val windSpeed = if (content.containsKey(WALLPAPER_DESCRIPTION_WIND_SPEED_KEY)) {
        content.getDouble(WALLPAPER_DESCRIPTION_WIND_SPEED_KEY)
    } else {
        null
    }
    return if (source == null && shaderPresetId == null && weatherEffect == null && windSpeed == null) {
        null
    } else {
        AuraWallpaperDescriptionContent(source, shaderPresetId, weatherEffect, windSpeed)
    }
}

@RequiresApi(36)
internal fun buildAuraWallpaperDescription(
    id: String,
    title: CharSequence,
    description: CharSequence,
    content: PersistableBundle,
): WallpaperDescription = WallpaperDescription.Builder()
    .setId(id)
    .setTitle(title)
    .setContextDescription(description)
    .setDescription(listOf(description))
    .setContent(content)
    .build()
