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
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val clashProxyManager: ClashProxyManager,
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

    init {
        // SimpleCache construction scans the cache directory and opens/creates the
        // SQLite index synchronously — up to hundreds of ms of disk I/O. The UI calls
        // mediaSourceFactory() from Compose (`remember`), i.e. on the main thread, so
        // kick the construction off immediately on a background thread instead of
        // letting that first call perform it inline. `by lazy` is synchronized, so a
        // main-thread access while prewarming merely joins the in-progress init rather
        // than starting a second one.
        val t = Thread({ runCatching { cache } }, "aura-preview-cache-prewarm")
        t.isDaemon = true
        t.start()
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
