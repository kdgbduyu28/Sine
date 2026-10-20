package com.sine.player.music

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
    private val lrc = """
        [ti:Song]
        [offset:+500]
        [00:12.50]First line
        [00:20.00][01:05.3]Chorus
        [00:15]Second line
        not a lyric
    """.trimIndent()

    @Test
    fun `parses stamps, repeats and offset`() {
        val lines = Lyrics.parse(lrc)
        assertEquals(listOf(12_000L, 14_500L, 19_500L, 64_800L), lines.map { it.timeMs })
        assertEquals(listOf("First line", "Second line", "Chorus", "Chorus"), lines.map { it.text })
    }

    @Test
    fun `finds the current line`() {
        val lines = Lyrics.parse(lrc)
        assertEquals(-1, Lyrics.currentIndex(lines, 5_000))
        assertEquals(0, Lyrics.currentIndex(lines, 12_000))
        assertEquals(1, Lyrics.currentIndex(lines, 18_000))
        assertEquals(3, Lyrics.currentIndex(lines, 99_000))
    }
}
