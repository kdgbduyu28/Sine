package com.sine.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderMemoryTest {
    private val japanese = TrackChoice("ja", "Japanese 5.1", "audio/eac3", 1)
    private val english = TrackChoice("en", "Full", "application/x-subrip", 2)
    private val signs = TrackChoice("en", "Signs", "text/x-ssa", 3)

    @Test
    fun `folder fills in what the file lacks`() {
        val folder = FileChoices(audio = japanese, subtitle = english)
        val file = FileChoices(subtitleDelayMs = 250)
        assertEquals(FileChoices(audio = japanese, subtitle = english, subtitleDelayMs = 250), FileMemory.merge(file, folder))
        assertEquals(FileChoices(audio = japanese, subtitle = english), FileMemory.merge(null, folder))
    }

    @Test
    fun `the file's own choices win, including subtitles off`() {
        val folder = FileChoices(audio = japanese, subtitle = english)
        assertEquals(signs, FileMemory.merge(FileChoices(subtitle = signs), folder)!!.subtitle)
        val off = FileMemory.merge(FileChoices(subtitlesOff = true), folder)!!
        assertNull(off.subtitle)
        assertEquals(true, off.subtitlesOff)
        assertEquals(japanese, off.audio)
    }

    @Test
    fun `no folder means the file as is`() {
        assertNull(FileMemory.merge(null, null))
        val file = FileChoices(audio = japanese)
        assertEquals(file, FileMemory.merge(file, null))
    }

    @Test
    fun `only track choices are kept for a folder`() {
        val kept = FileMemory.tracksOnly(FileChoices(audio = japanese, subtitleDelayMs = 500, speed = 1.5f, engine = "MPV", aspect = AspectMode.entries.first()))
        assertEquals(FileChoices(audio = japanese), kept)
    }
}
