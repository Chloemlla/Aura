package com.freevibe.data.repository

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoteCountsOnceTest {

    @Test
    fun `missing rows and failed reads are left out so callers keep their own count`() = runTest {
        val reads = mutableListOf<String>()

        val counts = collectVoteCounts(listOf("present", "missing", "broken", "present", "zero")) { id ->
            reads += id
            when (id) {
                "present" -> 7
                "zero" -> 0
                "broken" -> throw IOException("offline")
                else -> null
            }
        }

        assertEquals(mapOf("present" to 7, "zero" to 0), counts)
        assertEquals(setOf("present", "missing", "broken", "zero"), reads.toSet())
        assertEquals("each id is read once", 4, reads.size)
    }

    @Test
    fun `creator uploads keep their stored votes when the public count is unavailable`() = runTest {
        val counts = collectVoteCounts(listOf("a", "b")) { id -> if (id == "a") 12 else null }
        val stored = mapOf("a" to 3, "b" to 5)

        val merged = stored.mapValues { (id, votes) -> counts[id] ?: votes }

        assertEquals(mapOf("a" to 12, "b" to 5), merged)
    }
}
