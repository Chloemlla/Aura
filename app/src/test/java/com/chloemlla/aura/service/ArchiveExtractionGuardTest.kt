package com.chloemlla.aura.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveExtractionGuardTest {

    private val limits = ArchiveExtractionLimits(
        maxEntries = 3,
        maxEntryBytes = 100L,
        maxTotalBytes = 150L,
        maxCompressionRatio = 10L,
    )

    private fun session() = ArchiveExtractionGuard.newSession(limits)

    @Test
    fun `ordinary entry names are accepted`() {
        listOf(
            "theme-pack.json",
            "assets/wallpaper.png",
            "assets/nested/dir/my sound file.mp3",
            "assets/unicode-ünïcode.jpg",
        ).forEach { name ->
            assertNull(name, ArchiveExtractionGuard.isSafeEntryName(name))
        }
    }

    @Test
    fun `traversal names are rejected`() {
        listOf(
            "../databases/aura.db",
            "assets/../../etc/passwd",
            "assets/..\\..\\windows",
            "..",
        ).forEach { name ->
            assertEquals(name, ArchiveRejectionReason.PATH_TRAVERSAL, ArchiveExtractionGuard.isSafeEntryName(name))
        }
    }

    @Test
    fun `absolute names are rejected`() {
        listOf(
            "/data/data/com.chloemlla.aura/files/x",
            "\\\\server\\share\\x",
            "C:/Windows/System32/x",
            "c:x",
        ).forEach { name ->
            assertEquals(name, ArchiveRejectionReason.ABSOLUTE_PATH, ArchiveExtractionGuard.isSafeEntryName(name))
        }
    }

    @Test
    fun `blank and control-character names are rejected`() {
        assertEquals(ArchiveRejectionReason.EMPTY_NAME, ArchiveExtractionGuard.isSafeEntryName("   "))
        assertEquals(
            ArchiveRejectionReason.PATH_TRAVERSAL,
            ArchiveExtractionGuard.isSafeEntryName("assets/evil\u0000.png"),
        )
    }

    @Test
    fun `entry count is bounded across the whole archive`() {
        val guard = session()
        repeat(3) { guard.beginEntry("assets/$it.png") }

        val failure = assertThrows(ArchiveExtractionException::class.java) {
            guard.beginEntry("assets/overflow.png")
        }

        assertEquals(ArchiveRejectionReason.TOO_MANY_ENTRIES, failure.reason)
        assertEquals(4, guard.entryCount)
    }

    @Test
    fun `link entries are refused outright`() {
        val failure = assertThrows(ArchiveExtractionException::class.java) {
            session().beginEntry("assets/link", isLink = true)
        }

        assertEquals(ArchiveRejectionReason.LINK_ENTRY, failure.reason)
    }

    @Test
    fun `per-entry budget shrinks as the archive fills up`() {
        val guard = session()
        guard.beginEntry("assets/a.png")
        assertEquals(100L, guard.remainingEntryBudget())

        guard.commitEntry(expandedBytes = 90L, compressedBytes = 50L)
        guard.beginEntry("assets/b.png")

        // 150 total - 90 already written = 60 left, below the 100 per-entry cap.
        assertEquals(60L, guard.remainingEntryBudget())
    }

    @Test
    fun `oversized single entry is rejected`() {
        val guard = session()
        guard.beginEntry("assets/a.png")

        val failure = assertThrows(ArchiveExtractionException::class.java) {
            guard.commitEntry(expandedBytes = 101L, compressedBytes = 101L)
        }

        assertEquals(ArchiveRejectionReason.ENTRY_TOO_LARGE, failure.reason)
    }

    @Test
    fun `total expanded bytes are bounded`() {
        val guard = session()
        guard.beginEntry("assets/a.png")
        guard.commitEntry(expandedBytes = 100L, compressedBytes = 100L)
        guard.beginEntry("assets/b.png")

        val failure = assertThrows(ArchiveExtractionException::class.java) {
            guard.commitEntry(expandedBytes = 51L, compressedBytes = 51L)
        }

        assertEquals(ArchiveRejectionReason.ARCHIVE_TOO_LARGE, failure.reason)
    }

    @Test
    fun `zip bomb compression ratio is rejected`() {
        val guard = session()
        guard.beginEntry("assets/bomb.bin")

        val failure = assertThrows(ArchiveExtractionException::class.java) {
            guard.commitEntry(expandedBytes = 100L, compressedBytes = 1L)
        }

        assertEquals(ArchiveRejectionReason.COMPRESSION_RATIO, failure.reason)
    }

    @Test
    fun `undeclared compressed size skips the ratio check`() {
        val guard = session()
        guard.beginEntry("assets/streamed.bin")

        // ZipInputStream reports -1 until the data descriptor is read.
        guard.commitEntry(expandedBytes = 100L, compressedBytes = -1L)

        assertEquals(100L, guard.totalExpandedBytes)
    }

    @Test
    fun `theme pack limits stay conservative`() {
        assertEquals(512, THEME_PACK_EXTRACTION_LIMITS.maxEntries)
        assertEquals(64L * 1024L * 1024L, THEME_PACK_EXTRACTION_LIMITS.maxEntryBytes)
        assertEquals(128L * 1024L * 1024L, THEME_PACK_EXTRACTION_LIMITS.maxTotalBytes)
        assertEquals(200L, THEME_PACK_EXTRACTION_LIMITS.maxCompressionRatio)
    }
}
