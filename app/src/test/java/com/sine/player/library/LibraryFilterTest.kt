package com.sine.player.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterTest {
    private fun e(title: String, year: Int?, rating: Double?, genres: List<String>, added: Long, watched: Boolean = false) =
        GridEntry(title, title, year, rating, genres, added, watched)

    private val all = listOf(
        e("The Bear", 2022, 8.6, listOf("Comedy", "Drama"), 3),
        e("Arrival", 2016, 7.9, listOf("Drama", "Science Fiction"), 1, watched = true),
        e("Dune", 2021, null, listOf("Science Fiction"), 2),
        e("Alien", null, 8.5, listOf("Horror", "Science Fiction"), 4),
    )

    private fun titles(view: LibraryView) = LibraryFilter.apply(all, view).map { it.title }

    @Test
    fun `sorts four ways`() {
        assertEquals(listOf("Alien", "The Bear", "Dune", "Arrival"), titles(LibraryView(LibrarySort.ADDED)))
        assertEquals(listOf("Alien", "Arrival", "The Bear", "Dune"), titles(LibraryView(LibrarySort.TITLE)))
        assertEquals(listOf("The Bear", "Dune", "Arrival", "Alien"), titles(LibraryView(LibrarySort.YEAR)))
        assertEquals(listOf("The Bear", "Alien", "Arrival", "Dune"), titles(LibraryView(LibrarySort.RATING)))
    }

    @Test
    fun `filters unwatched and by genre`() {
        assertEquals(listOf("Alien", "Dune"), titles(LibraryView(LibrarySort.TITLE, unwatchedOnly = true, genre = "science fiction")))
        assertEquals(listOf("Arrival", "The Bear"), titles(LibraryView(LibrarySort.TITLE, genre = "Drama")))
    }

    @Test
    fun `lists genres by how common they are`() {
        assertEquals(listOf("Science Fiction", "Drama", "Comedy", "Horror"), LibraryFilter.genres(all))
    }

    @Test
    fun `round-trips the remembered view`() {
        val v = LibraryView(LibrarySort.RATING, true, "Drama")
        assertEquals(v, LibraryView.decode(v.encode()))
        assertEquals(LibraryView(), LibraryView.decode(""))
        assertEquals(LibraryView(), LibraryView.decode(null))
    }

    @Test
    fun `ignores leading articles`() {
        assertEquals("bear", LibraryFilter.sortTitle("The Bear"))
        assertEquals('B', LibraryFilter.initial("The Bear"))
        assertEquals('#', LibraryFilter.initial("2001: A Space Odyssey"))
    }
}
