package com.chloemlla.aura.ui.screens.sounds

import org.junit.Assert.assertEquals
import org.junit.Test

class SoundDurationTest {

    @Test
    fun `subsecond tones never look empty`() {
        assertEquals("<1s", formatSoundFeedDuration(0.58))
        assertEquals("<1s", formatSoundDetailDuration(0.99))
    }

    @Test
    fun `ordinary durations preserve feed and detail formats`() {
        assertEquals("0:11", formatSoundFeedDuration(11.73))
        assertEquals("1:05", formatSoundFeedDuration(65.9))
        assertEquals("11s", formatSoundDetailDuration(11.73))
        assertEquals("1m 5s", formatSoundDetailDuration(65.9))
    }

    @Test
    fun `unknown durations remain explicit zero values`() {
        assertEquals("0:00", formatSoundFeedDuration(Double.NaN))
        assertEquals("0s", formatSoundDetailDuration(-1.0))
    }
}
