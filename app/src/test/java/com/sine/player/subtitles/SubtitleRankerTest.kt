package com.sine.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRankerTest {
    @Test
    fun `parses release names`() {
        val info = ReleaseInfo.parse("The.Bear.S03E04.1080p.DSNP.WEB-DL.DDP5.1.H.264-NTb.mkv")
        assertEquals("ntb", info.group)
        assertEquals("web", info.source)
        assertEquals("1080p", info.resolution)
        assertEquals("disney", info.service)
        assertEquals(3, info.season)
        assertEquals(4, info.episode)
        assertEquals("remux", ReleaseInfo.parse("Dune.2021.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1-FGT").source)
    }

    @Test
    fun `ranks hash match, then same release, then title only`() {
        val file = "The.Bear.S03E04.1080p.DSNP.WEB-DL.DDP5.1.H.264-NTb.mkv"
        val ranked = SubtitleRanker.rank(
            listOf(
                SubtitleCandidate("A", "1", "The.Bear.S03E04.720p.HDTV.x264-OTHER", "en", downloads = 50_000),
                SubtitleCandidate("A", "2", "The.Bear.S03E04.1080p.DSNP.WEB-DL.DDP5.1.H.264-NTb", "en", downloads = 10),
                SubtitleCandidate("B", "3", "whatever", "en", hashMatch = true),
                SubtitleCandidate("B", "4", "The.Bear.S03E05.1080p.DSNP.WEB-DL-NTb", "en"),
            ),
            file,
            listOf("en"),
            avoidHearingImpaired = true,
        )
        assertEquals(listOf("3", "2", "1"), ranked.map { it.candidate.id })
        assertEquals("Exact file match", ranked[0].match)
        assertEquals("Same release (ntb)", ranked[1].match)
        assertTrue(SubtitleRanker.confident(ranked[1]))
        assertFalse(SubtitleRanker.confident(ranked[2]))
    }

    @Test
    fun `prefers the first language`() {
        val ranked = SubtitleRanker.rank(
            listOf(SubtitleCandidate("A", "en", "Movie.2020.1080p.BluRay-X", "en"), SubtitleCandidate("A", "tl", "Movie.2020.1080p.BluRay-X", "tl")),
            "Movie.2020.1080p.BluRay-X.mkv",
            listOf("fil", "en"),
            true,
        )
        assertEquals("tl", ranked.first().candidate.id)
    }

    @Test
    fun `compares language codes across standards`() {
        assertTrue(Languages.same("en", "eng"))
        assertTrue(Languages.same("fil", "tl"))
        assertTrue(Languages.same("zh-CN", "chi"))
        assertFalse(Languages.same("en", "es"))
        assertFalse(Languages.same(null, "en"))
    }
}
