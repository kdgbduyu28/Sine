package com.sine.player.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySlidesTest {
    @Test
    fun `builds the detail line from what is known`() {
        assertEquals("2016 · ★ 7.9 · Drama, Science Fiction", LibrarySlides.detail(2016, 7.9, listOf("Drama", "Science Fiction", "Mystery")))
        assertEquals("2016", LibrarySlides.detail(2016, null, emptyList()))
        assertEquals("", LibrarySlides.detail(null, null, emptyList()))
    }
}
