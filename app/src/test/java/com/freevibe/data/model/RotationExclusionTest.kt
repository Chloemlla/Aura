package com.freevibe.data.model

import com.freevibe.data.repository.filterRotationCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationExclusionTest {

    @Test
    fun `remote identity is shared by feeds collections and downloads`() {
        val wallpaper = wallpaper("post-1")
        val collectionItem = WallpaperCollectionItemEntity(
            collectionId = 7,
            wallpaperId = "post-1",
            thumbnailUrl = "https://example.com/post-1-thumb.jpg",
            fullUrl = "https://example.com/post-1.jpg",
            source = "reddit",
        )
        val download = DownloadEntity(
            id = "wallpaper:batch_WALLPAPER::REDDIT::post-1",
            source = "REDDIT",
            type = "WALLPAPER",
            localPath = "/data/user/0/com.freevibe/files/post-1.jpg",
        )

        assertEquals(wallpaper.rotationIdentity().stableId, collectionItem.rotationIdentity().stableId)
        assertEquals(wallpaper.rotationIdentity().stableId, download.rotationIdentity().stableId)
    }

    @Test
    fun `local content hash survives a document path relink`() {
        val hash = "ab".repeat(32)
        val before = localWallpaper("content://old/tree/photo", "old-id", hash)
        val after = localWallpaper("content://new/tree/photo", "new-id", hash)

        assertEquals(before.rotationIdentity().stableId, after.rotationIdentity().stableId)
        assertTrue(before.rotationIdentity().toRotationExclusion().matches(after.rotationIdentity()))
    }

    @Test
    fun `persisted exclusion fingerprints locator without retaining it`() {
        val locator = "content://private/folder/photo.jpg"
        val entity = rotationIdentityForLocator(locator, "Photo").toRotationExclusion()

        assertFalse(entity.toString().contains(locator))
        assertEquals(64, entity.locatorDigest.length)
        assertTrue(entity.matches(rotationIdentityForLocator(locator)))
        assertFalse(entity.matches(rotationIdentityForLocator("content://private/folder/other.jpg")))
    }

    @Test
    fun `content and locator matches do not cross media types`() {
        val hash = "cd".repeat(32)
        val locator = "content://library/shared-media"
        val wallpaper = rotationIdentity(
            mediaType = ROTATION_MEDIA_WALLPAPER,
            source = ContentSource.LOCAL.name,
            contentId = "wallpaper",
            contentHash = hash,
            locator = locator,
        ).toRotationExclusion()
        val video = rotationIdentity(
            mediaType = ROTATION_MEDIA_VIDEO,
            source = ContentSource.LOCAL.name,
            contentId = "video",
            contentHash = hash,
            locator = locator,
        )

        assertFalse(wallpaper.matches(video))
        assertFalse(RotationExclusionIndex(listOf(wallpaper)).contains(video))
    }

    @Test
    fun `rotation filter never falls back to excluded candidates`() {
        val first = wallpaper("first")
        val second = wallpaper("second")
        val firstExclusion = first.rotationIdentity().toRotationExclusion()

        val mixed = filterRotationCandidates(listOf(first, second), listOf(firstExclusion))
        assertEquals(listOf("second"), mixed.candidates.map { it.id })
        assertEquals(1, mixed.excludedCount)
        assertFalse(mixed.allExcluded)

        val allExcluded = filterRotationCandidates(
            listOf(first, second),
            listOf(firstExclusion, second.rotationIdentity().toRotationExclusion()),
        )
        assertTrue(allExcluded.candidates.isEmpty())
        assertTrue(allExcluded.allExcluded)
    }

    private fun wallpaper(id: String) = Wallpaper(
        id = id,
        source = ContentSource.REDDIT,
        thumbnailUrl = "https://example.com/$id-thumb.jpg",
        fullUrl = "https://example.com/$id.jpg",
        width = 1080,
        height = 1920,
    )

    private fun localWallpaper(uri: String, documentId: String, hash: String) = LocalWallpaperEntity(
        documentUri = uri,
        folderUri = uri.substringBeforeLast('/'),
        documentId = documentId,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024,
        modifiedAt = 1,
        contentHash = hash,
    )
}
