package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BuffersTest {
    private val mb = BufferPlan.MB

    @Test
    fun `normal keeps the defaults`() {
        assertEquals(BufferPlan.DEFAULT, Buffers.plan(BufferSize.NORMAL, 512, 4096))
    }

    @Test
    fun `large and huge scale with the heap and RAM`() {
        // 2 GB box with a 384 MB large heap.
        val large = Buffers.plan(BufferSize.LARGE, 384, 2048)
        assertEquals(134 * mb, large.media3Bytes)
        assertEquals(400L * mb, large.mpvBytes)
        val huge = Buffers.plan(BufferSize.HUGE, 384, 2048)
        assertEquals(230 * mb, huge.media3Bytes)
        assertEquals(512L * mb, huge.mpvBytes) // a quarter of 2 GB
        assertEquals(600_000, huge.maxBufferMs)
    }

    @Test
    fun `caps and floors apply`() {
        assertEquals(512 * mb, Buffers.plan(BufferSize.HUGE, 1024, 8192).media3Bytes)
        assertEquals(800L * mb, Buffers.plan(BufferSize.HUGE, 1024, 8192).mpvBytes)
        assertEquals(64 * mb, Buffers.plan(BufferSize.LARGE, 128, 512).media3Bytes)
        assertEquals(160L * mb, Buffers.plan(BufferSize.LARGE, 128, 512).mpvBytes)
    }
}
