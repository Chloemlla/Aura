package com.chloemlla.aura.data.local

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reset happens during database construction, before any screen exists. The
 * receipt has to outlive that gap, and outlive process death, or the warning is
 * lost exactly when it matters.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseDowngradeReceiptStoreTest {

    private fun store() = DatabaseDowngradeReceiptStore(ApplicationProvider.getApplicationContext())

    private fun receipt(preservedPath: String? = "/data/freevibe.db.pre-downgrade") =
        DatabaseDowngradeReceipt(
            detectedUtc = "2026-08-20 12:00:00Z",
            fromVersion = 18,
            toVersion = 16,
            preservedPath = preservedPath,
        )

    @Test
    fun `a fresh install has nothing to warn about`() {
        assertNull(store().read())
    }

    @Test
    fun `a recorded downgrade reads back intact`() {
        store().record(receipt())

        val read = store().read()

        assertNotNull(read)
        assertEquals("2026-08-20 12:00:00Z", read!!.detectedUtc)
        assertEquals(18, read.fromVersion)
        assertEquals(16, read.toVersion)
        assertEquals("/data/freevibe.db.pre-downgrade", read.preservedPath)
        assertTrue(read.dataWasPreserved)
    }

    /** A different store instance is what the UI actually gets after process death. */
    @Test
    fun `the receipt survives a new store instance`() {
        store().record(receipt())

        assertNotNull(store().read())
    }

    @Test
    fun `a downgrade with no copy is distinguishable from one with a copy`() {
        store().record(receipt(preservedPath = null))

        val read = store().read()

        assertNotNull(read)
        assertNull(read!!.preservedPath)
        assertFalse(read.dataWasPreserved)
    }

    @Test
    fun `acknowledging clears it so the warning is shown once`() {
        val subject = store()
        subject.record(receipt())

        subject.acknowledge()

        assertNull(subject.read())
    }

    @Test
    fun `acknowledging nothing is harmless`() {
        val subject = store()

        subject.acknowledge()

        assertNull(subject.read())
    }

    @Test
    fun `a second downgrade replaces the first receipt`() {
        val subject = store()
        subject.record(receipt())

        subject.record(
            DatabaseDowngradeReceipt(
                detectedUtc = "2026-08-21 09:00:00Z",
                fromVersion = 20,
                toVersion = 16,
                preservedPath = null,
            ),
        )

        val read = subject.read()
        assertEquals(20, read!!.fromVersion)
        assertNull(read.preservedPath)
    }
}
