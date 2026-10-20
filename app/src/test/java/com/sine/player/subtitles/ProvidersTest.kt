package com.sine.player.subtitles

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProvidersTest {
    @Test
    fun `parses Podnapisi search JSON`() {
        val json = JSONObject(
            """{"data":[{"id":"Ab1","language":"en","releases":["The.Bear.S03E04.1080p.WEB-DL-NTb"],"custom_releases":[],
            "flags":["hearing_impaired"],"stats":{"downloads":321},
            "movie":{"title":"The Bear","year":2022,"type":"tv-series","episode_info":{"season":3,"episode":4}}}],"page":1,"all_pages":1}""",
        )
        val c = PodnapisiProvider.parse(json).single()
        assertEquals("Ab1", c.id)
        assertEquals("The.Bear.S03E04.1080p.WEB-DL-NTb", c.release)
        assertTrue(c.hearingImpaired)
        assertEquals(321, c.downloads)
        assertEquals(3, c.season)
        assertEquals(4, c.episode)
    }

    @Test
    fun `parses SubDL search JSON`() {
        val json = JSONObject(
            """{"status":true,"subtitles":[{"release_name":"Dune.2021.1080p.BluRay-SPARKS","name":"SUBDL::Dune","lang":"EN",
            "url":"/subtitle/3197651-3213944.zip","hi":false,"season":0,"episode":0}]}""",
        )
        val c = SubDlProvider.parse(json).single()
        assertEquals("3197651-3213944", c.id)
        assertEquals("/subtitle/3197651-3213944.zip", c.handle)
        assertEquals("EN", c.language)
        assertEquals(null, c.season)
    }

    @Test
    fun `picks the right episode out of a season zip`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                listOf("Show.S01E01.srt" to "one", "Show.S01E02.srt" to "two", "readme.txt" to "x").forEach { (name, body) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(body.toByteArray())
                    z.closeEntry()
                }
            }
        }.toByteArray()
        val (name, bytes) = SubtitleArchives.pick(zip, SubtitleQuery("Show", season = 1, episode = 2))!!
        assertEquals("Show.S01E02.srt", name)
        assertEquals("two", String(bytes))
        assertEquals("subtitle.srt", SubtitleArchives.pick("1\n".toByteArray(), SubtitleQuery("x"))!!.first)
    }
}
