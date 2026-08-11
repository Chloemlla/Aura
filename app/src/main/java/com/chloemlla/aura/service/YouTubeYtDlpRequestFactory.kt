package com.chloemlla.aura.service

import android.content.Context
import com.chloemlla.aura.R
import com.chloemlla.aura.data.local.PreferencesManager
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class YouTubePoTokenProviderState {
    DISABLED,
    READY,
    INVALID_CONFIGURATION,
    INSTALL_FAILED,
}

data class YouTubePoTokenProviderStatus(
    val state: YouTubePoTokenProviderState = YouTubePoTokenProviderState.DISABLED,
    val detail: String? = null,
)

internal fun normalizeYouTubePoTokenProviderUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val parsed = trimmed.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https") return null
    if (parsed.username.isNotBlank() || parsed.password.isNotBlank()) return null
    if (parsed.query != null || parsed.fragment != null) return null
    return parsed.toString().trimEnd('/')
}

internal fun applyYouTubePoTokenOptions(
    request: YoutubeDLRequest,
    pluginDirectory: File,
    providerBaseUrl: String,
) {
    request.addOption("--plugin-dirs", pluginDirectory.absolutePath)
    request.addOption(
        "--extractor-args",
        "youtube:player_client=mweb,default;fetch_pot=always",
    )
    request.addOption(
        "--extractor-args",
        "youtubepot-bgutilhttp:base_url=$providerBaseUrl",
    )
}

@Singleton
class YouTubeYtDlpRequestFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val clashProxyManager: ClashProxyManager,
) {
    private val installMutex = Mutex()
    private val _providerStatus = MutableStateFlow(YouTubePoTokenProviderStatus())
    val providerStatus: StateFlow<YouTubePoTokenProviderStatus> = _providerStatus.asStateFlow()

    suspend fun create(url: String): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        val rawProviderUrl = prefs.youtubePoTokenProviderUrl.first()
        val providerUrl = normalizeYouTubePoTokenProviderUrl(rawProviderUrl)
        if (providerUrl == "") {
            _providerStatus.value = YouTubePoTokenProviderStatus()
            return request
        }
        if (providerUrl == null) {
            _providerStatus.value = YouTubePoTokenProviderStatus(
                state = YouTubePoTokenProviderState.INVALID_CONFIGURATION,
                detail = "PO token provider must be an HTTPS URL without credentials, query, or fragment",
            )
            return request
        }

        return try {
            val pluginDirectory = ensurePluginInstalled()
            applyYouTubePoTokenOptions(request, pluginDirectory, providerUrl)
            // Apply Clash proxy to yt-dlp subprocess requests
            val proxyAddr = clashProxyManager.proxyAddress()
            if (proxyAddr != null) {
                request.addOption("--proxy", "http://${proxyAddr.hostString}:${proxyAddr.port}")
            }
            _providerStatus.value = YouTubePoTokenProviderStatus(YouTubePoTokenProviderState.READY)
            request
        } catch (error: Exception) {
            _providerStatus.value = YouTubePoTokenProviderStatus(
                state = YouTubePoTokenProviderState.INSTALL_FAILED,
                detail = error.message,
            )
            request
        }
    }

    private suspend fun ensurePluginInstalled(): File = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val pluginDirectory = File(context.noBackupFilesDir, PLUGIN_DIRECTORY_NAME)
            val pluginFile = File(pluginDirectory, PLUGIN_FILE_NAME)
            if (pluginFile.isFile && pluginFile.sha256() == PLUGIN_SHA256) {
                return@withLock pluginDirectory
            }

            pluginDirectory.mkdirs()
            if (!pluginDirectory.isDirectory) {
                throw IOException("Could not create the yt-dlp plugin directory")
            }
            val stagedFile = File(pluginDirectory, "$PLUGIN_FILE_NAME.tmp")
            try {
                context.resources.openRawResource(R.raw.bgutil_ytdlp_pot_provider).use { input ->
                    stagedFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (stagedFile.sha256() != PLUGIN_SHA256) {
                    throw IOException("Bundled PO token provider failed its SHA-256 check")
                }
                try {
                    Files.move(
                        stagedFile.toPath(),
                        pluginFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: Exception) {
                    Files.move(
                        stagedFile.toPath(),
                        pluginFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                stagedFile.delete()
            }
            pluginDirectory
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val PLUGIN_DIRECTORY_NAME = "yt-dlp-plugins"
        const val PLUGIN_FILE_NAME = "bgutil-ytdlp-pot-provider-1.3.1.zip"
        const val PLUGIN_SHA256 = "b8ceec7f76143da172aaf5ebeec0c2d218e5680c063b931586bca48567069b38"
    }
}
