package com.sine.player.livetv

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvParsersTest {
    private val playlist = """
        #EXTM3U url-tvg="http://example.com/guide.xml.gz"
        #EXTINF:-1 tvg-id="bbc1.uk" tvg-chno="101" tvg-logo="http://logo/bbc1.png" group-title="UK",BBC One HD
        http://example.com/live/bbc1.m3u8|User-Agent=Foo
        #EXTINF:-1 tvg-name="News, 24" group-title="News",News, 24
        http://example.com/live/news.ts
        #EXTINF:-1,Same Name
        http://a/1
        #EXTINF:-1,Same Name
        http://a/2
    """.trimIndent()

    @Test
    fun `parses channels, attributes and the guide URL`() {
        val parsed = M3u.parse(playlist, "src")
        assertEquals("http://example.com/guide.xml.gz", parsed.guideUrl)
        assertEquals(4, parsed.channels.size)
        val bbc = parsed.channels[0]
        assertEquals("BBC One HD", bbc.name)
        assertEquals("101", bbc.number)
        assertEquals("UK", bbc.group)
        assertEquals("bbc1.uk", bbc.guideId)
        assertEquals("http://example.com/live/bbc1.m3u8", bbc.url)
        assertEquals(mapOf("User-Agent" to "Foo"), bbc.headers)
        assertEquals("src:bbc1.uk", bbc.id)
        assertEquals("News, 24", parsed.channels[1].name)
    }

    @Test
    fun `duplicate names get distinct ids`() {
        val ids = M3u.parse(playlist, "src").channels.drop(2).map { it.id }
        assertEquals(listOf("src:Same Name", "src:Same Name#2"), ids)
    }

    @Test
    fun `reads EXTVLCOPT and Kodi headers`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Sports
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            #EXTVLCOPT:http-referrer=http://site.example/
            http://cdn.example/sports.m3u8|Origin=http%3A%2F%2Fsite.example
            #EXTINF:-1,Plain
            http://cdn.example/plain.ts
        """.trimIndent()
        val (sports, plain) = M3u.parse(text, "s").channels
        assertEquals(
            mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "http://site.example/", "Origin" to "http://site.example"),
            sports.headers,
        )
        assertEquals("http://cdn.example/sports.m3u8", sports.url)
        assertEquals(emptyMap<String, String>(), plain.headers)
    }

    @Test
    fun `parses XMLTV times with and without a zone`() {
        assertEquals(1_792_492_200_000L, Xmltv.parseTime("20261020183000 +0800"))
        assertEquals(1_792_521_000_000L, Xmltv.parseTime("20261020183000"))
        assertNull(Xmltv.parseTime("garbage"))
    }

    @Test
    fun `normalizes names for guide matching`() {
        assertEquals(Xmltv.normalize("BBC One"), Xmltv.normalize("BBC One HD"))
    }

    @Test
    fun `reads an HDHomeRun lineup and skips DRM channels`() {
        val lineup = JSONArray(
            """[{"GuideNumber":"2.1","GuideName":"KTVU","URL":"http://hd/auto/v2.1","HD":1},
                {"GuideNumber":"5.1","GuideName":"Paid","URL":"http://hd/auto/v5.1","DRM":1}]""",
        )
        val channels = HdHomeRun.parseLineup(lineup, "hd")
        assertEquals(1, channels.size)
        assertEquals("hd:2.1", channels[0].id)
        assertEquals("HD", channels[0].group)
    }
}
