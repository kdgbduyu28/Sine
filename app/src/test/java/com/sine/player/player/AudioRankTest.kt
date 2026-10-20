package com.sine.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRankTest {
    private fun a(lang: String?, mime: String, channels: Int, label: String? = null, commentary: Boolean = false, supported: Boolean = true) =
        AudioCandidate(lang, label, mime, channels, commentary, false, supported)

    @Test
    fun `prefers lossless Atmos in the wanted language`() {
        val tracks = listOf(
            a("eng", "audio/ac3", 6),
            a("eng", "audio/true-hd", 8, "TrueHD Atmos 7.1"),
            a("jpn", "audio/true-hd", 8, "Atmos"),
            a("eng", "audio/vnd.dts.hd", 8, "DTS-HD MA 7.1"),
        )
        assertEquals(1, AudioRank.best(tracks, "en", 0))
        assertEquals(2, AudioRank.best(tracks, "ja", 0))
    }

    @Test
    fun `follows the default track's language without a preference`() {
        val tracks = listOf(a("jpn", "audio/flac", 2), a("eng", "audio/true-hd", 8), a("jpn", "audio/ac3", 6))
        // AC3 5.1 (50) beats FLAC stereo (45) within Japanese.
        assertEquals(2, AudioRank.best(tracks, null, 0))
    }

    @Test
    fun `never starts on commentary or unsupported tracks when there is a choice`() {
        val tracks = listOf(
            a("eng", "audio/true-hd", 8, "Director's Commentary"),
            a("eng", "audio/true-hd", 8, "Commentary", commentary = true),
            a("eng", "audio/vnd.dts.hd", 8, supported = false),
            a("eng", "audio/eac3", 6),
        )
        assertEquals(3, AudioRank.best(tracks, "en", 0))
        assertTrue(AudioRank.isExtra(tracks[0]))
    }

    @Test
    fun `falls back to any language when none match`() {
        val tracks = listOf(a("fre", "audio/ac3", 6), a("ger", "audio/eac3-joc", 6))
        assertEquals(1, AudioRank.best(tracks, "es", 0))
        assertEquals("en", AudioRank.norm("eng"))
        assertEquals("en", AudioRank.norm("en-US"))
    }
}
