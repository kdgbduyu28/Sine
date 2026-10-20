package com.sine.player.subtitles

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSubtitlesTest {

    @Test
    fun `hash matches the reference algorithm`() {
        // 128 KiB file of zeros: hash is just the size.
        val zeros = ByteArray(OpenSubtitles.HASH_CHUNK)
        assertEquals("0000000000020000", OpenSubtitles.hash(131072, zeros, zeros))
        val head = ByteArray(OpenSubtitles.HASH_CHUNK).also { it[0] = 1 }
        val tail = ByteArray(OpenSubtitles.HASH_CHUNK).also { it[8] = 2 }
        assertEquals("0000000000020003", OpenSubtitles.hash(131072, head, tail))
        // Sums wrap at 64 bits like the C reference.
        val ff = ByteArray(OpenSubtitles.HASH_CHUNK) { if (it < 8) 0xFF.toByte() else 0 }
        assertEquals("000000000001ffff", OpenSubtitles.hash(131072, ff, zeros))
    }

    @Test
    fun `guesses film queries from release names`() {
        assertEquals(SubtitleQuery("The Matrix", 1999), SubtitleQuery.from("The.Matrix.1999.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-GRP.mkv"))
        assertEquals(SubtitleQuery("Blade Runner 2049", 2017), SubtitleQuery.from("Blade Runner 2049 (2017) 1080p"))
        assertEquals(SubtitleQuery("1917"), SubtitleQuery.from("1917"))
    }

    @Test
    fun `guesses episode numbers`() {
        assertEquals(SubtitleQuery("Some Show", null, 1, 2), SubtitleQuery.from("Some.Show.S01E02.1080p.WEB-DL.mkv"))
        assertEquals(SubtitleQuery("Pilot", null, 1, 2), SubtitleQuery.from("S01E02 · Pilot"))
        assertEquals(SubtitleQuery("Show", null, 3, 10), SubtitleQuery.from("Show 3x10"))
    }

    @Test
    fun `parses search results, hash matches first`() {
        val json = JSONObject(
            """{"data":[
              {"id":"1","attributes":{"language":"en","download_count":900,"release":"Film.2019.WEB","files":[{"file_id":11,"file_name":"a.srt"}]}},
              {"id":"2","attributes":{"language":"en","download_count":10,"moviehash_match":true,"hearing_impaired":true,"release":"Film.2019.BluRay","files":[{"file_id":22,"file_name":"b.srt"}],"feature_details":{"title":"Film"}}},
              {"id":"3","attributes":{"language":"en","files":[]}}
            ]}""",
        )
        val results = OpenSubtitles.parseSearch(json)
        assertEquals(listOf(22L, 11L), results.map { it.fileId })
        assertEquals("Film", results[0].title)
        assertNull(results[1].title)
    }

    @Test
    fun `maps language codes`() {
        assertEquals("tl", OpenSubtitles.apiLanguage("fil"))
        assertEquals("zh-cn", OpenSubtitles.apiLanguage("zh"))
        assertEquals("en", OpenSubtitles.apiLanguage("EN"))
    }
}
