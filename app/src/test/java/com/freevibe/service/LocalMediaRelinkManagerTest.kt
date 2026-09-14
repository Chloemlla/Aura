package com.freevibe.service

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import com.freevibe.data.local.FreeVibeDatabase
import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.DownloadEntity
import com.freevibe.data.model.FavoriteEntity
import com.freevibe.data.model.LocalMediaStatus
import com.freevibe.data.model.LocalWallpaperEntity
import com.freevibe.data.model.LocalWallpaperFolderEntity
import com.freevibe.data.model.LocalWallpaperFolderScanStatus
import com.freevibe.data.model.MediaTechnicalMetadata
import com.freevibe.data.model.RotationExclusionIndex
import com.freevibe.data.model.WallpaperCollectionEntity
import com.freevibe.data.model.WallpaperCollectionItemEntity
import com.freevibe.data.model.WallpaperHistoryEntity
import com.freevibe.data.model.WallpaperTarget
import com.freevibe.data.model.rotationIdentity
import com.freevibe.data.model.toRotationExclusion
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMediaRelinkManagerTest {
    private lateinit var database: FreeVibeDatabase
    private lateinit var manager: LocalMediaRelinkManager
    private lateinit var preferences: PreferencesManager
    private lateinit var candidateFile: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, FreeVibeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = mockk<PreferencesManager>(relaxed = true).also {
            every { it.darkModeWallpaperId } returns flowOf("")
            every { it.lightModeWallpaperId } returns flowOf("")
        }
        manager = LocalMediaRelinkManager(
            context = context,
            database = database,
            preferencesManager = preferences,
            mediaCopyStore = mockk(relaxed = true),
        )
        candidateFile = File(context.cacheDir, "relink-candidate-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try {
            candidateFile.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        database.close()
        candidateFile.delete()
    }

    @Test
    fun `exact hash is accepted even when stored metadata is stale`() {
        val expected = expectation(
            sha256 = "ab".repeat(32),
            sizeBytes = 100,
            width = 100,
            height = 200,
            durationMs = 9_000,
        )
        val candidate = candidate(
            sha256 = "AB".repeat(32),
            metadata = metadata(size = 200, width = 300, height = 400, duration = 20_000),
        )

        assertTrue(localMediaRelinkDifferences(expected, candidate).isEmpty())
    }

    @Test
    fun `replacement warning reports every meaningful technical difference`() {
        val expected = expectation(
            sha256 = "ab".repeat(32),
            mimeType = "image/png",
            sizeBytes = 100,
            width = 100,
            height = 200,
            durationMs = 10_000,
        )
        val candidate = candidate(
            sha256 = "cd".repeat(32),
            metadata = metadata(
                mime = "video/mp4",
                size = 200,
                width = 300,
                height = 400,
                duration = 12_000,
            ),
        )

        assertEquals(
            listOf("content hash", "file size", "dimensions", "duration", "media type"),
            localMediaRelinkDifferences(expected, candidate),
        )
    }

    @Test
    fun `duration differences inside five percent tolerance do not warn`() {
        val differences = localMediaRelinkDifferences(
            expectation(durationMs = 20_000),
            candidate(metadata = metadata(duration = 20_900)),
        )

        assertTrue(differences.isEmpty())
    }

    @Test
    fun `decoded track type rejects audio containers as video and video files as sound`() {
        val container = SniffedMediaType(MediaFamily.CONTAINER, "video/webm", "webm")
        val audioHeader = SniffedMediaType(MediaFamily.AUDIO, "audio/mp4", "m4a")

        assertFalse(candidateMatchesMediaType("VIDEO", container, metadata(mime = "audio/webm", duration = 2_000)))
        assertFalse(candidateMatchesMediaType("SOUND", audioHeader, metadata(mime = "video/mp4", duration = 2_000)))
        assertTrue(candidateMatchesMediaType("SOUND", container, metadata(mime = "audio/webm", duration = 2_000)))
        assertTrue(candidateMatchesMediaType("VIDEO", audioHeader, metadata(mime = "video/mp4", duration = 2_000)))
    }

    @Test
    fun `mismatch changes nothing and accepted relink preserves every library association`() = runTest {
        val oldLocator = "content://missing/tree/old-photo"
        val stableId = "local-wallpaper-stable-id"
        every { preferences.darkModeWallpaperId } returns flowOf("LOCAL|$stableId|$oldLocator")
        every { preferences.lightModeWallpaperId } returns flowOf("LOCAL|$stableId|$oldLocator")
        val oldItem = LocalWallpaperEntity(
            documentUri = oldLocator,
            stableId = stableId,
            folderUri = "content://missing/tree",
            documentId = "old-photo",
            displayName = "kept-name.png",
            mimeType = "image/png",
            sizeBytes = 999,
            modifiedAt = 123,
            contentHash = "ab".repeat(32),
            width = 100,
            height = 200,
            localMediaStatus = LocalMediaStatus.MISSING,
            localMediaReason = "File is missing",
            tags = "kept, tags",
            addedAt = 456,
        )
        database.localWallpaperFolderDao().upsert(
            LocalWallpaperFolderEntity(
                folderUri = oldItem.folderUri,
                displayName = "Old folder",
                target = WallpaperTarget.LOCK.name,
                scanStatus = LocalWallpaperFolderScanStatus.PERMISSION_REVOKED,
            ),
        )
        database.localWallpaperDao().upsertAll(listOf(oldItem))
        database.favoriteDao().insert(
            FavoriteEntity(
                id = stableId,
                source = ContentSource.LOCAL.name,
                type = "WALLPAPER",
                thumbnailUrl = oldLocator,
                fullUrl = oldLocator,
                name = "Kept favorite",
                offlinePath = oldLocator,
                tags = "kept, tags",
                localMediaStatus = LocalMediaStatus.MISSING,
            ),
        )
        val collectionId = database.collectionDao().createCollection(WallpaperCollectionEntity(name = "Kept collection"))
        database.collectionDao().addItem(
            WallpaperCollectionItemEntity(
                collectionId = collectionId,
                wallpaperId = stableId,
                thumbnailUrl = "",
                fullUrl = "",
                source = ContentSource.LOCAL.name,
            ),
        )
        database.wallpaperHistoryDao().insert(
            WallpaperHistoryEntity(
                wallpaperId = stableId,
                source = ContentSource.LOCAL.name,
                thumbnailUrl = "",
                fullUrl = "",
                target = WallpaperTarget.LOCK.name,
            ),
        )
        database.downloadDao().insert(
            DownloadEntity(
                id = "associated-download",
                source = ContentSource.LOCAL.name,
                type = "WALLPAPER",
                localPath = oldLocator,
                name = "Kept download",
            ),
        )
        database.rotationExclusionDao().upsert(oldItem.rotationIdentity().toRotationExclusion(excludedAt = 789))

        val uri = Uri.fromFile(candidateFile)
        val review = manager.relink(LocalMediaRelinkTarget.CatalogItem(oldLocator), uri)

        assertTrue(review is LocalMediaRelinkOutcome.ReviewRequired)
        assertEquals(oldItem, database.localWallpaperDao().get(oldLocator))
        assertEquals(oldLocator, database.favoriteDao().getByIdentity(stableId, ContentSource.LOCAL.name, "WALLPAPER")!!.offlinePath)
        assertEquals(oldLocator, database.downloadDao().getById("associated-download")!!.localPath)

        val result = manager.relink(LocalMediaRelinkTarget.CatalogItem(oldLocator), uri, acceptMismatch = true)

        assertTrue(result is LocalMediaRelinkOutcome.Relinked)
        val newLocator = (result as LocalMediaRelinkOutcome.Relinked).locator
        assertNull(database.localWallpaperDao().get(oldLocator))
        val relinked = database.localWallpaperDao().get(newLocator)!!
        assertEquals(stableId, relinked.stableId)
        assertEquals("kept-name.png", relinked.displayName)
        assertEquals("kept, tags", relinked.tags)
        assertEquals(456, relinked.addedAt)
        assertEquals(LocalMediaStatus.AVAILABLE, relinked.localMediaStatus)
        assertTrue(relinked.isStandalone)

        val favorite = database.favoriteDao().getByIdentity(stableId, ContentSource.LOCAL.name, "WALLPAPER")!!
        assertEquals("Kept favorite", favorite.name)
        assertEquals(newLocator, favorite.offlinePath)
        assertEquals(newLocator, favorite.fullUrl)
        assertEquals(newLocator, favorite.thumbnailUrl)
        assertEquals("kept, tags", favorite.tags)
        assertEquals(newLocator, database.downloadDao().getById("associated-download")!!.localPath)
        assertEquals(newLocator, database.collectionDao().getCollectionItems(collectionId).first().single().fullUrl)
        assertEquals(newLocator, database.wallpaperHistoryDao().getRecentSnapshot(1).single().fullUrl)
        assertEquals(WallpaperTarget.LOCK.name, database.wallpaperHistoryDao().getRecentSnapshot(1).single().target)
        assertTrue(
            RotationExclusionIndex(database.rotationExclusionDao().getAll())
                .contains(relinked.rotationIdentity()),
        )
        coVerify(exactly = 1) {
            preferences.setDarkModeWallpaperId("LOCAL|$stableId|$newLocator")
        }
        coVerify(exactly = 1) {
            preferences.setLightModeWallpaperId("LOCAL|$stableId|$newLocator")
        }

        val catalog = LocalWallpaperCatalog(RuntimeEnvironment.getApplication(), database, manager)
        assertTrue(catalog.rotationWallpapers(WallpaperTarget.HOME).isEmpty())
        assertEquals(listOf(stableId), catalog.rotationWallpapers(WallpaperTarget.LOCK).map { it.id })
    }

    @Test
    fun `favorite restored without a locator relinks associations but not unrelated blank downloads`() = runTest {
        val stableId = "restored-local-favorite"
        database.favoriteDao().insert(
            FavoriteEntity(
                id = stableId,
                source = ContentSource.LOCAL.name,
                type = "WALLPAPER",
                thumbnailUrl = "",
                fullUrl = "",
                offlinePath = "",
                name = "Restored favorite",
                localMediaStatus = LocalMediaStatus.MISSING,
                localMediaSha256 = "ab".repeat(32),
            ),
        )
        val collectionId = database.collectionDao().createCollection(
            WallpaperCollectionEntity(name = "Restored collection"),
        )
        database.collectionDao().addItem(
            WallpaperCollectionItemEntity(
                collectionId = collectionId,
                wallpaperId = stableId,
                thumbnailUrl = "",
                fullUrl = "",
                source = ContentSource.LOCAL.name,
            ),
        )
        database.wallpaperHistoryDao().insert(
            WallpaperHistoryEntity(
                wallpaperId = stableId,
                source = ContentSource.LOCAL.name,
                thumbnailUrl = "",
                fullUrl = "",
                target = WallpaperTarget.HOME.name,
            ),
        )
        database.downloadDao().insert(
            DownloadEntity(
                id = "unrelated-blank-download",
                source = ContentSource.REDDIT.name,
                type = "WALLPAPER",
                localPath = "",
                name = "Unrelated",
            ),
        )

        val result = manager.relink(
            LocalMediaRelinkTarget.Favorite(stableId, ContentSource.LOCAL.name, "WALLPAPER"),
            Uri.fromFile(candidateFile),
            acceptMismatch = true,
        )

        assertTrue(result is LocalMediaRelinkOutcome.Relinked)
        val newLocator = (result as LocalMediaRelinkOutcome.Relinked).locator
        val favorite = database.favoriteDao().getByIdentity(stableId, ContentSource.LOCAL.name, "WALLPAPER")!!
        assertEquals(newLocator, favorite.offlinePath)
        assertEquals(newLocator, favorite.fullUrl)
        assertEquals(newLocator, favorite.thumbnailUrl)
        assertEquals(newLocator, database.collectionDao().getCollectionItems(collectionId).first().single().fullUrl)
        assertEquals(newLocator, database.wallpaperHistoryDao().getRecentSnapshot(1).single().fullUrl)
        assertEquals("", database.downloadDao().getById("unrelated-blank-download")!!.localPath)
    }

    @Test
    fun `relink refuses to overwrite a different catalog item at the selected locator`() = runTest {
        val oldLocator = "content://missing/tree/original"
        val candidateLocator = Uri.fromFile(candidateFile).toString()
        val original = LocalWallpaperEntity(
            documentUri = oldLocator,
            stableId = "original-stable-id",
            folderUri = "content://missing/tree",
            documentId = "original",
            displayName = "original.png",
            mimeType = "image/png",
            sizeBytes = 999,
            modifiedAt = 123,
            contentHash = "ab".repeat(32),
            width = 100,
            height = 200,
            localMediaStatus = LocalMediaStatus.MISSING,
            localMediaReason = "File is missing",
            addedAt = 456,
        )
        val itemAlreadyAtCandidate = LocalWallpaperEntity(
            documentUri = candidateLocator,
            stableId = "different-stable-id",
            folderUri = "content://other/tree",
            documentId = "candidate",
            displayName = "candidate.png",
            mimeType = "image/png",
            sizeBytes = candidateFile.length(),
            modifiedAt = 789,
            addedAt = 987,
        )
        database.localWallpaperDao().upsertAll(listOf(original, itemAlreadyAtCandidate))

        val result = manager.relink(
            LocalMediaRelinkTarget.CatalogItem(oldLocator),
            Uri.fromFile(candidateFile),
            acceptMismatch = true,
        )

        assertTrue(result is LocalMediaRelinkOutcome.Rejected)
        assertTrue((result as LocalMediaRelinkOutcome.Rejected).message.contains("already linked"))
        assertEquals(original, database.localWallpaperDao().get(oldLocator))
        assertEquals(itemAlreadyAtCandidate, database.localWallpaperDao().get(candidateLocator))
    }

    private fun expectation(
        sha256: String = "",
        mimeType: String = "",
        sizeBytes: Long = 0,
        width: Int = 0,
        height: Int = 0,
        durationMs: Long = 0,
    ) = LocalMediaRelinkExpectation(
        mediaType = "WALLPAPER",
        locator = "content://old",
        sha256 = sha256,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMs,
    )

    private fun candidate(
        sha256: String = "",
        metadata: MediaTechnicalMetadata = metadata(),
    ) = LocalMediaRelinkCandidate(
        locator = "content://new",
        sha256 = sha256,
        metadata = metadata,
    )

    private fun metadata(
        mime: String = "image/png",
        size: Long = 0,
        width: Int = 0,
        height: Int = 0,
        duration: Long = 0,
    ) = MediaTechnicalMetadata(
        mimeType = mime,
        sizeBytes = size,
        width = width,
        height = height,
        durationMs = duration,
    )
}
