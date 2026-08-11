package com.chloemlla.aura.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreviewCacheTest {

    @Test
    fun `prebuffer accepts only progressive mp4 and webm urls`() {
        assertTrue(shouldPrebufferVideoPreview("https://cdn.example.com/loop.mp4"))
        assertTrue(shouldPrebufferVideoPreview("https://cdn.example.com/loop.WEBM?token=abc"))
        assertTrue(
            shouldPrebufferVideoPreview(
                "https://googlevideo.example/videoplayback?expire=1&mime=video%2Fmp4&token=abc",
            ),
        )
        assertFalse(shouldPrebufferVideoPreview("https://cdn.example.com/master.m3u8?token=abc"))
        assertFalse(shouldPrebufferVideoPreview("https://i.redd.it/loop.gif"))
        assertFalse(shouldPrebufferVideoPreview("https://cdn.example.com/video"))
        assertFalse(shouldPrebufferVideoPreview("https://cdn.example.com/video?mime=video%ZZmp4"))
        assertFalse(shouldPrebufferVideoPreview("content://media/external/video/42.mp4"))
    }
}
