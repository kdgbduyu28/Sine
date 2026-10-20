package com.sine.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryTest {
    @Test
    fun `parses movie names from file or folder`() {
        assertEquals(NameParser.Movie("Dune Part Two", 2024), NameParser.movie("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX-FGT.mkv", "Dune Part Two (2024)"))
        assertEquals(NameParser.Movie("Arrival", 2016), NameParser.movie("arrival.mkv", "Arrival (2016)"))
        assertEquals(NameParser.Movie("1917", 2019), NameParser.movie("1917.2019.1080p.mkv", null))
        assertEquals(NameParser.Movie("Home video", null), NameParser.movie("Home video.mp4", null))
    }

    @Test
    fun `parses episodes from names and season folders`() {
        assertEquals(NameParser.Episode("The Bear", null, 3, 4), NameParser.episode("The Bear", listOf("Season 3"), "The.Bear.S03E04.1080p.mkv"))
        assertEquals(NameParser.Episode("Bluey", 2018, 2, 7), NameParser.episode("Bluey (2018)", listOf("Season 02"), "07 - Bike.mp4"))
        assertEquals(NameParser.Episode("Show", null, 1, 12), NameParser.episode("Show", emptyList(), "Episode 12.mkv"))
        assertEquals(0, NameParser.episode("Show", listOf("Specials"), "E01.mkv")!!.season)
        assertNull(NameParser.episode("Show", listOf("Season 1"), "Behind the scenes.mkv"))
    }

    @Test
    fun `recognises season folders and junk`() {
        assertEquals(3, NameParser.seasonOf("Season 3"))
        assertEquals(12, NameParser.seasonOf("S12"))
        assertNull(NameParser.seasonOf("Extras"))
        assertTrue(NameParser.isSkippedFolder("Featurettes"))
        assertTrue(NameParser.isSample("movie-sample.mkv", 50_000_000))
        assertFalse(NameParser.isSample("Sample Movie.mkv", 5_000_000_000))
        assertEquals(NameParser.key("Dune: Part Two", 2024), NameParser.key("dune part two", 2024))
    }

    @Test
    fun `library store round-trips`() {
        val file = File.createTempFile("library", ".json").apply { delete() }
        val store = LibraryStore(file)
        store.addRoot(LibraryRoot("smb://nas/Movies", LibraryKind.MOVIES))
        val video = VideoFile("smb://nas/Movies/Arrival (2016)/arrival.mkv", "smb://nas/Movies/Arrival (2016)", "arrival.mkv", 10, 20)
        store.replaceContents(
            listOf(Movie("arrival|2016", "Arrival", 2016, video, null, 5)),
            listOf(Show("bluey", "Bluey", null, "smb://nas/TV/Bluey", "smb://nas/TV/Bluey/poster.jpg", listOf(Episode(1, 1, video)), 6)),
        )
        store.putMeta("arrival|2016", Meta(329865, "Arrival", "Linguist…", Tmdb.image("/p.jpg", "w342"), null, 7.6, listOf("Drama"), 2016, mapOf("1:1" to EpisodeMeta("Magic Xylophone", null, null))))
        val reloaded = LibraryStore(file)
        assertEquals(store.roots, reloaded.roots)
        assertEquals(store.movies, reloaded.movies)
        assertEquals(store.shows, reloaded.shows)
        assertEquals(store.meta.mapValues { it.value.copy(fetchedAt = 0) }, reloaded.meta.mapValues { it.value.copy(fetchedAt = 0) })
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", reloaded.meta["arrival|2016"]!!.poster)
        assertEquals(video.uri, reloaded.find(video.uri).first?.file?.uri)
        file.delete()
    }
}
