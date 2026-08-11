package com.chloemlla.aura.service

import android.content.Context
import com.squareup.moshi.Moshi
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Deleting a download used to destroy the file immediately, while removing one
 * item from a collection already offered Undo. The staged trash closes that gap,
 * so these cover the retention window, the bound on staged entries, and the
 * path-safety rule that keeps purge inside the staging directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadTrashTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var trash: DownloadTrash

    @Before
    fun setUp() {
        context.getSharedPreferences("freevibe_download_trash", Context.MODE_PRIVATE)
            .edit().clear().commit()
        trash = DownloadTrash(context, Moshi.Builder().build())
        trash.stagingDir.deleteRecursively()
        trash.stagingDir.mkdirs()
    }

    @After
    fun tearDown() {
        trash.stagingDir.deleteRecursively()
    }

    private fun stagedFile(name: String): File =
        File(trash.stagingDir, name).apply { writeBytes(ByteArray(16)) }

    private fun entry(
        id: String,
        deletedAtMs: Long = 0L,
        stagedPath: String = "",
    ) = TrashedDownload(
        id = id,
        source = "WALLHAVEN",
        type = "WALLPAPER",
        name = "Sunset",
        localPath = "/storage/emulated/0/Pictures/Aura/$id.jpg",
        downloadedAt = 100L,
        stagedPath = stagedPath,
        deletedAtMs = deletedAtMs,
    )

    @Test
    fun `an added entry survives a new instance`() {
        trash.add(entry("a"))

        val reopened = DownloadTrash(context, Moshi.Builder().build())

        assertEquals(listOf("a"), reopened.entries().map { it.id })
    }

    @Test
    fun `taking an entry removes it and returns it once`() {
        trash.add(entry("a"))

        assertEquals("a", trash.take("a")?.id)
        assertNull(trash.take("a"))
        assertTrue(trash.entries().isEmpty())
    }

    @Test
    fun `re-deleting the same id replaces rather than duplicates`() {
        trash.add(entry("a", deletedAtMs = 1L))
        trash.add(entry("a", deletedAtMs = 2L))

        assertEquals(1, trash.entries().size)
        assertEquals(2L, trash.entries().single().deletedAtMs)
    }

    @Test
    fun `entries inside the retention window are kept`() {
        val staged = stagedFile("keep.jpg")
        trash.add(entry("a", deletedAtMs = 1_000L, stagedPath = staged.absolutePath))

        val purged = trash.purgeExpired(nowMs = 1_000L + DELETION_RETENTION_MS - 1)

        assertTrue(purged.isEmpty())
        assertTrue(staged.exists())
        assertEquals(1, trash.entries().size)
    }

    @Test
    fun `entries past the retention window are purged with their bytes`() {
        val staged = stagedFile("gone.jpg")
        trash.add(entry("a", deletedAtMs = 1_000L, stagedPath = staged.absolutePath))

        val purged = trash.purgeExpired(nowMs = 1_000L + DELETION_RETENTION_MS)

        assertEquals(listOf("a"), purged.map { it.id })
        assertFalse(staged.exists())
        assertTrue(trash.entries().isEmpty())
    }

    @Test
    fun `the trash is bounded so a bulk delete cannot grow without limit`() {
        val overflow = DELETION_TRASH_MAX_ENTRIES + 10
        val files = (0 until overflow).map { stagedFile("f$it.jpg") }
        files.forEachIndexed { index, file ->
            trash.add(entry("id$index", deletedAtMs = index.toLong(), stagedPath = file.absolutePath))
        }

        assertEquals(DELETION_TRASH_MAX_ENTRIES, trash.entries().size)
        // Newest survive; the evicted oldest had their bytes released.
        assertEquals("id${overflow - 1}", trash.entries().first().id)
        assertFalse(files.first().exists())
        assertTrue(files.last().exists())
    }

    @Test
    fun `purge never follows a staged path outside the staging directory`() {
        val outside = File(context.filesDir, "not-trash.jpg").apply { writeBytes(ByteArray(8)) }
        trash.add(entry("a", deletedAtMs = 0L, stagedPath = outside.absolutePath))

        trash.purgeExpired(nowMs = DELETION_RETENTION_MS * 2)

        assertTrue("a path outside staging must never be deleted", outside.exists())
        outside.delete()
    }

    @Test
    fun `clear releases everything`() {
        val staged = stagedFile("a.jpg")
        trash.add(entry("a", stagedPath = staged.absolutePath))

        val cleared = trash.clear()

        assertEquals(listOf("a"), cleared.map { it.id })
        assertFalse(staged.exists())
        assertTrue(trash.entries().isEmpty())
    }

    @Test
    fun `a trashed entry round-trips back to its download row`() {
        val restored = entry("a", deletedAtMs = 5L).toEntity()

        assertEquals("a", restored.id)
        assertEquals("WALLHAVEN", restored.source)
        assertEquals("WALLPAPER", restored.type)
        assertEquals("Sunset", restored.name)
        assertEquals(100L, restored.downloadedAt)
    }
}
