package com.sine.player.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SineSearchTest {
    @Test
    fun `every word must start a word of the title`() {
        assertTrue(SineSearch.matches("Dune: Part Two", "dun par"))
        assertTrue(SineSearch.matches("Dune: Part Two", "TWO"))
        assertFalse(SineSearch.matches("Dune: Part Two", "art"))
        assertFalse(SineSearch.matches("Dune", "dune three"))
    }

    @Test
    fun `ignores accents and punctuation`() {
        assertTrue(SineSearch.matches("Amélie", "amelie"))
        assertEquals("spider man no way home", SineSearch.normalize("Spider-Man: No Way Home"))
    }

    @Test
    fun `finds titles by actor or director`() {
        val meta = com.sine.player.library.Meta(null, "Dune", null, null, null, null, emptyList(), 2021, cast = listOf("Timothée Chalamet", "Zendaya"), director = "Denis Villeneuve")
        assertEquals("Zendaya", SineSearch.person(meta, "zendaya"))
        assertEquals("Denis Villeneuve", SineSearch.person(meta, "villeneuve"))
        assertEquals("Timothée Chalamet", SineSearch.person(meta, "timothee"))
        assertEquals(null, SineSearch.person(meta, "ze"))
    }
}
