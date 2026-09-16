package com.chloemlla.aura.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chloemlla.aura.data.local.FreeVibeDatabase
import com.chloemlla.aura.data.model.ROTATION_MEDIA_WALLPAPER
import com.chloemlla.aura.data.model.rotationIdentity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RotationExclusionRepositoryTest {
    private lateinit var database: FreeVibeDatabase
    private lateinit var repository: RotationExclusionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FreeVibeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RotationExclusionRepository(database.rotationExclusionDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `exclude persists and undo restores the item`() = runTest {
        val identity = rotationIdentity(
            mediaType = ROTATION_MEDIA_WALLPAPER,
            source = "REDDIT",
            contentId = "post-9",
            title = "Clouds",
            locator = "https://i.redd.it/post-9.jpg",
        )

        val exclusion = repository.exclude(identity)

        assertTrue(RotationExclusionRepository(database.rotationExclusionDao()).isExcluded(identity))
        assertEquals("Clouds", repository.snapshot().single().title)

        repository.restore(exclusion.stableId)

        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun `restore all clears every persistent exclusion`() = runTest {
        listOf("first", "second").forEach { id ->
            repository.exclude(
                rotationIdentity(
                    mediaType = ROTATION_MEDIA_WALLPAPER,
                    source = "REDDIT",
                    contentId = id,
                ),
            )
        }

        repository.restoreAll()

        assertTrue(repository.snapshot().isEmpty())
    }
}
