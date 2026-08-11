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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.freevibe.data.model.Sound
import com.freevibe.data.model.stableKey
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlaybackService : MediaLibraryService() {

    @Inject lateinit var audioPreviewCache: AudioPreviewCache
    @Inject lateinit var bundledContentProvider: BundledContentProvider

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
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession.takeIf {
            controllerInfo.isTrusted ||
                controllerInfo.packageName == packageName ||
                controllerInfo.packageName == MediaSessionService.SERVICE_INTERFACE ||
                controllerInfo.packageName.startsWith("com.chloemlla.")
        } ?: run {
            if (com.freevibe.BuildConfig.DEBUG) {
                Log.w("AudioPlaybackService", "Rejected controller from ${controllerInfo.packageName}")
            }
            null
        }

    // ---- MediaLibraryService: browseable library ----

    override fun onGetLibraryRoot(
        controllerInfo: MediaSession.ControllerInfo,
        rootHint: String?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = MediaItem.Builder()
            .setMediaId("__ROOT__")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Aura Audio Library")
                    .setDescription("Browse and play sounds from Aura")
                    .build()
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, /* isReady */ true))
    }

    override fun onGetItem(
        controllerInfo: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = allBundledSounds().firstOrNull { it.stableKey() == mediaId }
            ?.toMediaItem()
        return if (item != null) {
            Futures.immediateFuture(LibraryResult.ofItem(item))
        } else {
            Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }
    }

    override fun onGetChildren(
        controllerInfo: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        extra: Bundle,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val children: List<MediaItem> = when (parentId) {
            "__ROOT__" -> rootCategories()
            "category/ringtone" -> bundledContentProvider.getRingtones().map { it.toMediaItem() }
            "category/notification" -> bundledContentProvider.getNotifications().map { it.toMediaItem() }
            "category/alarm" -> bundledContentProvider.getAlarms().map { it.toMediaItem() }
            "category/aura_picks" -> allBundledSounds().map { it.toMediaItem() }
            else -> return Futures.immediateFuture(
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }
        // Apply pagination
        val fromIndex = (page - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, children.size)
        val pageItems = if (fromIndex < children.size) {
            children.subList(fromIndex, toIndex)
        } else {
            emptyList()
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(pageItems)))
    }

    // ---- helpers ----

    private fun rootCategories(): List<MediaItem> = listOf(
        browseableCategory("category/ringtone", "Ringtones", "Melodic phone ringtones"),
        browseableCategory("category/notification", "Notifications", "Short notification sounds"),
        browseableCategory("category/alarm", "Alarms", "Attention-getting alarm sounds"),
        browseableCategory("category/aura_picks", "Aura Picks", "All bundled sounds"),
    )

    private fun browseableCategory(mediaId: String, title: String, description: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDescription(description)
                    .build()
            )
            .build()

    private fun allBundledSounds(): List<Sound> =
        bundledContentProvider.getRingtones() +
            bundledContentProvider.getNotifications() +
            bundledContentProvider.getAlarms()

    // ---- existing lifecycle ----

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
}

private fun Sound.toMediaItem(): MediaItem {
    val uri = previewUrl.ifBlank { downloadUrl }
    return MediaItem.Builder()
        .setMediaId(stableKey())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(uploaderName.ifBlank { null })
                .setDescription(description.ifBlank { null })
                .setDuration((duration * 1000L).toLong())
                .build()
        )
        .build()
}