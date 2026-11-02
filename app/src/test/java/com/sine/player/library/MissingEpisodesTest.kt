package com.sine.player.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MissingEpisodesTest {

    private fun ep(aired: String?) = EpisodeMeta("Name", null, null, aired)

    @Test
    fun `lists aired episodes of the season that aren't in the library`() {
        val meta = mapOf(
            "1:1" to ep("2020-01-01"),
            "1:2" to ep("2020-01-08"),
            "1:3" to ep("2020-01-15"),
            "1:4" to ep("2031-01-01"),
            "1:5" to ep(null),
            "2:1" to ep("2021-01-01"),
        )
        val missing = MissingEpisodes.forSeason(meta, 1, setOf(1, 3), "2026-11-02")
        assertEquals(listOf(2), missing.map { it.first })
    }

    @Test
    fun `nothing missing when every aired episode is there`() {
        val meta = mapOf("3:1" to ep("2020-01-01"), "3:2" to ep("2020-01-08"))
        assertEquals(emptyList<Int>(), MissingEpisodes.forSeason(meta, 3, setOf(1, 2), "2026-11-02").map { it.first })
    }
}
