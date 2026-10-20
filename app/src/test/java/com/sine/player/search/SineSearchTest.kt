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
}
