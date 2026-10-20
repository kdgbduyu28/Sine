package com.sine.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfoTest {
    @Test
    fun `reads a Kodi movie nfo`() {
        val nfo = Nfo.parse(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
            <movie>
                <title>Arrival</title>
                <ratings>
                    <rating name="imdb" max="10"><value>7.9</value><votes>700000</votes></rating>
                    <rating name="themoviedb" max="10" default="true"><value>7.6</value></rating>
                </ratings>
                <plot>Linguist Louise Banks leads an elite team &amp; more.</plot>
                <mpaa>Rated PG-13</mpaa>
                <uniqueid type="imdb">tt2543164</uniqueid>
                <uniqueid type="tmdb" default="true">329865</uniqueid>
                <genre>Drama</genre>
                <genre>Science Fiction / Mystery</genre>
                <premiered>2016-11-10</premiered>
            </movie>""",
        )!!
        assertEquals("Arrival", nfo.title)
        assertEquals(2016, nfo.year)
        assertEquals(7.6, nfo.rating!!, 1e-9)
        assertEquals("Linguist Louise Banks leads an elite team & more.", nfo.plot)
        assertEquals("PG-13", nfo.certification)
        assertEquals(329865, nfo.tmdbId)
        assertEquals(listOf("Drama", "Science Fiction", "Mystery"), nfo.genres)
    }

    @Test
    fun `reads older and link-only files`() {
        val old = Nfo.parse("<tvshow><title>The Bear</title><year>2022</year><rating>8.6</rating><mpaa>US:TV-MA / GB:15</mpaa><tmdbid>136315</tmdbid></tvshow>")!!
        assertEquals(8.6, old.rating!!, 1e-9)
        assertEquals("TV-MA", old.certification)
        assertEquals(136315, old.tmdbId)
        assertEquals(329865, Nfo.parse("https://www.themoviedb.org/movie/329865-arrival")!!.tmdbId)
        assertNull(Nfo.parse("https://www.imdb.com/title/tt2543164/"))
    }

    @Test
    fun `nfo fields win over TMDB, gaps are kept`() {
        val tmdb = Meta(1, "Arrival (2016)", "TMDB plot", "http://img/poster.jpg", "http://img/back.jpg", 7.0, listOf("Drama"), 2016)
        val nfo = NfoInfo("Arrival", null, "My plot", null, emptyList(), "PG-13", null)
        val merged = Nfo.merge(tmdb, nfo, "smb://nas/Arrival/fanart.jpg")
        assertEquals("Arrival", merged.title)
        assertEquals("My plot", merged.overview)
        assertEquals(7.0, merged.rating!!, 1e-9)
        assertEquals(listOf("Drama"), merged.genres)
        assertEquals("PG-13", merged.certification)
        assertEquals("http://img/poster.jpg", merged.poster)
        assertEquals("smb://nas/Arrival/fanart.jpg", merged.backdrop)
    }

    @Test
    fun `nfo alone stays due for a TMDB lookup`() {
        val merged = Nfo.merge(null, NfoInfo("Home Video", 2020, null, null, emptyList(), null, null), null)
        assertEquals("Home Video", merged.title)
        assertEquals(0L, merged.fetchedAt)
    }

    @Test
    fun `knows Kodi file names`() {
        assertEquals(listOf("Arrival (2016).nfo", "movie.nfo"), Nfo.movieNfoNames("Arrival (2016).mkv"))
        assertEquals(listOf("Arrival (2016)-fanart", "fanart", "backdrop", "background"), Nfo.fanartNames("Arrival (2016).mkv"))
    }
}
