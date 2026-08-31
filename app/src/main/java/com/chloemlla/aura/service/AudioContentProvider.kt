package com.chloemlla.aura.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.chloemlla.aura.data.model.Sound
import com.chloemlla.aura.data.model.stableKey
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException

class AudioContentProvider : ContentProvider() {

    private lateinit var bundledContentProvider: BundledContentProvider
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var cacheDir: File

    override fun onCreate(): Boolean {
        val context = context ?: return false
        val entryPoint = EntryPoints.get(context.applicationContext, AudioContentProviderEntryPoint::class.java)
        bundledContentProvider = entryPoint.bundledContentProvider()
        okHttpClient = entryPoint.okHttpClient()
        cacheDir = File(context.cacheDir, "audio_provider").also { it.mkdirs() }
        return true
    }

    override fun getType(uri: Uri): String {
        if (!isTrustedCaller()) return "audio/*"
        return when (URI_MATCHER.match(uri)) {
            SOUND, SOUND_PREVIEW, SOUND_DOWNLOAD -> "audio/*"
            CATEGORY -> "vnd.android.cursor.dir/vnd.freevibe.sound"
            else -> null
        } ?: "audio/*"
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        if (!isTrustedCaller()) return null
        val match = URI_MATCHER.match(uri)
        val sounds = when (match) {
            SOUND, SOUND_PREVIEW, SOUND_DOWNLOAD -> {
                val stableKey = uri.lastPathSegment ?: return null
                listOfNotNull(resolveSound(stableKey))
            }
            CATEGORY -> {
                val category = uri.lastPathSegment ?: return null
                resolveCategorySounds(category)
            }
            else -> return null
        }
        val cursor = MatrixCursor(SOUND_COLUMNS)
        sounds.forEach { sound ->
            cursor.addRow(
                soundRow(sound)
            )
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!isTrustedCaller()) {
            Log.w(TAG, "Blocked openFile from untrusted caller")
            throw SecurityException("Caller not authorized")
        }
        if (mode != "r" && mode != "rt") {
            throw SecurityException("Only read-only access is supported")
        }
        val sound = resolveSound(uri) ?: throw FileNotFoundException("Unknown sound: $uri")
        val url = resolveUrl(sound, uri) ?: throw FileNotFoundException("No URL for sound: ${sound.stableKey()}")

        val cacheFile = getCacheFile(sound.stableKey())
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Download to a temp file first: renameTo publishes the entry only once the
        // whole body is on disk, so an interrupted transfer can never be mistaken for
        // a valid cache entry. The copy itself is size-capped.
        val tempFile = File.createTempFile("download", ".part", cacheDir)
        try {
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw FileNotFoundException(RequestRedactor.formatRequest("GET", url, response.code))
                }
                response.body?.byteStream()?.use { input ->
                    tempFile.outputStream().use { output ->
                        copyStreamCapped(input, output, MAX_SOUND_BYTES)
                    }
                } ?: throw FileNotFoundException("Empty response body for ${RequestRedactor.redactUrl(url)}")
            }
            if (!tempFile.renameTo(cacheFile)) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }

        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    // --- trusted caller check ---

    private fun isTrustedCaller(): Boolean {
        val context = context ?: return false
        val uid = Binder.getCallingUid()
        // Real signature comparison, not a package-name prefix check: a package name
        // starting with "com.chloemlla." is freely choosable by any app.
        val allowed = uid == Process.myUid() ||
            context.packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
        if (!allowed && com.chloemlla.aura.BuildConfig.DEBUG) {
            Log.w(TAG, "Blocked untrusted caller uid=$uid")
        }
        return allowed
    }

    // --- helpers ---

    private fun resolveSound(stableKey: String): Sound? =
        allBundledSounds().find { it.stableKey() == stableKey }

    private fun resolveSound(uri: Uri): Sound? {
        val path = uri.pathSegments
        // content://authority/sound/{stableKey}[/preview|download]
        if (path.size >= 2 && path[0] == "sound") {
            return resolveSound(path[1])
        }
        return null
    }

    private fun resolveUrl(sound: Sound, uri: Uri): String? {
        val path = uri.pathSegments
        if (path.size >= 3 && path.getOrNull(2) == "download") {
            return sound.downloadUrl.ifBlank { sound.previewUrl }
        }
        if (path.size >= 3 && path.getOrNull(2) == "preview") {
            return sound.previewUrl.ifBlank { sound.downloadUrl }
        }
        return sound.previewUrl.ifBlank { sound.downloadUrl }
    }

    private fun resolveCategorySounds(category: String): List<Sound> = when (category) {
        "ringtone" -> bundledContentProvider.getRingtones()
        "notification" -> bundledContentProvider.getNotifications()
        "alarm" -> bundledContentProvider.getAlarms()
        "all" -> allBundledSounds()
        else -> emptyList()
    }

    private fun allBundledSounds(): List<Sound> =
        bundledContentProvider.getRingtones() +
            bundledContentProvider.getNotifications() +
            bundledContentProvider.getAlarms()

    private fun getCacheFile(stableKey: String): File {
        pruneCacheIfNeeded()
        return File(cacheDir, "${stableKey.replace("::", "_")}.mp3")
    }

    private fun pruneCacheIfNeeded() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun soundRow(sound: Sound): Array<Any?> = arrayOf(
        sound.stableKey(),
        sound.name,
        sound.description,
        sound.duration,
        sound.uploaderName,
        sound.license,
        sound.source.name,
        sound.previewUrl,
        sound.downloadUrl,
    )

    companion object {
        private const val TAG = "AudioContentProvider"
        private const val MAX_SOUND_BYTES = 64L * 1024L * 1024L
        private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
        private const val SOUND = 1
        private const val SOUND_PREVIEW = 2
        private const val SOUND_DOWNLOAD = 3
        private const val CATEGORY = 4

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI("com.chloemlla.aura.audioprovider", "sound/*", SOUND)
            addURI("com.chloemlla.aura.audioprovider", "sound/*/preview", SOUND_PREVIEW)
            addURI("com.chloemlla.aura.audioprovider", "sound/*/download", SOUND_DOWNLOAD)
            addURI("com.chloemlla.aura.audioprovider", "category/*", CATEGORY)
        }

        private val SOUND_COLUMNS = arrayOf(
            "_id", "name", "description", "duration", "uploader",
            "license", "source", "preview_url", "download_url"
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioContentProviderEntryPoint {
    fun bundledContentProvider(): BundledContentProvider
    fun okHttpClient(): OkHttpClient
}
