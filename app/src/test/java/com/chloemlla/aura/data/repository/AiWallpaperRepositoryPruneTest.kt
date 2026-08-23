package com.chloemlla.aura.data.repository

import android.content.Context
import androidx.room.Room
import com.chloemlla.aura.data.local.FreeVibeDatabase
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.FavoriteEntity
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ownership rules for the managed generated-wallpaper directory: the 50-file cap
 * and the unfavourite path must never destroy a PNG something still points at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiWallpaperRepositoryPruneTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var db: FreeVibeDatabase
    private lateinit var repository: AiWallpaperRepository
    private lateinit var dir: File

    private var darkSlot = ""

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FreeVibeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = mockk<PreferencesManager>()
        every { prefs.darkModeWallpaperId } answers { flowOf(darkSlot) }
        every { prefs.lightModeWallpaperId } returns flowOf("")
        every { prefs.lastNightVariantWallpaperLocator } returns flowOf("")
        every { prefs.wallpaperPackJson } returns flowOf("")
        repository = AiWallpaperRepository(
            context = context,
            backend = mockk<GeneratedWallpaperBackend>(relaxed = true),
            referenceIndex = GeneratedAssetReferenceIndex(
                favoriteDao = db.favoriteDao(),
                collectionDao = db.collectionDao(),
                historyDao = db.wallpaperHistoryDao(),
                cacheDao = db.wallpaperCacheDao(),
                downloadDao = db.downloadDao(),
                prefs = prefs,
            ),
        )
        dir = File(context.filesDir, "ai_wallpapers").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    /** Oldest first, so index 0 is the first candidate for eviction. */
    private fun seedGenerated(count: Int): List<File> =
        (0 until count).map { index ->
            File(dir, "gen-$index.png").apply {
                writeBytes(ByteArray(4))
                setLastModified(1_000_000L + index * 1_000L)
            }
        }

    private fun locator(file: File): String {
        val normalized = file.path.replace('\\', '/')
        return "file:" + if (normalized.startsWith("/")) normalized else "/$normalized"
    }

    private fun favoriteFor(file: File) = FavoriteEntity(
        id = file.nameWithoutExtension,
        source = "AI_GENERATED",
        type = "WALLPAPER",
        thumbnailUrl = locator(file),
        fullUrl = locator(file),
    )

    @Test
    fun `pruning evicts only the oldest unreferenced files`() = runTest {
        val files = seedGenerated(10)

        repository.pruneOldFiles(maxCount = 4)

        // Newest four survive; the six oldest are gone.
        assertEquals(
            files.takeLast(4).map { it.name }.toSet(),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a favorited file survives an unbounded number of newer generations`() = runTest {
        val files = seedGenerated(10)
        val pinned = files.first()
        db.favoriteDao().insert(favoriteFor(pinned))

        repository.pruneOldFiles(maxCount = 2)

        assertTrue("favorited generation must survive pruning", pinned.exists())
        assertEquals(
            setOf(pinned.name) + files.takeLast(2).map { it.name },
            dir.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a day-night slot file survives pruning`() = runTest {
        val files = seedGenerated(6)
        val pinned = files.first()
        darkSlot = pinned.nameWithoutExtension

        repository.pruneOldFiles(maxCount = 1)

        assertTrue(pinned.exists())
    }

    @Test
    fun `referenced files do not consume the unreferenced budget`() = runTest {
        val files = seedGenerated(6)
        files.take(3).forEach { db.favoriteDao().insert(favoriteFor(it)) }

        repository.pruneOldFiles(maxCount = 2)

        // All three favourites plus the two newest unreferenced files remain.
        assertEquals(5, dir.listFiles()!!.size)
        files.take(3).forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `delete refuses while a reference survives and succeeds once it is gone`() = runTest {
        val file = seedGenerated(1).single()
        val favorite = favoriteFor(file)
        db.favoriteDao().insert(favorite)

        assertFalse(repository.deleteGeneratedWallpaper(locator(file)))
        assertTrue(file.exists())

        db.favoriteDao().delete(favorite)

        assertTrue(repository.deleteGeneratedWallpaper(locator(file)))
        assertFalse(file.exists())
    }

    @Test
    fun `delete never touches locators outside the managed directory`() = runTest {
        val outside = File(context.filesDir, "user-photo.png").apply { writeBytes(ByteArray(4)) }

        assertFalse(repository.deleteGeneratedWallpaper(locator(outside)))
        assertFalse(repository.deleteGeneratedWallpaper("content://media/external/images/1"))
        assertTrue(outside.exists())
        outside.delete()
    }

    @Test
    fun `interrupted temp writes are swept without touching referenced pngs`() = runTest {
        val files = seedGenerated(2)
        db.favoriteDao().insert(favoriteFor(files.first()))
        val leftover = File(dir, "half-written.tmp").apply { writeBytes(ByteArray(4)) }

        repository.pruneOldFiles(maxCount = 50)

        assertFalse(leftover.exists())
        files.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `audit reports referenced prunable and stale counts`() = runTest {
        val files = seedGenerated(3)
        db.favoriteDao().insert(favoriteFor(files.first()))
        darkSlot = "gone-forever"

        val audit = repository.auditGeneratedAssets()

        assertEquals(1, audit.referencedFiles)
        assertEquals(2, audit.unreferencedFiles)
        // The slot id has no history row, so it is a dangling preference, not a
        // stale reference to a deleted managed file.
        assertEquals(0, audit.staleReferences)
    }
}
