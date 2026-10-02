package com.chloemlla.aura.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class TikTokAudioSampleCopyTest {

    @Test
    fun `edit list lead-in is shifted to zero instead of ending the copy`() {
        // TikTok's AAC track starts 161 ms before zero; MediaExtractor reports those
        // frames at negative times, and MediaMuxer rejects negative stamps.
        val source = FakeSampleSource(listOf(-161_000L, -140_000L, -118_000L, 0L, 21_000L))
        val sink = RecordingSink()

        val copied = copyAudioSamples(source, sink, ByteBuffer.allocate(16))

        assertEquals(5, copied)
        assertEquals(listOf(0L, 21_000L, 43_000L, 161_000L, 182_000L), sink.times)
        assertEquals(List(5) { 3 }, sink.sizes)
    }

    @Test
    fun `a track that starts at zero keeps its stamps`() {
        val sink = RecordingSink()

        val copied = copyAudioSamples(FakeSampleSource(listOf(0L, 23_219L, 46_439L)), sink, ByteBuffer.allocate(16))

        assertEquals(3, copied)
        assertEquals(listOf(0L, 23_219L, 46_439L), sink.times)
    }

    @Test
    fun `an empty track copies nothing`() {
        assertEquals(0, copyAudioSamples(FakeSampleSource(emptyList()), RecordingSink(), ByteBuffer.allocate(16)))
    }

    private class FakeSampleSource(private val times: List<Long>) : AudioSampleSource {
        private var index = 0

        override fun readSample(buffer: ByteBuffer): Int {
            if (index >= times.size) return -1
            buffer.put(byteArrayOf(1, 2, 3))
            return 3
        }

        // MediaExtractor answers -1 once the track is exhausted.
        override val sampleTimeUs: Long get() = times.getOrElse(index) { -1L }
        override val isSyncSample: Boolean get() = true

        override fun advance() {
            index++
        }
    }

    private class RecordingSink : AudioSampleSink {
        val times = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()

        override fun write(buffer: ByteBuffer, size: Int, presentationTimeUs: Long, isSyncSample: Boolean) {
            times += presentationTimeUs
            sizes += size
        }
    }
}
