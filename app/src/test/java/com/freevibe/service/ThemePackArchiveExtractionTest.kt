package com.freevibe.service

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-archive coverage for the theme-pack extraction path. These build actual
 * zip bytes so the bounds are proven against `java.util.zip` behaviour (including
 * streamed entries whose compressed size is only known after the body is read),
 * not just against the pure guard state machine.
 */
class ThemePackArchiveExtractionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val manifest = """
        {"version":1,"id":"pack-1","name":"Night desk"}
    """.trimIndent()

    private fun zipOf(build: ZipOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.build() }
        return bytes.toByteArray()
    }

    private fun ZipOutputStream.entry(name: String, content: ByteArray = ByteArray(0)) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    private fun extract(archive: ByteArray, importDir: File): ImportedThemePack =
        ZipInputStream(archive.inputStream()).use { extractThemePackArchive(it, importDir) }

    private fun newImportDir(name: String): File =
        File(temporaryFolder.root, name)

    @Test
    fun `well formed pack extracts its manifest and assets`() {
        val archive = zipOf {
            entry("assets/wallpaper.png", ByteArray(1024) { 7 })
            entry("theme-pack.json", manifest.toByteArray())
        }
        val importDir = newImportDir("ok")

        val imported = extract(archive, importDir)

        assertEquals("pack-1", imported.recipe.id)
        assertEquals(1, imported.assetsByKey.size)
        val extracted = File(imported.assetsByKey.getValue("assets/wallpaper.png"))
        assertTrue(extracted.exists())
        assertEquals(1024L, extracted.length())
        // The flattened name stays inside the staging directory.
        assertEquals(importDir.canonicalFile, extracted.canonicalFile.parentFile)
    }

    @Test
    fun `traversal entry names are rejected and nothing is left behind`() {
        val archive = zipOf {
            entry("../../databases/aura.db", ByteArray(16))
            entry("theme-pack.json", manifest.toByteArray())
        }
        val importDir = newImportDir("traversal")

        val failure = assertThrows(ArchiveExtractionException::class.java) {
            extract(archive, importDir)
        }

        assertEquals(ArchiveRejectionReason.PATH_TRAVERSAL, failure.reason)
        assertFalse(importDir.exists())
    }

    @Test
    fun `absolute entry names are rejected`() {
        val archive = zipOf {
            entry("/data/data/com.freevibe/files/pwned", ByteArray(16))
        }

        val failure = assertThrows(ArchiveExtractionException::class.java) {
            extract(archive, newImportDir("absolute"))
        }

        assertEquals(ArchiveRejectionReason.ABSOLUTE_PATH, failure.reason)
    }

    @Test
    fun `entry floods are rejected before the filesystem fills`() {
        val archive = zipOf {
            repeat(THEME_PACK_EXTRACTION_LIMITS.maxEntries + 5) { entry("assets/pad-$it.bin") }
            entry("theme-pack.json", manifest.toByteArray())
        }
        val importDir = newImportDir("flood")

        val failure = assertThrows(ArchiveExtractionException::class.java) {
            extract(archive, importDir)
        }

        assertEquals(ArchiveRejectionReason.TOO_MANY_ENTRIES, failure.reason)
        assertFalse(importDir.exists())
    }

    @Test
    fun `highly compressible entries are rejected on ratio`() {
        // 2 MB of zeroes deflates to a couple of kB — well past the 200:1 ceiling.
        val archive = zipOf {
            entry("assets/bomb.bin", ByteArray(2 * 1024 * 1024))
            entry("theme-pack.json", manifest.toByteArray())
        }
        val importDir = newImportDir("bomb")

        val failure = assertThrows(ArchiveExtractionException::class.java) {
            extract(archive, importDir)
        }

        assertEquals(ArchiveRejectionReason.COMPRESSION_RATIO, failure.reason)
        assertFalse(importDir.exists())
    }

    @Test
    fun `a missing manifest fails the import and cleans the staging directory`() {
        val archive = zipOf { entry("assets/wallpaper.png", ByteArray(32) { 3 }) }
        val importDir = newImportDir("no-manifest")

        assertThrows(IllegalStateException::class.java) { extract(archive, importDir) }

        assertFalse(importDir.exists())
    }

    @Test
    fun `directory entries are counted but never written`() {
        val archive = zipOf {
            putNextEntry(ZipEntry("assets/"))
            closeEntry()
            entry("assets/wallpaper.png", ByteArray(8) { 1 })
            entry("theme-pack.json", manifest.toByteArray())
        }
        val importDir = newImportDir("dirs")

        val imported = extract(archive, importDir)

        assertEquals(1, imported.assetsByKey.size)
        assertEquals(1, importDir.listFiles()?.size)
    }

    @Test
    fun `same file name in different folders does not overwrite`() {
        val archive = zipOf {
            entry("assets/a/x.png", ByteArray(4) { 1 })
            entry("assets/b/x.png", ByteArray(8) { 2 })
            entry("theme-pack.json", manifest.toByteArray())
        }

        val imported = extract(archive, newImportDir("collide"))

        assertEquals(2, imported.assetsByKey.size)
        assertEquals(
            2,
            imported.assetsByKey.values.map { File(it).name }.toSet().size,
        )
    }
}
