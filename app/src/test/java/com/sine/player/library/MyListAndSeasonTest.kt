package com.sine.player.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MyListAndSeasonTest {
    private fun ep(s: Int, e: Int) = Episode(s, e, VideoFile("file:///s${s}e$e.mkv", "file:///", "s${s}e$e.mkv", 0, 0))

    private val episodes = listOf(ep(0, 1), ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1), ep(2, 2))

    private fun codes(list: List<Episode>) = list.map { "${it.season}x${it.number}" }

    @Test
    fun `up to here covers earlier seasons and skips specials`() {
        assertEquals(listOf("1x1", "1x2", "1x3", "2x1"), codes(SeasonMarks.upTo(episodes, ep(2, 1))))
        assertEquals(listOf("1x1"), codes(SeasonMarks.upTo(episodes, ep(1, 1))))
    }

    @Test
    fun `a special only marks itself`() {
        assertEquals(listOf("0x1"), codes(SeasonMarks.upTo(episodes, ep(0, 1))))
    }

    @Test
    fun `season picks one season`() {
        assertEquals(listOf("2x1", "2x2"), codes(SeasonMarks.season(episodes, 2)))
    }

    @Test
    fun `list keys round-trip without blanks or duplicates`() {
        val keys = listOf("movie:dune-2021", "show:the-bear", "movie:dune-2021")
        assertEquals(listOf("movie:dune-2021", "show:the-bear"), MyList.decode(MyList.encode(keys)))
        assertEquals(emptyList<String>(), MyList.decode(""))
    }
}
