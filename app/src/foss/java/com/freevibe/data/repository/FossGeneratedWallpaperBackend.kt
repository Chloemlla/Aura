package com.freevibe.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.ResponseBody

@Singleton
class FossGeneratedWallpaperBackend @Inject constructor() : GeneratedWallpaperBackend {
    override suspend fun generate(
        prompt: String,
        style: AiStyle,
        apiKey: String,
    ): Result<ResponseBody> = Result.failure(
        UnsupportedOperationException("Generated wallpapers are unavailable in the FOSS build."),
    )
}
