package com.sine.player.source

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseSortingTest {
    private val entries = listOf(
        MediaEntry("A folder", "f://a", isDirectory = true, modified = 1),
        MediaEntry("a.mkv", "f://a.mkv", isDirectory = false, size = 10, modified = 3),
        MediaEntry("b.mkv", "f://b.mkv", isDirectory = false, size = 30, modified = 1),
        MediaEntry("c.mkv", "f://c.mkv", isDirectory = false, size = 20, modified = 2),
    )

    private fun names(sort: BrowseSort) = BrowseSorting.apply(entries, sort).map { it.name }

    @Test
    fun `folders first, then files by the chosen order`() {
        assertEquals(listOf("A folder", "a.mkv", "b.mkv", "c.mkv"), names(BrowseSort.NAME))
        assertEquals(listOf("A folder", "a.mkv", "c.mkv", "b.mkv"), names(BrowseSort.NEWEST))
        assertEquals(listOf("A folder", "b.mkv", "c.mkv", "a.mkv"), names(BrowseSort.LARGEST))
    }
}
