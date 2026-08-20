package com.freevibe.data.local

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Installing an older Aura over a newer one used to crash on every launch, and
 * the only recovery destroyed the library. These cover the file-level half:
 * recognising the situation without opening a connection, and keeping the data
 * where it can still be recovered.
 */
class DatabaseDowngradeGuardTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("aura-db-guard").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A SQLite file is identified by its header, and the version lives at byte 60. */
    private fun sqliteFileAtVersion(version: Int, name: String = "freevibe.db"): File {
        val header = ByteArray(4096)
        // The guard's own constant, so a fixture cannot drift into a header no
        // real SQLite file has and quietly stop testing anything.
        DatabaseDowngradeGuard.SQLITE_MAGIC.copyInto(header)
        header[60] = (version ushr 24 and 0xFF).toByte()
        header[61] = (version ushr 16 and 0xFF).toByte()
        header[62] = (version ushr 8 and 0xFF).toByte()
        header[63] = (version and 0xFF).toByte()
        val file = File(dir, name)
        file.writeBytes(header)
        return file
    }

    @Test
    fun `the schema version is read straight out of the file header`() {
        assertEquals(16, DatabaseDowngradeGuard.readOnDiskVersion(sqliteFileAtVersion(16)))
    }

    @Test
    fun `a version above one byte is decoded big-endian`() {
        assertEquals(300, DatabaseDowngradeGuard.readOnDiskVersion(sqliteFileAtVersion(300)))
    }

    @Test
    fun `a missing database has no version rather than a zero`() {
        assertNull(DatabaseDowngradeGuard.readOnDiskVersion(File(dir, "absent.db")))
    }

    @Test
    fun `a truncated file is refused instead of read past its end`() {
        val file = File(dir, "short.db")
        file.writeBytes(ByteArray(10))

        assertNull(DatabaseDowngradeGuard.readOnDiskVersion(file))
    }

    @Test
    fun `a file that is not SQLite at all is refused`() {
        val file = File(dir, "notsqlite.db")
        file.writeBytes(ByteArray(4096) { 0x41 })

        assertNull(DatabaseDowngradeGuard.readOnDiskVersion(file))
    }

    @Test
    fun `a directory where the database should be does not throw`() {
        val directory = File(dir, "freevibe.db")
        directory.mkdirs()

        assertNull(DatabaseDowngradeGuard.readOnDiskVersion(directory))
    }

    // -- inspect --

    @Test
    fun `a matching version is not a downgrade`() {
        assertNull(DatabaseDowngradeGuard.inspect(sqliteFileAtVersion(16), currentVersion = 16))
    }

    @Test
    fun `an older database is an ordinary upgrade the migrations handle`() {
        assertNull(DatabaseDowngradeGuard.inspect(sqliteFileAtVersion(9), currentVersion = 16))
    }

    @Test
    fun `a first run with no database is not a downgrade`() {
        assertNull(DatabaseDowngradeGuard.inspect(File(dir, "absent.db"), currentVersion = 16))
    }

    @Test
    fun `a newer database is reported with both versions`() {
        val receipt = DatabaseDowngradeGuard.inspect(
            sqliteFileAtVersion(18),
            currentVersion = 16,
            nowMs = 1_760_000_000_000L,
        )

        assertNotNull(receipt)
        assertEquals(18, receipt!!.fromVersion)
        assertEquals(16, receipt.toVersion)
        assertTrue(receipt.detectedUtc.endsWith("Z"))
    }

    @Test
    fun `the database is copied aside so reinstalling the newer build recovers it`() {
        val database = sqliteFileAtVersion(18)
        val original = database.readBytes()

        val receipt = DatabaseDowngradeGuard.inspect(database, currentVersion = 16)

        assertTrue(receipt!!.dataWasPreserved)
        val preserved = File(receipt.preservedPath!!)
        assertTrue(preserved.isFile)
        assertArrayEqualsBytes(original, preserved.readBytes())
    }

    /**
     * Copy, not move. If anything downstream fails, the app still has a database
     * where Room expects one and still starts.
     */
    @Test
    fun `the original database is left in place`() {
        val database = sqliteFileAtVersion(18)

        DatabaseDowngradeGuard.inspect(database, currentVersion = 16)

        assertTrue(database.isFile)
        assertEquals(18, DatabaseDowngradeGuard.readOnDiskVersion(database))
    }

    @Test
    fun `the write-ahead log is preserved too, or the copy would be stale`() {
        val database = sqliteFileAtVersion(18)
        File(dir, "freevibe.db-wal").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "freevibe.db-shm").writeBytes(byteArrayOf(4, 5))

        val receipt = DatabaseDowngradeGuard.inspect(database, currentVersion = 16)

        val preserved = File(receipt!!.preservedPath!!)
        assertTrue(File(preserved.parentFile, preserved.name + "-wal").isFile)
        assertTrue(File(preserved.parentFile, preserved.name + "-shm").isFile)
    }

    @Test
    fun `a second downgrade replaces the older copy rather than accumulating`() {
        DatabaseDowngradeGuard.inspect(sqliteFileAtVersion(18), currentVersion = 16)
        val receipt = DatabaseDowngradeGuard.inspect(sqliteFileAtVersion(20), currentVersion = 16)

        assertEquals(20, receipt!!.fromVersion)
        val copies = dir.listFiles { file -> file.name.contains(DatabaseDowngradeGuard.PRESERVED_SUFFIX) }
        assertEquals(1, copies?.size)
        assertEquals(20, DatabaseDowngradeGuard.readOnDiskVersion(File(receipt.preservedPath!!)))
    }

    @Test
    fun `a missing sidecar is simply not copied`() {
        val receipt = DatabaseDowngradeGuard.inspect(sqliteFileAtVersion(18), currentVersion = 16)

        val preserved = File(receipt!!.preservedPath!!)
        assertFalse(File(preserved.parentFile, preserved.name + "-wal").exists())
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        assertTrue(expected.contentEquals(actual))
    }
}
