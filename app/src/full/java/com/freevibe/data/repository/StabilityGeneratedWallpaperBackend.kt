package com.freevibe.data.repository

import com.freevibe.data.remote.stability.StabilityAiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StabilityGeneratedWallpaperBackend @Inject constructor(
    private val api: StabilityAiApi,
) : GeneratedWallpaperBackend {
    override suspend fun generate(
        prompt: String,
        style: AiStyle,
        apiKey: String,
    ): Result<ResponseBody> = withContext(Dispatchers.IO) {
        try {
            val textType = "text/plain".toMediaType()
            val parts = buildMap<String, RequestBody> {
                put("prompt", prompt.toRequestBody(textType))
                put("aspect_ratio", "9:16".toRequestBody(textType))
                put("output_format", "png".toRequestBody(textType))
                if (style.preset.isNotEmpty()) {
                    put("style_preset", style.preset.toRequestBody(textType))
                }
            }

            val response = api.generateImage(
                authHeader = "Bearer $apiKey",
                accept = "image/*",
                parts = parts,
            )
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()?.takeIf { it.isNotBlank() }
                throw IllegalStateException(friendlyErrorMessage(response.code(), errorBody))
            }

            val body = response.body()
                ?: throw IllegalStateException("Empty response from Stability AI")
            Result.success(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    companion object {
        internal fun friendlyErrorMessage(code: Int, errorBody: String?): String {
            val base = when (code) {
                401 -> "Stability AI key is invalid or expired. Update it in the key field."
                402 -> "Stability AI account is out of credits. Top up or check billing at platform.stability.ai/account."
                403 -> "Prompt was rejected by Stability AI's content policy. Try rewording."
                422 -> "Prompt could not be processed. Try a shorter or simpler description."
                429 -> "Stability AI rate limit hit. Wait 60 seconds, then check platform.stability.ai/account if it keeps happening."
                in 500..599 -> "Stability AI server error ($code). Try again shortly."
                else -> "Generation failed (HTTP $code)."
            }
            return if (!errorBody.isNullOrBlank()) "$base $errorBody" else base
        }
    }
}
