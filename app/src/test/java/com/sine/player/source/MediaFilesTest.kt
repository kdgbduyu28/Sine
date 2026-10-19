package com.sine.player.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilesTest {

    @Test
    fun `finds sidecars with language and flags`() {
        val siblings = listOf(
            "Dune.Part.Two.2024.mkv",
            "Dune.Part.Two.2024.srt",
            "Dune.Part.Two.2024.en.forced.srt",
            "Dune.Part.Two.2024.fil.ass",
            "Dune.Part.Two.2024.eng.sdh.srt",
            "Other.Movie.en.srt",
            "Dune.Part.Two.2024 extras.srt",
        )
        val subs = MediaFiles.findSidecars("Dune.Part.Two.2024.mkv", siblings)
        assertEquals(4, subs.size)
        val byFile = subs.associateBy { it.fileName }
        assertEquals(null, byFile.getValue("Dune.Part.Two.2024.srt").language)
        assertEquals("en", byFile.getValue("Dune.Part.Two.2024.en.forced.srt").language)
        assertTrue(byFile.getValue("Dune.Part.Two.2024.en.forced.srt").forced)
        assertEquals("fil", byFile.getValue("Dune.Part.Two.2024.fil.ass").language)
        assertEquals("English · SDH · SRT", byFile.getValue("Dune.Part.Two.2024.eng.sdh.srt").label)
        // Forced subtitles sort last so a full track is the obvious first choice.
        assertTrue(subs.last().forced)
    }

    @Test
    fun `natural order puts episode 2 before episode 10`() {
        val names = listOf("Episode 10.mkv", "episode 2.mkv", "Episode 1.mkv", "Episode 02b.mkv")
        assertEquals(
            listOf("Episode 1.mkv", "episode 2.mkv", "Episode 02b.mkv", "Episode 10.mkv"),
            names.sortedWith(MediaFiles.naturalOrder),
        )
    }

    @Test
    fun `recognises video and subtitle extensions case-insensitively`() {
        assertTrue(MediaFiles.isVideo("MOVIE.M2TS"))
        assertTrue(MediaFiles.isSubtitle("a.ASS"))
        assertEquals(false, MediaFiles.isVideo("notes.txt"))
    }

    @Test
    fun `formats sizes`() {
        assertEquals("512 B", MediaFiles.formatSize(512))
        assertEquals("58.3 GB", MediaFiles.formatSize(62_600_000_000))
        assertEquals("", MediaFiles.formatSize(0))
    }
}
