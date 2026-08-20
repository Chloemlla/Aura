package com.freevibe.service

import android.content.Context
import androidx.room.Room
import com.freevibe.data.local.FreeVibeDatabase
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.LocalWallpaperEntity
import com.freevibe.data.model.LocalWallpaperFolderEntity
import com.freevibe.data.model.LocalWallpaperFolderScanStatus
import com.freevibe.data.model.WallpaperTarget
import com.freevibe.data.model.normalizeLocalWallpaperTags
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        catalog = LocalWallpaperCatalog(context, database)
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
}
