package com.freevibe.ui.screens.sounds

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SoundPipelineContractTest {

    @Test
    fun `deleted top hits and reddit api symbols stay out of source profiles`() {
        val forbiddenSymbols = listOf("topHits", "fetchTopHits", "RedditApi", "provideRedditApi")
        val files = File("src/main")
            .walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "txt") }

        files.forEach { file ->
            val source = file.readText()
            forbiddenSymbols.forEach { symbol ->
                assertFalse("${file.path} should not reference deleted symbol $symbol", source.contains(symbol))
            }
        }
    }
}
