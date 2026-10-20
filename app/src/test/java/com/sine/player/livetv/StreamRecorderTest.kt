package com.sine.player.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRecorderTest {
    @Test
    fun `picks the highest bandwidth variant`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            hi/index.m3u8
        """.trimIndent()
        assertEquals("http://cdn/ch/hi/index.m3u8", StreamRecorder.bestVariant(master, "http://cdn/ch/master.m3u8?t=1"))
        assertNull(StreamRecorder.bestVariant("#EXTM3U\n#EXTINF:6,\na.ts", "http://cdn/x.m3u8"))
    }

    @Test
    fun `reads segments with sequence numbers, init and encryption`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg100.m4s
            #EXTINF:6.0,
            /abs/seg101.m4s
        """.trimIndent()
        val p = StreamRecorder.parseMedia(media, "http://cdn/ch/hi/index.m3u8")
        assertEquals(listOf(100L to "http://cdn/ch/hi/seg100.m4s", 101L to "http://cdn/abs/seg101.m4s"), p.segments)
        assertEquals("http://cdn/ch/hi/init.mp4", p.initUri)
        assertEquals(6, p.targetDurationS)
        assertFalse(p.encrypted)
        assertFalse(p.ended)
        assertTrue(StreamRecorder.parseMedia("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXT-X-ENDLIST", "http://a/b.m3u8").encrypted)
    }

    @Test
    fun `file names avoid characters file systems reject`() {
        val name = StreamRecorder.fileName("BBC One", "News: Late/Night?", 1_792_492_200_000L, "ts")
        assertTrue(name.startsWith("BBC One - News_ Late_Night_ - 2026-10-"))
        assertTrue(name.endsWith(".ts"))
    }
}
