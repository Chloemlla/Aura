package com.freevibe.data.repository

import android.content.Context
import androidx.room.Room
import com.freevibe.data.local.FreeVibeDatabase
import com.freevibe.data.local.PreferencesManager
import com.freevibe.data.model.DownloadEntity
import com.freevibe.data.model.FavoriteEntity
import com.freevibe.data.model.WallpaperCacheEntity
import com.freevibe.data.model.WallpaperCollectionEntity
import com.freevibe.data.model.WallpaperCollectionItemEntity
import com.freevibe.data.model.WallpaperHistoryEntity
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeneratedAssetReferenceIndexTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var db: FreeVibeDatabase
    private lateinit var generatedDir: File

    private var darkSlot = ""
    private var lightSlot = ""
    private var nightVariant = ""
    private var packJson = ""

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FreeVibeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        generatedDir = File(temporaryFolder.root, "ai_wallpapers").apply { mkdirs() }
    }

    @After
    fun tearDown() = db.close()

    private fun index(): GeneratedAssetReferenceIndex {
        val prefs = mockk<PreferencesManager>()
        every { prefs.darkModeWallpaperId } returns flowOf(darkSlot)
        every { prefs.lightModeWallpaperId } returns flowOf(lightSlot)
        every { prefs.lastNightVariantWallpaperLocator } returns flowOf(nightVariant)
        every { prefs.wallpaperPackJson } returns flowOf(packJson)
        return GeneratedAssetReferenceIndex(
            favoriteDao = db.favoriteDao(),
            collectionDao = db.collectionDao(),
            historyDao = db.wallpaperHistoryDao(),
            cacheDao = db.wallpaperCacheDao(),
            downloadDao = db.downloadDao(),
            prefs = prefs,
        )
    }

    private fun generatedFile(id: String): File =
        File(generatedDir, "$id.png").apply { writeBytes(ByteArray(8)) }

    /**
     * Android paths always start with `/`; the JVM test tree may not (Windows drive
     * letters), so normalise the same way the production helper does before building
     * the URI spellings a row could legitimately hold.
     */
    private fun absolutePosixPath(file: File): String {
        val normalized = file.path.replace('\\', '/')
        return if (normalized.startsWith("/")) normalized else "/$normalized"
    }

    /** `File.toURI()` spelling: `file:/path`. */
    private fun uriLocator(file: File): String = "file:" + absolutePosixPath(file)

    /** `Uri.fromFile` spelling: `file:///path`. */
    private fun tripleSlashLocator(file: File): String = "file://" + absolutePosixPath(file)

    private fun favorite(locator: String) = FavoriteEntity(
        id = "fav-1",
        source = "AI_GENERATED",
        type = "WALLPAPER",
        thumbnailUrl = locator,
        fullUrl = locator,
    )

    @Test
    fun `a file nothing points at is unreferenced`() = runTest {
        val file = generatedFile("alpha")

        assertTrue(index().isUnreferenced(file))
    }

    @Test
    fun `a favorite keeps the file alive`() = runTest {
        val file = generatedFile("alpha")
        db.favoriteDao().insert(favorite(uriLocator(file)))

        assertEquals(setOf(GeneratedAssetReference.FAVORITE), index().referencesFor(file))
    }

    @Test
    fun `the triple-slash spelling is recognised too`() = runTest {
        val file = generatedFile("alpha")
        db.favoriteDao().insert(favorite(tripleSlashLocator(file)))

        assertFalse(index().isUnreferenced(file))
    }

    @Test
    fun `the bare absolute path spelling is recognised too`() = runTest {
        val file = generatedFile("alpha")
        db.favoriteDao().insert(favorite(file.path.replace('\\', '/')))

        assertFalse(index().isUnreferenced(file))
    }

    @Test
    fun `a collection item keeps the file alive`() = runTest {
        val file = generatedFile("alpha")
        val collectionId = db.collectionDao().createCollection(
            WallpaperCollectionEntity(name = "Night"),
        )
        db.collectionDao().addItem(
            WallpaperCollectionItemEntity(
                collectionId = collectionId,
                wallpaperId = "alpha",
                thumbnailUrl = uriLocator(file),
                fullUrl = uriLocator(file),
                source = "AI_GENERATED",
            ),
        )

        assertEquals(setOf(GeneratedAssetReference.COLLECTION_ITEM), index().referencesFor(file))
    }

    @Test
    fun `history keeps the file alive`() = runTest {
        val file = generatedFile("alpha")
        db.wallpaperHistoryDao().insert(
            WallpaperHistoryEntity(
                wallpaperId = "alpha",
                source = "AI_GENERATED",
                thumbnailUrl = uriLocator(file),
                fullUrl = uriLocator(file),
            ),
        )

        assertEquals(setOf(GeneratedAssetReference.HISTORY), index().referencesFor(file))
    }

    @Test
    fun `the wallpaper cache keeps the file alive`() = runTest {
        val file = generatedFile("alpha")
        db.wallpaperCacheDao().insertAll(
            listOf(
                WallpaperCacheEntity(
                    id = "alpha",
                    source = "AI_GENERATED",
                    thumbnailUrl = uriLocator(file),
                    fullUrl = uriLocator(file),
                    width = 576,
                    height = 1024,
                    cacheKey = "ai_1",
                ),
            ),
        )

        assertEquals(setOf(GeneratedAssetReference.WALLPAPER_CACHE), index().referencesFor(file))
    }

    @Test
    fun `a download row keeps the file alive`() = runTest {
        val file = generatedFile("alpha")
        db.downloadDao().insert(
            DownloadEntity(
                id = "alpha",
                source = "AI_GENERATED",
                type = "WALLPAPER",
                localPath = uriLocator(file),
            ),
        )

        assertEquals(setOf(GeneratedAssetReference.DOWNLOAD), index().referencesFor(file))
    }

    @Test
    fun `a day-night slot keeps the file alive by id`() = runTest {
        val file = generatedFile("alpha")
        darkSlot = "alpha"

        assertEquals(setOf(GeneratedAssetReference.DAY_NIGHT_SLOT), index().referencesFor(file))
    }

    @Test
    fun `the last night-variant locator keeps the file alive`() = runTest {
        val file = generatedFile("alpha")
        nightVariant = uriLocator(file)

        assertEquals(setOf(GeneratedAssetReference.NIGHT_VARIANT), index().referencesFor(file))
    }

    @Test
    fun `a 24H wallpaper pack slot keeps the file alive`() = runTest {
        val file = generatedFile("alpha")
        packJson = """
            {"id":"pack","name":"Day","slots":[{"daypart":"NIGHT","wallpaperUri":"${uriLocator(file)}"}]}
        """.trimIndent()

        assertEquals(setOf(GeneratedAssetReference.WALLPAPER_PACK), index().referencesFor(file))
    }

    @Test
    fun `multiple references are all reported`() = runTest {
        val file = generatedFile("alpha")
        db.favoriteDao().insert(favorite(uriLocator(file)))
        db.wallpaperHistoryDao().insert(
            WallpaperHistoryEntity(
                wallpaperId = "alpha",
                source = "AI_GENERATED",
                thumbnailUrl = uriLocator(file),
                fullUrl = uriLocator(file),
            ),
        )
        darkSlot = "alpha"

        assertEquals(
            setOf(
                GeneratedAssetReference.FAVORITE,
                GeneratedAssetReference.HISTORY,
                GeneratedAssetReference.DAY_NIGHT_SLOT,
            ),
            index().referencesFor(file),
        )
    }

    @Test
    fun `removing the last reference makes the file collectable again`() = runTest {
        val file = generatedFile("alpha")
        val entity = favorite(uriLocator(file))
        db.favoriteDao().insert(entity)
        assertFalse(index().isUnreferenced(file))

        db.favoriteDao().delete(entity)

        assertTrue(index().isUnreferenced(file))
    }

    @Test
    fun `a reference to a sibling file does not protect this one`() = runTest {
        val kept = generatedFile("alpha")
        val other = generatedFile("beta")
        db.favoriteDao().insert(favorite(uriLocator(kept)))

        assertFalse(index().isUnreferenced(kept))
        assertTrue(index().isUnreferenced(other))
    }

    @Test
    fun `audit splits referenced from prunable and counts stale references`() = runTest {
        val referenced = generatedFile("alpha")
        val prunable = generatedFile("beta")
        db.favoriteDao().insert(favorite(uriLocator(referenced)))
        // A pack slot that points at a file that has already been deleted.
        val missing = uriLocator(File(generatedDir, "gone.png"))
        packJson = """
            {"id":"pack","name":"Day","slots":[{"daypart":"NIGHT","wallpaperUri":"$missing"}]}
        """.trimIndent()

        val audit = index().audit(listOf(referenced, prunable))

        assertEquals(1, audit.referencedFiles)
        assertEquals(1, audit.unreferencedFiles)
        assertEquals(1, audit.staleReferences)
    }

    @Test
    fun `unmanaged locators are never treated as Aura-owned`() {
        assertFalse(isManagedGeneratedLocator("content://media/external/images/1"))
        assertFalse(isManagedGeneratedLocator("https" + "://example.com/ai_wallpapers/x.png"))
        assertFalse(isManagedGeneratedLocator("/storage/emulated/0/Pictures/x.png"))
        assertTrue(isManagedGeneratedLocator("file:/data/user/0/com.freevibe/files/ai_wallpapers/x.png"))
    }

    @Test
    fun `every persisted spelling of a path is covered`() {
        assertEquals(
            setOf(
                "/data/files/ai_wallpapers/x.png",
                "file:/data/files/ai_wallpapers/x.png",
                "file:///data/files/ai_wallpapers/x.png",
            ),
            generatedLocatorSpellings("/data/files/ai_wallpapers/x.png"),
        )
    }
}
