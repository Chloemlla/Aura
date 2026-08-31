package com.chloemlla.aura.service

import android.content.Context
import androidx.room.Room
import com.chloemlla.aura.data.local.FreeVibeDatabase
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.data.model.WallpaperCollectionEntity
import com.chloemlla.aura.data.model.WallpaperCollectionItemEntity
import com.chloemlla.aura.data.repository.CollectionRepository
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Restore is the one operation that can destroy a user's library, so it is
 * covered end to end: version gating, migration reporting, non-portable asset
 * reporting, and full rollback after an injected mid-write failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryImportPlanTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var db: FreeVibeDatabase
    private lateinit var exporter: LibraryExporter

    private val packJson = MutableStateFlow("")
    private val profilesJson = MutableStateFlow("")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FreeVibeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = mockk<PreferencesManager>()
        every { prefs.wallpaperPackJson } returns packJson
        every { prefs.soundProfilesJson } returns profilesJson
        coEvery { prefs.setWallpaperPackJson(any()) } answers { packJson.value = firstArg() }
        coEvery { prefs.setSoundProfilesJson(any()) } answers { profilesJson.value = firstArg() }
        exporter = LibraryExporter(
            context = context,
            database = db,
            favoriteDao = db.favoriteDao(),
            collectionRepo = CollectionRepository(db.collectionDao()),
            searchHistoryDao = db.searchHistoryDao(),
            prefs = prefs,
            moshi = Moshi.Builder().build(),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun payload(
        version: String = "2",
        favorites: String = "[]",
        collections: String = "[]",
        searchHistory: String = "[]",
        extra: String = "",
    ): String = """
        {
          "version": $version,
          "favorites": $favorites,
          "collections": $collections,
          "searchHistory": $searchHistory$extra
        }
    """.trimIndent()

    private val portableFavorite = """
        {"id":"wp-1","source":"WALLHAVEN","type":"WALLPAPER",
         "thumbnailUrl":"https://w.example/t.jpg","fullUrl":"https://w.example/f.jpg","name":"Blue"}
    """.trimIndent()

    private val localFavorite = """
        {"id":"ai-1","source":"AI_GENERATED","type":"WALLPAPER",
         "thumbnailUrl":"file:/data/user/0/com.chloemlla.aura/files/ai_wallpapers/ai-1.png",
         "fullUrl":"file:/data/user/0/com.chloemlla.aura/files/ai_wallpapers/ai-1.png","name":"Dream"}
    """.trimIndent()

    // -- version gating --

    @Test
    fun `a payload without a version is refused`() = runTest {
        val failure = runCatching { exporter.buildPlan("""{"favorites":[]}""") }.exceptionOrNull()

        assertTrue(failure is LibraryImportUnsupportedVersionException)
        assertTrue(failure!!.message!!.contains("version"))
    }

    @Test
    fun `a future payload version is refused instead of partially read`() = runTest {
        val failure = runCatching {
            exporter.buildPlan(payload(version = "99", favorites = "[$portableFavorite]"))
        }.exceptionOrNull()

        assertTrue(failure is LibraryImportUnsupportedVersionException)
        assertTrue(failure!!.message!!.contains("newer version"))
    }

    @Test
    fun `a corrupt payload is refused`() = runTest {
        assertTrue(runCatching { exporter.buildPlan("not json at all") }.isFailure)
    }

    @Test
    fun `a v1 payload is migrated and its downloads are reported not silently dropped`() = runTest {
        val plan = exporter.buildPlan(
            payload(
                version = "1",
                favorites = "[$portableFavorite]",
                extra = ""","downloads":[{"id":"d-1","name":"Sunset","localPath":"/sdcard/old.jpg"}]""",
            ),
        )

        assertEquals(1, plan.sourceVersion)
        assertEquals(1, plan.favorites.size)
        val migrationSkips = plan.skipped.filter {
            it.reason == LibraryImportSkipReason.DROPPED_BY_MIGRATION
        }
        assertEquals(listOf("Sunset"), migrationSkips.map { it.label })
    }

    @Test
    fun `a v2 payload is accepted`() = runTest {
        val plan = exporter.buildPlan(payload(favorites = "[$portableFavorite]"))

        assertEquals(2, plan.sourceVersion)
        assertEquals(listOf("wp-1"), plan.favorites.map { it.id })
    }

    // -- non-portable reporting --

    @Test
    fun `device-local favorites are reported as non-portable rather than dropped`() = runTest {
        val plan = exporter.buildPlan(payload(favorites = "[$portableFavorite,$localFavorite]"))

        assertEquals(listOf("wp-1"), plan.favorites.map { it.id })
        assertEquals(
            listOf("Dream" to LibraryImportSkipReason.NON_PORTABLE),
            plan.nonPortable.map { it.label to it.reason },
        )
    }

    @Test
    fun `device-local collection items are reported as non-portable`() = runTest {
        val plan = exporter.buildPlan(
            payload(
                collections = """
                    [{"id":1,"name":"Night","items":[
                      {"wallpaperId":"ai-2","source":"AI_GENERATED",
                       "thumbnailUrl":"content://media/external/images/2",
                       "fullUrl":"content://media/external/images/2"}
                    ]}]
                """.trimIndent(),
            ),
        )

        assertEquals(1, plan.collections.size)
        assertTrue(plan.collections.single().items.isEmpty())
        assertEquals(
            listOf(LibraryImportSkipReason.NON_PORTABLE),
            plan.nonPortable.map { it.reason },
        )
    }

    @Test
    fun `a locator is only portable when it is https`() {
        assertTrue(isNonPortableLocator("file:/data/x.png"))
        assertTrue(isNonPortableLocator("content://media/external/images/1"))
        assertTrue(isNonPortableLocator("/storage/emulated/0/x.png"))
        assertEquals(false, isNonPortableLocator("https://example.com/x.png"))
        assertEquals(false, isNonPortableLocator(""))
    }

    // -- conflict planning --

    @Test
    fun `an item already in the target collection is reported as a duplicate`() = runTest {
        val collectionId = db.collectionDao().createCollection(WallpaperCollectionEntity(name = "Night"))
        db.collectionDao().addItem(
            WallpaperCollectionItemEntity(
                collectionId = collectionId,
                wallpaperId = "wp-9",
                thumbnailUrl = "https://w.example/t.jpg",
                fullUrl = "https://w.example/f.jpg",
                source = "WALLHAVEN",
            ),
        )

        val plan = exporter.buildPlan(
            payload(
                collections = """
                    [{"id":1,"name":"Night","items":[
                      {"wallpaperId":"wp-9","source":"WALLHAVEN",
                       "thumbnailUrl":"https://w.example/t.jpg","fullUrl":"https://w.example/f.jpg"}
                    ]}]
                """.trimIndent(),
            ),
        )

        assertEquals(collectionId, plan.collections.single().existingId)
        assertTrue(plan.collections.single().items.isEmpty())
        assertEquals(
            listOf(LibraryImportSkipReason.DUPLICATE),
            plan.skipped.map { it.reason },
        )
    }

    @Test
    fun `planning writes nothing`() = runTest {
        exporter.buildPlan(
            payload(
                favorites = "[$portableFavorite]",
                collections = """[{"id":1,"name":"Night","items":[]}]""",
            ),
        )

        assertEquals(0, db.favoriteDao().count().first())
        assertEquals(0, db.collectionDao().getAllCollections().first().size)
    }

    // -- atomicity --

    @Test
    fun `an injected mid-write failure leaves the pre-import state intact`() = runTest {
        packJson.value = "original-pack"
        profilesJson.value = "original-profiles"
        exporter.failBeforeCommit = { throw IllegalStateException("disk full") }

        val plan = exporter.buildPlan(
            payload(
                favorites = "[$portableFavorite]",
                collections = """[{"id":1,"name":"Night","items":[]}]""",
                searchHistory = """[{"query":"blue","type":"WALLPAPER","searchedAt":1}]""",
                extra = ""","wallpaperPackJson":"new-pack","soundProfilesJson":"new-profiles"""",
            ),
        )

        val failure = runCatching { exporter.applyPlan(plan) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)

        assertEquals(0, db.favoriteDao().count().first())
        assertEquals(0, db.collectionDao().getAllCollections().first().size)
        assertEquals(0, db.searchHistoryDao().count("WALLPAPER"))
        assertEquals("original-pack", packJson.value)
        assertEquals("original-profiles", profilesJson.value)
    }

    @Test
    fun `a successful import writes exactly what the plan promised`() = runTest {
        val plan = exporter.buildPlan(
            payload(
                favorites = "[$portableFavorite,$localFavorite]",
                collections = """
                    [{"id":1,"name":"Night","items":[
                      {"wallpaperId":"wp-2","source":"WALLHAVEN",
                       "thumbnailUrl":"https://w.example/t2.jpg","fullUrl":"https://w.example/f2.jpg"}
                    ]}]
                """.trimIndent(),
                searchHistory = """[{"query":"blue","type":"WALLPAPER","searchedAt":1}]""",
                extra = ",\"wallpaperPackJson\":\"{\\\"id\\\":\\\"new-pack\\\",\\\"name\\\":\\\"New Pack\\\"}\"",
            ),
        )

        exporter.applyPlan(plan)

        assertEquals(1, db.favoriteDao().count().first())
        val collections = db.collectionDao().getAllCollections().first()
        assertEquals(listOf("Night"), collections.map { it.name })
        assertEquals(
            listOf("wp-2"),
            db.collectionDao().getCollectionItems(collections.single().collectionId).first()
                .map { it.wallpaperId },
        )
        assertEquals(1, db.searchHistoryDao().count("WALLPAPER"))
        assertEquals("""{"id":"new-pack","name":"New Pack"}""", packJson.value)
        // favorites(1) + collection(1) + item(1) + search(1) + pack(1)
        assertEquals(5, plan.writeCount)
    }

    @Test
    fun `re-importing the same backup does not duplicate collections`() = runTest {
        val json = payload(
            collections = """
                [{"id":1,"name":"Night","items":[
                  {"wallpaperId":"wp-2","source":"WALLHAVEN",
                   "thumbnailUrl":"https://w.example/t2.jpg","fullUrl":"https://w.example/f2.jpg"}
                ]}]
            """.trimIndent(),
        )

        exporter.applyPlan(exporter.buildPlan(json))
        exporter.applyPlan(exporter.buildPlan(json))

        val collections = db.collectionDao().getAllCollections().first()
        assertEquals(1, collections.size)
        assertEquals(
            1,
            db.collectionDao().getCollectionItems(collections.single().collectionId).first().size,
        )
    }
}
