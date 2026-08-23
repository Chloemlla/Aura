package com.chloemlla.aura.data.repository

import okhttp3.ResponseBody

/**
 * Flavor-provided image generation boundary. The shared repository only owns
 * local file retention and references; provider code lives behind this seam.
 */
interface GeneratedWallpaperBackend {
    suspend fun generate(
        prompt: String,
        style: AiStyle,
        apiKey: String,
    ): Result<ResponseBody>
}
