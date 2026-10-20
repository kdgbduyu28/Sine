package com.sine.player.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProxyTest {

    @Test
    fun `parses open, closed and suffix ranges`() {
        assertEquals(ByteRange(100, null), ByteRange.parse("bytes=100-"))
        assertEquals(ByteRange(0, 499), ByteRange.parse("bytes=0-499"))
        assertEquals(ByteRange(0, null, 500), ByteRange.parse("bytes=-500"))
        assertEquals(ByteRange(10, 20), ByteRange.parse("bytes=10-20, 30-40"))
        assertNull(ByteRange.parse(null))
        assertNull(ByteRange.parse("items=0-1"))
        assertNull(ByteRange.parse("bytes=20-10"))
    }

    @Test
    fun `resolves ranges against the file length`() {
        assertEquals(100L..999L, ByteRange(100, null).resolve(1000))
        assertEquals(0L..999L, ByteRange(0, 5000).resolve(1000))
        assertEquals(500L..999L, ByteRange(0, null, 500).resolve(1000))
        assertNull(ByteRange(1000, null).resolve(1000))
        // Unknown length: an open range can't be bounded, but an explicit end can.
        assertEquals(0L..99L, ByteRange(0, 99).resolve(-1))
    }

    @Test
    fun `builds partial and full responses`() {
        val partial = StreamProxy.responseHead("smb://nas/Movies/a.mkv", 1000, 100L..199L)
        assertTrue(partial.startsWith("HTTP/1.1 206"))
        assertTrue(partial.contains("Content-Range: bytes 100-199/1000\r\n"))
        assertTrue(partial.contains("Content-Length: 100\r\n"))
        assertTrue(partial.contains("Content-Type: video/x-matroska\r\n"))
        assertTrue(partial.endsWith("\r\n\r\n"))
        val full = StreamProxy.responseHead("sftp://host/a.srt", 42, null)
        assertTrue(full.startsWith("HTTP/1.1 200"))
        assertTrue(full.contains("Content-Length: 42\r\n"))
        assertTrue(full.contains("application/x-subrip"))
    }

    @Test
    fun `mpv fallback triggers on unsupported containers and codecs only`() {
        assertTrue(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED in EngineFallback.mpvErrorCodes)
        assertTrue(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED in EngineFallback.mpvErrorCodes)
        assertTrue(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED !in EngineFallback.mpvErrorCodes)
    }

    @Test
    fun `detects files with no playable video or audio`() {
        fun g(type: Int, ok: Boolean) = EngineFallback.GroupSupport(type, ok)
        assertEquals("video", EngineFallback.unsupportedType(listOf(g(C.TRACK_TYPE_VIDEO, false), g(C.TRACK_TYPE_AUDIO, true))))
        assertEquals("audio", EngineFallback.unsupportedType(listOf(g(C.TRACK_TYPE_VIDEO, true), g(C.TRACK_TYPE_AUDIO, false), g(C.TRACK_TYPE_AUDIO, false))))
        assertNull(EngineFallback.unsupportedType(listOf(g(C.TRACK_TYPE_VIDEO, true), g(C.TRACK_TYPE_AUDIO, false), g(C.TRACK_TYPE_AUDIO, true))))
        assertNull(EngineFallback.unsupportedType(listOf(g(C.TRACK_TYPE_VIDEO, true), g(C.TRACK_TYPE_TEXT, false))))
        assertNull(EngineFallback.unsupportedType(emptyList()))
    }
}
