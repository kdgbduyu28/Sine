package com.sine.player.library

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimilarAndTrailerTest {
    private fun e(title: String, year: Int?, rating: Double?, vararg genres: String) = GridEntry(title, title, year, rating, genres.toList(), 0, false)

    @Test
    fun `ranks by shared genres, then year, then rating`() {
        val dune = e("Dune", 2021, 7.8, "Science Fiction", "Adventure")
        val candidates = listOf(
            dune,
            e("Arrival", 2016, 7.9, "Science Fiction", "Drama"),
            e("Dune: Part Two", 2024, 8.2, "Science Fiction", "Adventure"),
            e("Blade Runner 2049", 2017, 8.0, "Science Fiction", "Drama"),
            e("Paddington", 2014, 7.3, "Comedy", "Family"),
        )
        assertEquals(listOf("Dune: Part Two", "Blade Runner 2049", "Arrival"), LibraryFilter.similar(dune, candidates).map { it.title })
        assertEquals(emptyList<GridEntry<String>>(), LibraryFilter.similar(e("No genres", 2020, null), candidates))
    }

    @Test
    fun `picks the official YouTube trailer`() {
        val json = JSONObject(
            """{"results":[
                {"site":"YouTube","type":"Featurette","key":"feat","official":true},
                {"site":"Vimeo","type":"Trailer","key":"vimeo","official":true},
                {"site":"YouTube","type":"Teaser","key":"teaser","official":true},
                {"site":"YouTube","type":"Trailer","key":"fan","official":false},
                {"site":"YouTube","type":"Trailer","key":"official","official":true}]}""",
        )
        assertEquals("official", Trailers.pick(json))
        assertEquals("teaser", Trailers.pick(JSONObject("""{"results":[{"site":"YouTube","type":"Teaser","key":"teaser"}]}""")))
        assertNull(Trailers.pick(JSONObject("""{"results":[{"site":"YouTube","type":"Clip","key":"c"}]}""")))
    }
}
