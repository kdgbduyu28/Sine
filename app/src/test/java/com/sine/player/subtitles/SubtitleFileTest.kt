package com.sine.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFileTest {
    private val srt = "1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:00,000 --> 00:01:02,250\r\nSecond\r\n"
    private val ass = """
        [Script Info]
        Title: x

        [V4+ Styles]
        Style: Default,Arial,20

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:05.10,0:00:07.00,Default,,0,0,0,,{\i1}Line, with comma\Nnext 0:00:09.00
    """.trimIndent()

    @Test
    fun `parses SRT with tags and CRLF`() {
        val cues = SubtitleFile.parse(srt, SubtitleFile.format("a.srt", srt))
        assertEquals(listOf(Cue(1500, 3000, "Hello\nthere"), Cue(60_000, 62_250, "Second")), cues)
    }

    @Test
    fun `parses ASS dialogue with commas in text`() {
        val cues = SubtitleFile.parse(ass, SubtitleFile.format("a.ass", ass))
        assertEquals(listOf(Cue(5100, 7000, "Line, with comma\nnext 0:00:09.00")), cues)
    }

    @Test
    fun `parses WebVTT short timestamps`() {
        val vtt = "WEBVTT\n\n00:05.000 --> 00:06.500 align:start\nHi\n"
        assertEquals(listOf(Cue(5000, 6500, "Hi")), SubtitleFile.parse(vtt, SubtitleFile.format("a.vtt", vtt)))
    }

    @Test
    fun `retimes in place keeping everything else`() {
        val shifted = SubtitleFile.retime(srt, SubtitleFormat.SRT, SubtitleFile.Retime(2000))
        assertTrue(shifted.contains("00:00:03,500 --> 00:00:05,000"))
        assertTrue(shifted.contains("<i>Hello</i>"))
        val assShifted = SubtitleFile.retime(ass, SubtitleFormat.ASS, SubtitleFile.Retime(-1000))
        assertTrue(assShifted.contains("Dialogue: 0,0:00:04.10,0:00:06.00,Default"))
        // A time-like string inside the text stays untouched.
        assertTrue(assShifted.contains("next 0:00:09.00"))
    }

    @Test
    fun `scales for frame-rate fixes and never goes negative`() {
        val r = SubtitleFile.Retime(-5000, 25.0 / 23.976)
        assertEquals(0L, r.apply(1000))
        assertEquals(57_563L, r.apply(60_000))
    }

    @Test
    fun `decodes Windows-1252 when not UTF-8`() {
        val bytes = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())
        assertEquals("café", SubtitleFile.decode(bytes))
        assertEquals("café", SubtitleFile.decode("\uFEFFcafé".toByteArray()))
    }

    @Test
    fun `writes SRT`() {
        assertEquals("1\n00:00:01,500 --> 01:00:00,000\nHi\n\n", SubtitleFile.toSrt(listOf(Cue(1500, 3_600_000, "Hi"))))
    }
}
