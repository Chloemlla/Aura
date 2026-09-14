package com.freevibe.service

import android.content.Context
import com.freevibe.data.local.DownloadDao
import com.freevibe.data.local.FavoriteDao
import com.freevibe.data.model.DownloadEntity
import com.freevibe.data.model.FavoriteEntity
import com.freevibe.data.model.LocalMediaStatus
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathBackedRecordReconcilerTest {

    @Test
    fun `blank paths stay available`() {
        val blank = probePathBackedRecord("", { missingProbe() }, { missingProbe() })
        val spaces = probePathBackedRecord("   ", { missingProbe() }, { missingProbe() })

        assertEquals(LocalMediaStatus.AVAILABLE, blank.status)
        assertEquals(LocalMediaStatus.AVAILABLE, spaces.status)
    }

    @Test
    fun `filesystem probe distinguishes available missing and corrupt`() {
        val available = probePathBackedRecord("/tmp/existing.jpg", { availableProbe() }, { missingProbe() })
        val missing = probePathBackedRecord("/tmp/missing.jpg", { missingProbe() }, { missingProbe() })
        val corrupt = probePathBackedRecord("/tmp/corrupt.jpg", { corruptProbe() }, { missingProbe() })

        assertEquals(LocalMediaStatus.AVAILABLE, available.status)
        assertEquals(LocalMediaStatus.MISSING, missing.status)
        assertEquals(LocalMediaStatus.CORRUPT, corrupt.status)
        assertTrue(missing.reason!!.contains("missing", ignoreCase = true))
        assertTrue(corrupt.reason!!.contains("corrupt", ignoreCase = true))
    }

    @Test
    fun `file uri resolves before probing filesystem`() {
        val checked = mutableListOf<String>()
        val exists = pathBackedRecordExists(
            rawPath = "file:///tmp/aura/restored.jpg",
            fileExists = {
                checked += it
                it == "/tmp/aura/restored.jpg"
            },
            contentUriExists = { false },
        )

        assertTrue(exists)
        assertEquals(listOf("/tmp/aura/restored.jpg"), checked)
    }

    @Test
    fun `content uri delegates to content probe`() {
        val checked = mutableListOf<String>()
        val exists = pathBackedRecordExists(
            rawPath = "content://media/external/images/media/42",
            fileExists = { false },
            contentUriExists = {
                checked += it
                true
            },
        )

        assertTrue(exists)
        assertEquals(listOf("content://media/external/images/media/42"), checked)
    }

    @Test
    fun `content probe preserves permission revoked diagnosis`() {
        val probe = probePathBackedRecord(
            rawPath = "content://documents/tree/old",
            fileProbe = { availableProbe() },
            contentUriProbe = { revokedProbe() },
        )

        assertEquals(LocalMediaStatus.PERMISSION_REVOKED, probe.status)
        assertTrue(probe.reason!!.contains("access", ignoreCase = true))
    }

    @Test
    fun `unsupported locator is diagnosed as corrupt`() {
        val probe = probePathBackedRecord(
            rawPath = "ftp://example.test/media.jpg",
            fileProbe = { availableProbe() },
            contentUriProbe = { availableProbe() },
        )

        assertEquals(LocalMediaStatus.CORRUPT, probe.status)
    }

    @Test
    fun `reconcile marks missing records without clearing their locators`() = runTest {
        val existing = File.createTempFile("aura-existing", ".dat").apply { writeText("ok") }
        val missing = File(existing.parentFile, "aura-missing-${System.nanoTime()}.dat")
        val favoriteDao = mockk<FavoriteDao>(relaxed = true)
        val downloadDao = mockk<DownloadDao>(relaxed = true)

        every { favoriteDao.getAll() } returns flowOf(
            listOf(
                favorite("fav-existing", existing.absolutePath),
                favorite("fav-missing", missing.absolutePath),
                favorite("fav-blank", ""),
            )
        )
        every { downloadDao.getAll() } returns flowOf(
            listOf(
                download("download-existing", existing.absolutePath),
                download("download-missing", missing.absolutePath),
                download("download-blank", ""),
            )
        )

        val result = PathBackedRecordReconciler(
            context = mockk<Context>(relaxed = true),
            favoriteDao = favoriteDao,
            downloadDao = downloadDao,
        ).reconcile()

        assertEquals(PathBackedRecordReconciliationResult(favoritesUpdated = 1, downloadsUpdated = 1), result)
        coVerify(exactly = 1) {
            favoriteDao.updateLocalMediaStatus(
                "fav-missing",
                "WALLHAVEN",
                "WALLPAPER",
                LocalMediaStatus.MISSING,
                "Local file is missing",
            )
        }
        coVerify(exactly = 1) {
            downloadDao.updateLocalMediaStatus(
                "download-missing",
                LocalMediaStatus.MISSING,
                "Local file is missing",
            )
        }
        coVerify(exactly = 0) { favoriteDao.updateOfflinePath(any(), any(), any(), any()) }
        coVerify(exactly = 0) { downloadDao.updateLocalPath(any(), any()) }

        existing.delete()
    }

    @Test
    fun `startup and UI contracts keep missing files visible`() {
        val app = File("src/main/java/com/freevibe/FreeVibeApp.kt").readText()
        val database = File("src/main/java/com/freevibe/data/local/Database.kt").readText()
        val downloads = File("src/main/java/com/freevibe/ui/screens/downloads/DownloadsScreen.kt").readText()

        assertTrue(app.contains("lateinit var pathBackedRecordReconciler"))
        assertTrue(app.contains("pathBackedRecordReconciler.reconcile()"))
        assertTrue(database.contains("suspend fun updateLocalMediaStatus"))
        assertTrue(downloads.contains("needsLocalMediaRelink()"))
        assertTrue(downloads.contains("media_relink_action"))
    }

    private fun favorite(id: String, offlinePath: String): FavoriteEntity = FavoriteEntity(
        id = id,
        source = "WALLHAVEN",
        type = "WALLPAPER",
        thumbnailUrl = "https://example.test/thumb.jpg",
        fullUrl = "https://example.test/full.jpg",
        offlinePath = offlinePath,
    )

    private fun download(id: String, localPath: String): DownloadEntity = DownloadEntity(
        id = id,
        source = "WALLHAVEN",
        type = "WALLPAPER",
        localPath = localPath,
        name = id,
    )
}
