package com.chloemlla.aura.service

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Av1CodecSupportTest {

    @Test
    fun `preferredVideoMimeTypes always contains AVC`() {
        val support = Av1CodecSupport()
        assertTrue(support.preferredVideoMimeTypes().contains(MediaFormat.MIMETYPE_VIDEO_AVC))
    }

    @Test
    fun `preferredVideoMimeTypes always contains HEVC`() {
        val support = Av1CodecSupport()
        assertTrue(support.preferredVideoMimeTypes().contains(MediaFormat.MIMETYPE_VIDEO_HEVC))
    }

    @Test
    fun `preferredVideoMimeTypes has at least two entries`() {
        val support = Av1CodecSupport()
        assertTrue(support.preferredVideoMimeTypes().size >= 2)
    }

    @Test
    fun `hasHardwareAv1Decode does not throw in unit test environment`() {
        val support = Av1CodecSupport()
        // MediaCodecList is not available in Robolectric, so this returns false
        assertEquals(false, support.hasHardwareAv1Decode)
    }

    @Test
    fun `legacy software codec heuristic identifies framework software decoders`() {
        val support = Av1CodecSupport()

        assertEquals(true, support.isKnownSoftwareCodec("OMX.google.av1.decoder"))
        assertEquals(true, support.isKnownSoftwareCodec("c2.android.av1.decoder"))
        assertEquals(true, support.isKnownSoftwareCodec("vendor.sw.av1.decoder"))
    }

    @Test
    fun `legacy software codec heuristic keeps vendor hardware decoders eligible`() {
        val support = Av1CodecSupport()

        assertEquals(false, support.isKnownSoftwareCodec("OMX.qcom.video.decoder.av1"))
        assertEquals(false, support.isKnownSoftwareCodec("c2.qti.av1.decoder"))
    }
}
