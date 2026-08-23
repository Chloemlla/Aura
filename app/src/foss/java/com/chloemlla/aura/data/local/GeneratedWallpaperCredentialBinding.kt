package com.chloemlla.aura.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal fun PreferencesManager.generatedWallpaperProviderKeyForFlavor(): Flow<String> = flowOf("")

@Suppress("UNUSED_PARAMETER")
internal suspend fun PreferencesManager.setGeneratedWallpaperProviderKeyForFlavor(key: String) = Unit
