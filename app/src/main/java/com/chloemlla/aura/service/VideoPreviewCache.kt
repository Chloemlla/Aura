package com.chloemlla.aura.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.chloemlla.aura.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

internal fun shouldPrebufferVideoPreview(url: String): Boolean {
    val normalized = url.trim().substringBefore('#')
    if (!normalized.startsWith("http://", ignoreCase = true) &&
        !normalized.startsWith("https://", ignoreCase = true)
    ) {
        return false
    }
    val path = normalized.substringBefore('?').lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".gif")) return false
    if (path.endsWith(".mp4") || path.endsWith(".webm")) return true

    val mime = normalized.substringAfter('?', "")
        .split('&')
        .firstNotNullOfOrNull { parameter ->
            val (key, value) = parameter.split('=', limit = 2).let {
                it.firstOrNull().orEmpty() to it.getOrElse(1) { "" }
            }
            value.takeIf { key.equals("mime", ignoreCase = true) }
        }
        ?.let { encoded ->
            runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }
                .getOrDefault(encoded)
        }
        ?.lowercase()
        .orEmpty()
    return mime.startsWith("video/mp4") || mime.startsWith("video/webm")
}

@OptIn(UnstableApi::class)
@Singleton
class VideoPreviewCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val upstreamFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent("Aura/${BuildConfig.VERSION_NAME} (Android; Open Source)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(false)
    }

    private val cache by lazy {
        SimpleCache(
            File(context.cacheDir, "video-preview-cache"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    private val dataSourceFactory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun mediaSourceFactory(): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(DefaultDataSource.Factory(context, dataSourceFactory))

    suspend fun prebuffer(cacheKey: String, url: String): Boolean {
        if (!shouldPrebufferVideoPreview(url)) return false
        return withContext(Dispatchers.IO) {
            try {
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setKey(cacheKey)
                    .setPosition(0)
                    .setLength(PREBUFFER_BYTES)
                    .build()
                CacheWriter(dataSourceFactory.createDataSource(), dataSpec, null, null).cache()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    private companion object {
        const val PREBUFFER_BYTES = 768L * 1024L
        const val MAX_CACHE_BYTES = 192L * 1024L * 1024L
    }
}
