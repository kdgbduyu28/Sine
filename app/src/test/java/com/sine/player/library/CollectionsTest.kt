package com.sine.player.library

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsTest {
    private val lotr = MovieCollection(119, "The Lord of the Rings Collection", null, null)
    private val dune = MovieCollection(726871, "Dune Collection", null, null)

    @Test
    fun `reads belongs_to_collection`() {
        val c = Tmdb.collection(JSONObject("""{"belongs_to_collection":{"id":119,"name":"The Lord of the Rings Collection","poster_path":"/p.jpg","backdrop_path":null}}"""))!!
        assertEquals(119, c.id)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", c.poster)
        assertNull(c.backdrop)
        assertNull(Tmdb.collection(JSONObject("""{"belongs_to_collection":null}""")))
    }

    @Test
    fun `groups two or more films in release order`() {
        val grouped = Library.groupCollections(
            listOf(
                Triple("Two Towers", lotr, 2002),
                Triple("Fellowship", lotr, 2001),
                Triple("Return", lotr, null),
                Triple("Dune", dune, 2021),
            ),
        )
        assertEquals(1, grouped.size)
        assertEquals(listOf("Fellowship", "Two Towers", "Return"), grouped.single().second)
    }

    @Test
    fun `old saved details are marked for a fresh lookup`() {
        val old = JSONObject("""{"tmdb":1,"title":"Dune","genres":[],"year":2021,"at":5}""")
        assertFalse(LibraryStore.decodeMeta(old).collectionKnown)
        val saved = LibraryStore.decodeMeta(LibraryStore.encodeMeta(Meta(1, "Dune", null, null, null, null, emptyList(), 2021, collection = dune)))
        assertTrue(saved.collectionKnown)
        assertEquals(dune, saved.collection)
        val none = LibraryStore.decodeMeta(LibraryStore.encodeMeta(Meta(2, "Solo", null, null, null, null, emptyList(), 2018)))
        assertTrue(none.collectionKnown)
        assertNull(none.collection)
    }

    @Test
    fun `collection parts not in the library come in release order`() {
        val parts = listOf(
            CollectionPart(3, "Third", "2003-12-17", null),
            CollectionPart(1, "First", "2001-12-19", null),
            CollectionPart(9, "Announced", null, null),
            CollectionPart(2, "Second", "2002-12-18", null),
        )
        assertEquals(listOf(3, 9), CollectionPart.missing(parts, setOf(1, 2)).map { it.tmdbId })
    }
}
