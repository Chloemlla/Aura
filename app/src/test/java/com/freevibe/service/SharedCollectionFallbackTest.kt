package com.freevibe.service

import com.freevibe.data.repository.CommunityCallableException
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SharedCollectionFallbackTest {

    @Test
    fun `a share function that isn't deployed falls back to the direct write`() = runTest {
        for (code in listOf("NOT_FOUND", "UNIMPLEMENTED")) {
            var directWrites = 0
            val token = publishShareWithFallback(
                publishViaBackend = { throw CommunityCallableException("publishSharedCollection", code, "missing") },
                publishDirectly = { directWrites++; "direct_token" },
            )
            assertEquals(code, "direct_token", token)
            assertEquals(code, 1, directWrites)
        }
    }

    @Test
    fun `any other backend refusal still fails the link without writing directly`() = runTest {
        for (code in listOf("RESOURCE_EXHAUSTED", "PERMISSION_DENIED", "INVALID_ARGUMENT", "UNAUTHENTICATED")) {
            var directWrites = 0
            val refusal = CommunityCallableException("publishSharedCollection", code, "refused")
            try {
                publishShareWithFallback(
                    publishViaBackend = { throw refusal },
                    publishDirectly = { directWrites++; "direct_token" },
                )
                fail("$code should not fall back")
            } catch (e: CommunityCallableException) {
                assertSame(refusal, e)
            }
            assertEquals(code, 0, directWrites)
        }
    }

    @Test
    fun `a deployed share function is used and the direct write is never touched`() = runTest {
        var directWrites = 0
        val token = publishShareWithFallback(
            publishViaBackend = { "server_token" },
            publishDirectly = { directWrites++; "direct_token" },
        )
        assertEquals("server_token", token)
        assertEquals(0, directWrites)
    }

    @Test
    fun `the direct record carries the fields the share function stores and the same 30 day expiry`() {
        val record = directSharedCollectionRecord(
            json = "{}",
            collectionName = "Night",
            itemCount = 3,
            creatorUid = "uid_1",
            nowMillis = 1_000L,
        )
        val handler = File("../functions/src/collectionShareHandler.ts").readText()
        val serverFields = Regex("""export interface SharedCollectionRecord \{([^}]*)\}""")
            .find(handler)!!.groupValues[1]
            .let { Regex("""readonly (\w+):""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        assertFalse(serverFields.isEmpty())
        assertEquals(serverFields, record.keys)

        assertEquals(1_000L, record["createdAt"])
        assertEquals(1_000L + 30L * 24 * 60 * 60 * 1000, record["expiresAt"])
        assertEquals("uid_1", record["createdByUid"])
        assertEquals(3, record["itemCount"])
        val serverTtl = Regex("""SHARED_COLLECTION_TTL_MILLIS = (\d+) \* DAY_MILLIS""").find(handler)!!.groupValues[1]
        assertEquals(serverTtl.toLong() * 24 * 60 * 60 * 1000, SHARED_COLLECTION_TTL_MILLIS)
    }
}
