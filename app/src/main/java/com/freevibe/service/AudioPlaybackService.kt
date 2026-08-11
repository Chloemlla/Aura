package com.freevibe.service

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Sound
import com.freevibe.data.model.stableKey
import com.freevibe.data.repository.FavoritesRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class AudioPlaybackService : MediaLibraryService() {

    @Inject lateinit var audioPreviewCache: AudioPreviewCache
    @Inject lateinit var bundledContentProvider: BundledContentProvider
    @Inject lateinit var favoritesRepository: FavoritesRepository

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(audioPreviewCache.mediaSourceFactory())
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 1_000,
                        /* maxBufferMs = */ 15_000,
                        /* bufferForPlaybackMs = */ 250,
                        /* bufferForPlaybackAfterRebufferMs = */ 500,
                    )
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .build()
    }

    @OptIn(UnstableApi::class)
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val allowed = controllerInfo.isTrusted ||
            controllerInfo.packageName == packageName ||
            controllerInfo.packageName.startsWith("com.chloemlla.")
        if (!allowed) {
            Log.w("AudioPlaybackService", "Rejected controller from ${controllerInfo.packageName}")
        }
        return mediaSession.takeIf { allowed }
    }

    @OptIn(UnstableApi::class)
    override fun onGetLibraryRoot(
        controllerInfo: MediaSession.ControllerInfo,
        rootHints: Bundle?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Aura")
                            .setIsBrowsable(true)
                            .build(),
                    )
                    .build(),
            ),
        )

    @OptIn(UnstableApi::class)
    override fun onGetChildren(
        controllerInfo: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: Bundle?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val children: ImmutableList<MediaItem> = when (parentId) {
            ROOT_ID -> ImmutableList.of(
                browseableCategory("Ringtones", CATEGORY_RINGTONE, "Curated melodic ringtones"),
                browseableCategory("Notifications", CATEGORY_NOTIFICATION, "Short, crisp notification sounds"),
                browseableCategory("Alarms", CATEGORY_ALARM, "Attention-getting alarm sounds"),
                browseableCategory("Favorites", CATEGORY_FAVORITES, "Your saved sounds"),
                browseableCategory("Aura Picks", CATEGORY_AURA_PICKS, "All bundled Aura Picks"),
            )
            CATEGORY_RINGTONE -> bundledContentProvider.getRingtones()
                .map { it.toMediaItem() }
                .let(ImmutableList::copyOf)
            CATEGORY_NOTIFICATION -> bundledContentProvider.getNotifications()
                .map { it.toMediaItem() }
                .let(ImmutableList::copyOf)
            CATEGORY_ALARM -> bundledContentProvider.getAlarms()
                .map { it.toMediaItem() }
                .let(ImmutableList::copyOf)
            CATEGORY_FAVORITES -> runBlocking { favoritesRepository.getSounds().first() }
                .mapNotNull { favorite ->
                    val source = runCatching {
                        ContentSource.valueOf(favorite.source.uppercase(Locale.ROOT))
                    }.getOrNull() ?: return@mapNotNull null
                    Sound(
                        id = favorite.id,
                        source = source,
                        name = favorite.name,
                        previewUrl = favorite.fullUrl,
                        downloadUrl = favorite.fullUrl,
                        duration = favorite.duration,
                        uploaderName = favorite.uploaderName ?: "",
                    ).toMediaItem()
                }
                .let(ImmutableList::copyOf)
            CATEGORY_AURA_PICKS -> allBundledSounds()
                .map { it.toMediaItem() }
                .let(ImmutableList::copyOf)
            else -> return Futures.immediateFuture(
                LibraryResult.ofError(MediaLibraryService.RESULT_ERROR_BAD_VALUE),
            )
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(children))
    }

    @OptIn(UnstableApi::class)
    override fun onGetItem(
        controllerInfo: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val match = allBundledSounds().firstOrNull { it.stableKey() == mediaId }
        return Futures.immediateFuture(
            if (match != null) {
                LibraryResult.ofItem(match.toMediaItem())
            } else {
                LibraryResult.ofError(MediaLibraryService.RESULT_ERROR_BAD_VALUE)
            },
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun Sound.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(stableKey())
            .setUri(previewUrl.ifBlank { downloadUrl })
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(uploaderName.ifBlank { null })
                    .setDescription(description.ifBlank { null })
                    .setDurationMs((duration * 1_000).toLong())
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private fun browseableCategory(title: String, mediaId: String, subtitle: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .build(),
            )
            .build()

    private fun allBundledSounds(): List<Sound> =
        bundledContentProvider.getRingtones() +
            bundledContentProvider.getNotifications() +
            bundledContentProvider.getAlarms()

    companion object {
        private const val ROOT_ID = "__ROOT__"
        private const val CATEGORY_RINGTONE = "category/ringtone"
        private const val CATEGORY_NOTIFICATION = "category/notification"
        private const val CATEGORY_ALARM = "category/alarm"
        private const val CATEGORY_FAVORITES = "category/favorites"
        private const val CATEGORY_AURA_PICKS = "category/aura_picks"
    }
}
