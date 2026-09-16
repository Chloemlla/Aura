package com.chloemlla.aura.service

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.chloemlla.aura.data.local.DownloadDao
import com.chloemlla.aura.data.model.DownloadEntity
import com.chloemlla.aura.data.model.MediaTechnicalMetadata
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaCopyStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reapply reuses a valid derivative without rewriting it`() = runTest {
        val fixture = fixture()
        var writerCalls = 0

        val first = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = fixture.original.originalSha256,
            optimizationKey = "fit-key",
            reason = "Fit Canvas video",
            extension = "mp4",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { output ->
            writerCalls += 1
            output.writeBytes(byteArrayOf(9, 8, 7, 6))
        }
        val second = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = fixture.original.originalSha256,
            optimizationKey = "fit-key",
            reason = "Fit Canvas video",
            extension = "mp4",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { output ->
            writerCalls += 1
            output.writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        assertFalse(first.reused)
        assertTrue(second.reused)
        assertEquals(first.file.canonicalPath, second.file.canonicalPath)
        assertEquals(1, writerCalls)
        assertEquals(byteArrayOf(9, 8, 7, 6).toList(), second.file.readBytes().toList())
    }

    @Test
    fun `tampered derivative is regenerated while original hash stays stable`() = runTest {
        val fixture = fixture()
        val originalHash = sha256File(fixture.originalFile)
        var writerCalls = 0

        val first = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = originalHash,
            optimizationKey = "codec-key",
            reason = "Compatible H.264 MP4",
            extension = "mp4",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { output ->
            writerCalls += 1
            output.writeBytes(byteArrayOf(5, 4, 3, 2))
        }
        first.file.writeBytes(byteArrayOf(0))
        val regenerated = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = originalHash,
            optimizationKey = "codec-key",
            reason = "Compatible H.264 MP4",
            extension = "mp4",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { output ->
            writerCalls += 1
            output.writeBytes(byteArrayOf(1, 3, 5, 7))
        }

        assertFalse(regenerated.reused)
        assertEquals(2, writerCalls)
        assertEquals(originalHash, sha256File(fixture.originalFile))
        assertNotEquals(originalHash, sha256File(regenerated.file))
    }

    @Test
    fun `deleting optimized copy clears its record and never removes original`() = runTest {
        val fixture = fixture()
        val prepared = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = fixture.original.originalSha256,
            optimizationKey = "edit-key",
            reason = "Edited sound",
            extension = "m4a",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { it.writeBytes(byteArrayOf(2, 4, 6, 8)) }

        assertTrue(fixture.store.deleteOptimizedCopy(fixture.original.id))
        assertFalse(prepared.file.exists())
        assertTrue(fixture.originalFile.exists())
        assertTrue(fixture.current().optimizedPath.isBlank())
        assertEquals(fixture.original.originalSha256, sha256File(fixture.originalFile))
    }

    @Test
    fun `failed optimized deletion restores its file and database reference`() = runTest {
        val fixture = fixture()
        val prepared = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = fixture.original.originalSha256,
            optimizationKey = "delete-key",
            reason = "Edited sound",
            extension = "m4a",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { it.writeBytes(byteArrayOf(2, 4, 6, 8)) }
        coEvery { fixture.dao.insert(match { it.optimizedPath.isBlank() }) } throws
            IOException("database unavailable")

        try {
            fixture.store.deleteOptimizedCopy(fixture.original.id)
            throw AssertionError("database failure must reject deletion")
        } catch (error: IOException) {
            assertEquals("database unavailable", error.message)
        }

        assertTrue(prepared.file.exists())
        assertEquals(prepared.file.absolutePath, fixture.current().optimizedPath)
        assertTrue(fixture.originalFile.exists())
    }

    @Test
    fun `updating original provenance preserves matching derivative and original bytes`() = runTest {
        val fixture = fixture()
        val prepared = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = fixture.original.originalSha256,
            optimizationKey = "old-key",
            reason = "Oversized wallpaper",
            extension = "jpg",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { it.writeBytes(byteArrayOf(8, 6, 4, 2)) }

        val recorded = fixture.store.recordOriginal(
            id = fixture.original.id,
            source = "REDDIT",
            type = "VIDEO",
            locator = fixture.originalFile.absolutePath,
            name = "Saved loop",
            provenanceUrl = "https://www.reddit.com/r/wallpapers/comments/example",
            sha256 = fixture.original.originalSha256,
            metadata = MediaTechnicalMetadata(sizeBytes = fixture.originalFile.length()),
        )

        assertTrue(prepared.file.exists())
        assertTrue(fixture.originalFile.exists())
        assertEquals("https://www.reddit.com/r/wallpapers/comments/example", recorded.provenanceUrl)
        assertEquals(fixture.original.originalSha256, recorded.originalSha256)
    }

    @Test
    fun `replacing a derivative deletes only the previous derivative`() = runTest {
        val fixture = fixture()
        val first = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = fixture.original.originalSha256,
            optimizationKey = "first-key",
            reason = "Oversized wallpaper",
            extension = "jpg",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { it.writeBytes(byteArrayOf(1, 1, 1, 1)) }
        val replacement = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = fixture.original.originalSha256,
            optimizationKey = "second-key",
            reason = "Wallpaper transform",
            extension = "png",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { it.writeBytes(byteArrayOf(2, 2, 2, 2)) }

        assertFalse(first.file.exists())
        assertTrue(replacement.file.exists())
        assertTrue(fixture.originalFile.exists())
        assertEquals(fixture.original.originalSha256, sha256File(fixture.originalFile))
    }

    @Test
    fun `low storage rejection leaves the original byte identical`() {
        val original = temporaryFolder.newFile("original.jpg").apply {
            writeBytes(byteArrayOf(11, 22, 33, 44))
        }
        val before = sha256File(original)

        try {
            requireApplyCopyCapacity(usableBytes = 32, expectedBytes = 64, reserveBytes = 16)
            throw AssertionError("low storage must reject the optimized copy")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("original was kept unchanged"))
        }

        assertEquals(before, sha256File(original))
    }

    @Test
    fun `failed optimized writer rolls back pending bytes and keeps original`() = runTest {
        val fixture = fixture()
        val before = sha256File(fixture.originalFile)

        try {
            fixture.store.prepareCopy(
                downloadId = fixture.original.id,
                sourceIdentity = before,
                optimizationKey = "failed-key",
                reason = "Edited sound",
                extension = "m4a",
                expectedBytes = 4,
                maxBytes = 1_024,
            ) { output ->
                output.writeBytes(byteArrayOf(9, 9, 9, 9))
                throw IOException("encoder stopped")
            }
            throw AssertionError("failed output must not be committed")
        } catch (error: IOException) {
            assertEquals("encoder stopped", error.message)
        }

        assertEquals(before, sha256File(fixture.originalFile))
        assertTrue(
            File(fixture.originalFile.parentFile, MediaCopyStore.APPLY_COPY_DIRECTORY)
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
        assertTrue(fixture.current().optimizedPath.isBlank())
    }

    @Test
    fun `failed database replacement retains prior derivative and original`() = runTest {
        val fixture = fixture()
        val originalHash = fixture.original.originalSha256
        val first = fixture.store.prepareCopy(
            downloadId = fixture.original.id,
            sourceIdentity = originalHash,
            optimizationKey = "first-key",
            reason = "Oversized wallpaper",
            extension = "jpg",
            expectedBytes = 4,
            maxBytes = 1_024,
        ) { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        coEvery { fixture.dao.insert(match { it.optimizationKey == "failed-key" }) } throws
            IOException("database full")

        try {
            fixture.store.prepareCopy(
                downloadId = fixture.original.id,
                sourceIdentity = originalHash,
                optimizationKey = "failed-key",
                reason = "Wallpaper transform",
                extension = "png",
                expectedBytes = 4,
                maxBytes = 1_024,
            ) { it.writeBytes(byteArrayOf(4, 3, 2, 1)) }
            throw AssertionError("database failure must reject the replacement")
        } catch (error: IOException) {
            assertEquals("database full", error.message)
        }

        assertTrue(first.file.exists())
        assertEquals(first.file.absolutePath, fixture.current().optimizedPath)
        assertEquals(originalHash, sha256File(fixture.originalFile))
        assertEquals(1, first.file.parentFile?.listFiles()?.count { !it.name.startsWith(".") })
    }

    @Test
    fun `stale source identity is rejected before the writer runs`() = runTest {
        val fixture = fixture()
        var writerCalled = false

        try {
            fixture.store.prepareCopy(
                downloadId = fixture.original.id,
                sourceIdentity = "stale-hash",
                optimizationKey = "stale-key",
                reason = "Compatible H.264 MP4",
                extension = "mp4",
                expectedBytes = 4,
                maxBytes = 1_024,
            ) {
                writerCalled = true
                it.writeBytes(byteArrayOf(9, 9, 9, 9))
            }
            throw AssertionError("stale source identity must be rejected")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("identity"))
        }

        assertFalse(writerCalled)
        assertEquals(fixture.original.originalSha256, sha256File(fixture.originalFile))
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder("media-copy-${System.nanoTime()}")
        val originalFile = File(root, "original.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val original = DownloadEntity(
            id = "saved-original",
            source = "REDDIT",
            type = "VIDEO",
            localPath = originalFile.absolutePath,
            name = "Saved loop",
            originalSha256 = sha256File(originalFile),
            originalSizeBytes = originalFile.length(),
        )
        var current = original
        val dao = mockk<DownloadDao>()
        coEvery { dao.getById(original.id) } answers { current }
        coEvery { dao.insert(any()) } answers {
            current = firstArg()
        }
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        return Fixture(MediaCopyStore(context, dao), dao, original, originalFile) { current }
    }

    private data class Fixture(
        val store: MediaCopyStore,
        val dao: DownloadDao,
        val original: DownloadEntity,
        val originalFile: File,
        val current: () -> DownloadEntity,
    )
}
