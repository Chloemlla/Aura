package com.chloemlla.aura

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.Wallpaper
import com.chloemlla.aura.service.ExternalAutomationDispatcher
import com.chloemlla.aura.service.ExternalMediaKind
import com.chloemlla.aura.service.IngestedExternalMedia
import com.chloemlla.aura.service.MediaIngestionLimitExceeded
import com.chloemlla.aura.service.MediaIngestionMediaRejected
import com.chloemlla.aura.service.RotationTriggerRecovery
import com.chloemlla.aura.service.TaskerActionReceiver
import com.chloemlla.aura.service.extractCollectionShareToken
import com.chloemlla.aura.service.ingestExternalMedia
import com.chloemlla.aura.service.parseExternalMediaIntent
import com.chloemlla.aura.ui.FreeVibeRoot
import com.chloemlla.aura.ui.navigation.Screen
import com.chloemlla.aura.ui.theme.FreeVibeTheme
import com.chloemlla.aura.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LaunchNavigation(
    val route: String? = null,
    val wallpaper: Wallpaper? = null,
    val token: Long = System.nanoTime(),
)

private const val EXTRA_DAILY_WALLPAPER_ID = "daily_wallpaper_id"
private const val EXTRA_DAILY_WALLPAPER_URL = "daily_wallpaper_url"
private const val EXTRA_DAILY_WALLPAPER_THUMB = "daily_wallpaper_thumb"
private const val EXTRA_DAILY_WALLPAPER_SOURCE = "daily_wallpaper_source"
private const val EXTRA_DAILY_WALLPAPER_WIDTH = "daily_wallpaper_width"
private const val EXTRA_DAILY_WALLPAPER_HEIGHT = "daily_wallpaper_height"
private const val EXTRA_NAVIGATE_TO = "navigate_to"
internal const val ACTION_SHORTCUT_SEARCH = "com.chloemlla.aura.action.SEARCH"
internal const val ACTION_SHORTCUT_DOWNLOADS = "com.chloemlla.aura.action.DOWNLOADS"

internal fun buildExternalMediaNavigation(media: IngestedExternalMedia): LaunchNavigation =
    when (media.kind) {
        ExternalMediaKind.IMAGE -> {
            val uriString = media.uri.toString()
            val wallpaper = Wallpaper(
                id = "shared_image_${uriString.hashCode()}",
                source = ContentSource.LOCAL,
                thumbnailUrl = uriString,
                fullUrl = uriString,
                width = 0,
                height = 0,
                category = "Shared image",
            )
            // The route carries the local wallpaper metadata. Do not also set
            // initialWallpaper, which would intentionally route to detail.
            LaunchNavigation(route = Screen.WallpaperCrop.createRoute(wallpaper))
        }
        ExternalMediaKind.AUDIO ->
            LaunchNavigation(route = Screen.SoundEditor.createLocalRoute(media.uri))
    }

internal fun consumeLaunchNavigation(intent: Intent?): LaunchNavigation? {
    val navigation = parseLaunchNavigation(intent)
    intent?.removeExtra(EXTRA_DAILY_WALLPAPER_ID)
    intent?.removeExtra(EXTRA_DAILY_WALLPAPER_URL)
    intent?.removeExtra(EXTRA_DAILY_WALLPAPER_THUMB)
    intent?.removeExtra(EXTRA_DAILY_WALLPAPER_SOURCE)
    intent?.removeExtra(EXTRA_DAILY_WALLPAPER_WIDTH)
    intent?.removeExtra(EXTRA_DAILY_WALLPAPER_HEIGHT)
    intent?.removeExtra(EXTRA_NAVIGATE_TO)
    return navigation
}

internal fun shouldHandleInitialLaunchNavigation(savedInstanceState: Bundle?): Boolean =
    savedInstanceState == null

internal fun routeForShortcutAction(action: String?): String? =
    when (action) {
        TaskerActionReceiver.ACTION_SHUFFLE_NOW,
        TaskerActionReceiver.ACTION_ROTATE_NOW,
        ACTION_SHORTCUT_SEARCH -> Screen.Wallpapers.route
        ACTION_SHORTCUT_DOWNLOADS -> Screen.Downloads.route
        else -> null
    }

