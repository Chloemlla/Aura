package com.chloemlla.aura.service

import android.content.Context
import androidx.room.Room
import com.chloemlla.aura.data.local.FreeVibeDatabase
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.data.model.LocalWallpaperEntity
import com.chloemlla.aura.data.model.LocalWallpaperFolderEntity
import com.chloemlla.aura.data.model.LocalWallpaperFolderScanStatus
import com.chloemlla.aura.data.model.LocalMediaStatus
import com.chloemlla.aura.data.model.WallpaperTarget
import com.chloemlla.aura.data.model.normalizeLocalWallpaperTags
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalWallpaperCatalogTest {
    private lateinit var database: FreeVibeDatabase
    private lateinit var catalog: LocalWallpaperCatalog

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, FreeVibeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = LocalWallpaperCatalog(context, database, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rotation filters folder target and removes identical content`() = runTest {
        val homeFolder = folder("content://example/home", WallpaperTarget.HOME)
        val lockFolder = folder("content://example/lock", WallpaperTarget.LOCK)
        database.localWallpaperFolderDao().upsert(homeFolder)
        database.localWallpaperFolderDao().upsert(lockFolder)
        database.localWallpaperDao().upsertAll(
            listOf(
                item("content://example/home/one", homeFolder.folderUri, "one.jpg", "same-hash"),
                item("content://example/home/two", homeFolder.folderUri, "two.jpg", "same-hash"),
                item("content://example/lock/three", lockFolder.folderUri, "three.jpg", "lock-hash"),
            ),
        )

        val home = catalog.rotationWallpapers(WallpaperTarget.HOME)
        assertEquals(1, home.size)
        assertEquals(ContentSource.LOCAL, home.single().source)
        assertEquals("content://example/home/one", home.single().fullUrl)

        val lock = catalog.rotationWallpapers(WallpaperTarget.LOCK)
        assertEquals(listOf("content://example/lock/three"), lock.map { it.fullUrl })
        assertEquals(2, catalog.rotationWallpapers().size)
    }

    @Test
    fun `rotation keeps tags and local metadata`() = runTest {
        val folder = folder("content://example/folder", WallpaperTarget.BOTH)
        database.localWallpaperFolderDao().upsert(folder)
        database.localWallpaperDao().upsertAll(
            listOf(item("content://example/folder/a", folder.folderUri, "a.png", "hash-a").copy(tags = "nature, blue")),
        )

        val wallpaper = catalog.rotationWallpapers().single()
        assertEquals(listOf("nature", "blue"), wallpaper.tags)
        assertEquals(123L, wallpaper.fileSize)
        assertEquals("image/png", wallpaper.fileType)
        assertEquals(folder.folderUri, wallpaper.sourcePageUrl)
    }

    @Test
    fun `tag normalization removes blanks and case insensitive duplicates`() {
        val tags = normalizeLocalWallpaperTags("nature, blue\nNature, , night, blue")

        assertEquals("nature, blue, night", tags)
    }

    @Test
    fun `local image detection accepts bounded image formats only`() {
        assertTrue(isLocalWallpaperImage("photo.jpg", null))
        assertTrue(isLocalWallpaperImage("portrait.WEBP", "application/octet-stream"))
        assertTrue(isLocalWallpaperImage("wallpaper.avif", null))
        assertTrue(isLocalWallpaperImage("photo.bin", "image/png"))
        assertFalse(isLocalWallpaperImage("Pictures", "vnd.android.document/directory"))
        assertFalse(isLocalWallpaperImage("clip.mp4", "video/mp4"))
        assertFalse(isLocalWallpaperImage("notes.txt", null))
    }

    @Test
    fun `content identity hashes beyond the former 64 MiB boundary`() {
        val boundary = 64L * 1024L * 1024L

        val atBoundary = hashLocalWallpaperStream(RepeatingInputStream(boundary))
        val beyondBoundary = hashLocalWallpaperStream(RepeatingInputStream(boundary + 1))

        assertEquals(64, beyondBoundary.length)
        assertNotEquals(atBoundary, beyondBoundary)
    }

    @Test
    fun `folder repair matches exact hashes once and ignores corrupt candidates`() {
        val oldOne = item("content://old/one", "content://old", "one.png", "same")
        val oldTwo = item("content://old/two", "content://old", "two.png", "same")
        val corrupt = item("content://new/corrupt", "content://new", "one.png", "same")
            .copy(localMediaStatus = LocalMediaStatus.CORRUPT)
        val available = item("content://new/good", "content://new", "one.png", "same")

        val matches = matchLocalWallpaperFolderRepairCandidates(
            oldItems = listOf(oldOne, oldTwo),
            candidates = listOf(
                item("content://new/no-hash", "content://new", "one.png", ""),
                item("content://new/wrong", "content://new", "one.png", "different"),
                corrupt,
                available,
            ),
            maxItems = 500,
        )

        assertEquals(1, matches.size)
        assertEquals(oldOne.documentUri, matches.single().first.documentUri)
        assertEquals(available.documentUri, matches.single().second.documentUri)
    }

    @Test
    fun `folder repair batch is bounded`() {
        val oldItems = (0..500).map { index ->
            item("content://old/$index", "content://old", "$index.png", "hash-$index")
        }
        val candidates = (0..500).map { index ->
            item("content://new/$index", "content://new", "$index.png", "hash-$index")
        }

        val matches = matchLocalWallpaperFolderRepairCandidates(oldItems, candidates, maxItems = 500)

        assertEquals(500, matches.size)
        assertFalse(matches.any { it.first.documentUri == "content://old/500" })
    }

    @Test
    fun `revoked folder status does not invalidate an individually relinked file`() = runTest {
        val relinked = item("content://new/photo", "content://old", "photo.png", "same")
            .copy(isStandalone = true)
        database.localWallpaperDao().upsertAll(listOf(relinked))

        database.localWallpaperDao().updateFolderMediaStatus(
            relinked.folderUri,
            LocalMediaStatus.PERMISSION_REVOKED,
            "Folder permission was revoked",
        )

        assertEquals(LocalMediaStatus.AVAILABLE, database.localWallpaperDao().get(relinked.documentUri)!!.localMediaStatus)
    }

    @Test
    fun `folder repair does not take over a locator owned by another catalog identity`() {
        val old = item("content://old/photo", "content://old", "photo.png", "same")
            .copy(stableId = "old-stable-id")
        val candidate = item("content://new/photo", "content://new", "photo.png", "same")
        val occupied = candidate.copy(stableId = "different-stable-id")

        val matches = matchLocalWallpaperFolderRepairCandidates(
            oldItems = listOf(old),
            candidates = listOf(candidate),
            maxItems = 500,
            occupiedCandidates = mapOf(candidate.documentUri to occupied),
        )

        assertTrue(matches.isEmpty())
    }

    private fun folder(uri: String, target: WallpaperTarget) = LocalWallpaperFolderEntity(
        folderUri = uri,
        displayName = uri.substringAfterLast('/'),
        target = target.name,
        scanStatus = LocalWallpaperFolderScanStatus.READY,
    )

    private fun item(uri: String, folderUri: String, name: String, hash: String) = LocalWallpaperEntity(
        documentUri = uri,
        folderUri = folderUri,
        documentId = name,
        displayName = name,
        mimeType = "image/png",
        sizeBytes = 123L,
        modifiedAt = 456L,
        contentHash = hash,
    )

    private class RepeatingInputStream(private val length: Long) : InputStream() {
        private var position = 0L

        override fun read(): Int = if (position++ < length) 0x5a else -1

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (position >= length) return -1
            val read = minOf(count.toLong(), length - position).toInt()
            buffer.fill(0x5a.toByte(), offset, offset + read)
            position += read
            return read
        }
    }
}
