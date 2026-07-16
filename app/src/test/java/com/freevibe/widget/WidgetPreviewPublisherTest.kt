package com.freevibe.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPreviewPublisherTest {

    @Test
    fun `generated previews require Android 15`() {
        assertFalse(
            WidgetPreviewPublisher.shouldPublish(
                sdkInt = 34,
                publishedVersion = -1,
                currentVersion = 136,
            ),
        )
    }

    @Test
    fun `current app version publishes once`() {
        assertTrue(
            WidgetPreviewPublisher.shouldPublish(
                sdkInt = 35,
                publishedVersion = 135,
                currentVersion = 136,
            ),
        )
        assertFalse(
            WidgetPreviewPublisher.shouldPublish(
                sdkInt = 35,
                publishedVersion = 136,
                currentVersion = 136,
            ),
        )
    }
}
