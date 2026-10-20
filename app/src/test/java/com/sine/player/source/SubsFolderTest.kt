package com.sine.player.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsFolderTest {
    @Test
    fun `finds the subtitle folder`() {
        assertEquals("Subs", MediaFiles.subsFolder(listOf("Featurettes", "Subs")))
        assertEquals("subtitles", MediaFiles.subsFolder(listOf("subtitles")))
        assertNull(MediaFiles.subsFolder(listOf("Extras")))
    }

    @Test
    fun `labels language-only subtitle names`() {
        val subs = MediaFiles.folderSidecars(listOf("2_English.srt", "3_eng.forced.srt", "French (SDH).ass", "notes.txt"))
        assertEquals(3, subs.size)
        val english = subs.first { it.fileName == "2_English.srt" }
        assertEquals("en", english.language)
        val forced = subs.first { it.fileName == "3_eng.forced.srt" }
        assertEquals(true, forced.forced)
        val french = subs.first { it.fileName == "French (SDH).ass" }
        assertEquals(true, french.label.contains("SDH"))
        // Forced ones sort last.
        assertEquals("3_eng.forced.srt", subs.last().fileName)
    }
}
