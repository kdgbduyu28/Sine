package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitlePickTest {
    private val tracks = listOf(
        SubtitleCandidate("eng", forced = true, hearingImpaired = false),
        SubtitleCandidate("eng", forced = false, hearingImpaired = true),
        SubtitleCandidate("eng", forced = false, hearingImpaired = false),
        SubtitleCandidate("spa", forced = false, hearingImpaired = false),
    )

    private fun pick(mode: SubtitleMode, audio: String?, list: List<SubtitleCandidate> = tracks, avoidSdh: Boolean = true) =
        SubtitlePick.decide(mode, list, audio, "en", avoidSdh)

    @Test
    fun `foreign mode shows full subtitles for other languages and forced ones otherwise`() {
        assertEquals(2, pick(SubtitleMode.FOREIGN, "jpn"))
        assertEquals(0, pick(SubtitleMode.FOREIGN, "eng"))
        assertNull(pick(SubtitleMode.FOREIGN, "en", tracks.drop(1)))
        assertEquals(2, pick(SubtitleMode.FOREIGN, null))
    }

    @Test
    fun `other modes`() {
        assertEquals(2, pick(SubtitleMode.ALWAYS, "eng"))
        assertEquals(0, pick(SubtitleMode.FORCED, "jpn"))
        assertNull(pick(SubtitleMode.OFF, "jpn"))
        assertNull(pick(SubtitleMode.FORCED, "jpn", tracks.drop(1)))
    }

    @Test
    fun `SDH only when it's all there is`() {
        val sdhOnly = listOf(SubtitleCandidate("en", false, true), SubtitleCandidate("fr", false, false))
        assertEquals(0, pick(SubtitleMode.ALWAYS, "fr", sdhOnly))
        assertNull(pick(SubtitleMode.ALWAYS, "fr", listOf(SubtitleCandidate("en", false, false, supported = false))))
    }
}