internal fun buildLaunchNavigation(
    route: String? = null,
    wallpaperId: String? = null,
    fullUrl: String = "",
    thumbnailUrl: String = "",
    sourceName: String? = null,
    width: Int = 0,
    height: Int = 0,
): LaunchNavigation? {
    val wallpaper = buildLaunchWallpaper(
        wallpaperId = wallpaperId,
        fullUrl = fullUrl,
        thumbnailUrl = thumbnailUrl,
        sourceName = sourceName,
        width = width,
        height = height,
    )

    val resolvedRoute = wallpaper?.let { Screen.WallpaperDetail.createRoute(it) } ?: route
    return if (resolvedRoute != null || wallpaper != null) {
        LaunchNavigation(route = resolvedRoute, wallpaper = wallpaper)
    } else {
        null
    }
}

private fun isAllowedLaunchUrl(url: String): Boolean {
    // Pure-JVM scheme extraction (avoids android.net.Uri so this helper is unit-testable
    // without Robolectric). HTTPS only — blocks file://, content://, javascript:, etc.
    val trimmed = url.trim()
    val colonIdx = trimmed.indexOf(':')
    if (colonIdx <= 0) return false
    val scheme = trimmed.substring(0, colonIdx).lowercase(java.util.Locale.ROOT)
    return scheme == "https"
}

internal fun buildLaunchWallpaper(
    wallpaperId: String? = null,
    fullUrl: String = "",
    thumbnailUrl: String = "",
    sourceName: String? = null,
    width: Int = 0,
    height: Int = 0,
): Wallpaper? {
    val normalizedThumb = thumbnailUrl.ifBlank { fullUrl }
    return if (!wallpaperId.isNullOrBlank() && fullUrl.isNotBlank() && isAllowedLaunchUrl(fullUrl)) {
        Wallpaper(
            id = wallpaperId,
            source = sourceName
                ?.let { name ->
                    runCatching { ContentSource.valueOf(name) }.getOrDefault(ContentSource.REDDIT)
                }
                ?: ContentSource.REDDIT,
            thumbnailUrl = normalizedThumb,
            fullUrl = fullUrl,
            width = width,
            height = height,
            category = "Wallpaper of the Day",
        )
    } else {
        null
    }
}

internal fun parseLaunchNavigation(intent: Intent?): LaunchNavigation? {
    if (intent == null) return null
    parseSetWallpaperNavigation(intent)?.let { return it }
    parseCollectionImportNavigation(intent)?.let { return it }

    return buildLaunchNavigation(
        route = intent.getStringExtra(EXTRA_NAVIGATE_TO) ?: routeForShortcutAction(intent.action),
        wallpaperId = intent.getStringExtra(EXTRA_DAILY_WALLPAPER_ID),
        fullUrl = intent.getStringExtra(EXTRA_DAILY_WALLPAPER_URL).orEmpty(),
        thumbnailUrl = intent.getStringExtra(EXTRA_DAILY_WALLPAPER_THUMB).orEmpty(),
        sourceName = intent.getStringExtra(EXTRA_DAILY_WALLPAPER_SOURCE),
        width = intent.getIntExtra(EXTRA_DAILY_WALLPAPER_WIDTH, 0),
        height = intent.getIntExtra(EXTRA_DAILY_WALLPAPER_HEIGHT, 0),
    )
}

private fun parseSetWallpaperNavigation(intent: Intent): LaunchNavigation? {
    if (intent.action != Intent.ACTION_ATTACH_DATA) return null
    val data = intent.data ?: return null
    val type = intent.type ?: return null
    if (!type.startsWith("image/") || !isAllowedAttachDataUri(data, intent.flags)) return null
    val uriString = data.toString()
    val wallpaper = Wallpaper(
        id = "set-with-${uriString.hashCode()}",
        source = ContentSource.LOCAL,
        thumbnailUrl = uriString,
        fullUrl = uriString,
        width = 0,
        height = 0,
    )
    return LaunchNavigation(
        route = Screen.WallpaperCrop.createRoute(wallpaper),
        wallpaper = wallpaper,
    )
}

/** ACTION_ATTACH_DATA must carry a provider-backed URI and an explicit read grant. */
internal fun isAllowedAttachDataUri(uri: Uri, intentFlags: Int): Boolean =
    uri.scheme.equals("content", ignoreCase = true) &&
        !uri.authority.isNullOrBlank() &&
        intentFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

