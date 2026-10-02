package com.chloemlla.aura.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the client's vote paths and leaderboard bound in step with database.rules.json, where
 * voter identities live only in the owner-readable marker tree.
 */
class VoteSchemaContractTest {

    private val rules: JsonObject =
        Json.parseToJsonElement(File("../database.rules.json").readText()).jsonObject.getValue("rules").jsonObject

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `public count tree is readable per row and only through the bounded leaderboard query`() {
        val counts = rules.obj(VOTE_COUNTS_PATH)
        val collectionRead = counts.text(".read")

        assertTrue(collectionRead.contains("query.orderByChild == 'upvotes'"))
        assertTrue(collectionRead.contains("query.limitToLast <= $TOP_VOTED_MAX_LIMIT"))
        assertEquals("upvotes", counts.getValue(".indexOn").jsonArray[0].jsonPrimitive.content)
        assertEquals("true", counts.obj("\$contentId").text(".read"))
    }

    @Test
    fun `vote markers and legacy voter trees are never public`() {
        assertEquals(
            "auth != null && (auth.uid === \$uid || auth.token.admin === true)",
            rules.obj(VOTE_MARKERS_PATH).obj("\$uid").text(".read"),
        )
        for (legacyRoot in listOf("votes", "voters")) {
            val root = rules.obj(legacyRoot)
            assertEquals("auth != null && auth.token.admin === true", root.text(".read"))
            assertFalse(root.obj("\$contentId").containsKey(".read"))
            assertFalse(root.obj("\$contentId").containsKey("\$voterId") && root.obj("\$contentId").obj("\$voterId").containsKey(".read"))
        }
        val follows = rules.obj("creator_follows")
        assertFalse(follows.containsKey(".read"))
        assertEquals(
            "auth != null && (auth.uid === \$followerUid || auth.token.admin === true)",
            follows.obj("\$followerUid").text(".read"),
        )
    }

    @Test
    fun `leaderboard rows sort high to low, drop empty counts and respect the rules bound`() {
        val ascending = listOf("c" to 0, "b" to 2, "a" to 7, "d" to 3)

        assertEquals(listOf("a" to 7, "d" to 3, "b" to 2), topVotedRows(ascending, limit = 50))
        assertEquals(listOf("a" to 7), topVotedRows(ascending, limit = 1))

        val many = (1..300).map { "id$it" to it }
        assertEquals(TOP_VOTED_MAX_LIMIT, topVotedRows(many, limit = 500).size)
    }
}
