package com.chloemlla.aura.data.repository

import com.chloemlla.aura.service.readStreamCapped
import okhttp3.ResponseBody

/**
 * Flavor-provided image generation boundary. The shared repository only owns
 * local file retention and references; provider code lives behind this seam.
 *
 * [generate] is the low-level seam flavor implementations provide; it returns an
 * OkHttp [ResponseBody] because that is exactly what the provider call produces.
 * The repository layer must not see that type, so [generateWallpaper] is the
 * domain entry point: it reads the body into a capped [ByteArray] and the upper
 * layer only ever deals with raw image bytes.
 */
interface GeneratedWallpaperBackend {
    suspend fun generate(
        prompt: String,
        style: AiStyle,
        apiKey: String,
    ): Result<ResponseBody>

    /**
     * Domain-typed image generation for the repository layer.
     *
     * Defaults to reading the provider [generate] body into memory, capped at
     * [maxBytes] so an oversized response fails instead of exhausting memory.
     * Flavor backends override [generate]; they do not need to override this.
     */
    suspend fun generateWallpaper(
        prompt: String,
        style: AiStyle,
        apiKey: String,
        maxBytes: Long,
    ): Result<ByteArray> = generate(prompt, style, apiKey).map { body ->
        body.use { response ->
            response.byteStream().use { input ->
                readStreamCapped(input, maxBytes)
            }
        }
    }
}