private fun parseCollectionImportNavigation(intent: Intent): LaunchNavigation? {
    val data = intent.data
    val token = data?.toString()?.let(::extractCollectionShareToken)
    if (!token.isNullOrBlank()) {
        return buildLaunchNavigation(route = Screen.Collections.createRoute(importToken = token))
    }

    val importUri = when {
        intent.action == Intent.ACTION_SEND && intent.isJsonCollectionShare() ->
            intent.collectionStreamUri() ?: intent.clipData
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.uri
        intent.action == Intent.ACTION_VIEW && data?.isJsonLikeCollectionUri(intent.type) == true -> data
        else -> null
    } ?: return null

    return buildLaunchNavigation(route = Screen.Collections.createRoute(importUri = importUri.toString()))
}

private fun Intent.isJsonCollectionShare(): Boolean =
        type?.contains("json", ignoreCase = true) == true ||
        data?.toString()?.endsWith(".json", ignoreCase = true) == true ||
        collectionStreamUri()?.toString()?.endsWith(".json", ignoreCase = true) == true ||
        clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?.toString()?.endsWith(".json", ignoreCase = true) == true

private fun Uri.isJsonLikeCollectionUri(intentType: String?): Boolean {
    val asString = toString()
    return intentType?.contains("json", ignoreCase = true) == true ||
        asString.endsWith(".json", ignoreCase = true) ||
        scheme.equals("content", ignoreCase = true)
}

@Suppress("DEPRECATION")
private fun Intent.collectionStreamUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var launchNavigation by mutableStateOf<LaunchNavigation?>(null)
    private var externalMediaJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchIntent = intent
        launchNavigation = if (shouldHandleInitialLaunchNavigation(savedInstanceState)) {
            handleShortcutSideEffects(intent)
            consumeLaunchNavigation(intent)
        } else {
            null
        }
        setContent {
            FreeVibeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FreeVibeRoot(
                        initialNavigateTo = launchNavigation?.route,
                        initialWallpaper = launchNavigation?.wallpaper,
                        navigationToken = launchNavigation?.token ?: 0L,
                    )
                }
            }
        }
        // A recreated activity (rotation / process restore) redelivers the same launch
        // intent; re-ingesting it duplicates the import and jumps back into the editor.
        if (shouldHandleInitialLaunchNavigation(savedInstanceState)) {
            handleExternalMediaIntent(launchIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutSideEffects(intent)
        launchNavigation = consumeLaunchNavigation(intent)
        handleExternalMediaIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // A visible activity is an Android 12+ foreground-service start exemption.
        // Retry any trigger service request rejected during a background process start.
        RotationTriggerRecovery.retryIfPending(this)
    }

    /**
     * MainActivity is exported, so an automation app (or `am start`) can reach the
     * same rotate/shuffle actions the exported receiver exposes. Route them through
     * the shared dispatcher so the opt-in consent and 30s throttle apply here too;
     * ordinary launcher shortcuts short-circuit inside the dispatcher and enqueue
     * nothing.
     */
    private fun handleShortcutSideEffects(intent: Intent?) {
        ExternalAutomationDispatcher.dispatch(
            context = this,
            intent = intent,
            entryPoint = ExternalAutomationDispatcher.ENTRY_POINT_ACTIVITY,
        )
    }

    private fun handleExternalMediaIntent(intent: Intent?) {
        externalMediaJob?.cancel()
        val parsed = parseExternalMediaIntent(intent) ?: return
        if (parsed.isFailure) {
            showExternalMediaError(parsed.exceptionOrNull())
            return
        }
        val request = parsed.getOrThrow()
        externalMediaJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { ingestExternalMedia(this@MainActivity, request) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
            }
            result
                .onSuccess { launchNavigation = buildExternalMediaNavigation(it) }
                .onFailure(::showExternalMediaError)
        }
    }

    private fun showExternalMediaError(error: Throwable?) {
        val messageRes = when (error) {
            is MediaIngestionLimitExceeded -> R.string.external_media_too_large
            is MediaIngestionMediaRejected -> R.string.external_media_unsupported
            else -> R.string.external_media_open_failed
        }
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_LONG).show()
    }
}
