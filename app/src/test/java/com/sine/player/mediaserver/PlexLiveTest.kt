package com.sine.player.mediaserver

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexLiveTest {
    private val base = "http://plex.local:32400"

    @Test
    fun `reads DVRs`() {
        val json = JSONObject("""{"MediaContainer":{"Dvr":[{"key":"12","lineup":"lineup://tv.plex.providers.epg.cloud/abc","epgIdentifier":"tv.plex.providers.epg.cloud:12"}]}}""")
        val dvr = PlexLive.parseDvrs(json).single()
        assertEquals("12", dvr.key)
        assertEquals("tv.plex.providers.epg.cloud:12", dvr.epgIdentifier)
    }

    @Test
    fun `reads lineup channels from either shape`() {
        val nested = JSONObject("""{"MediaContainer":{"Lineup":[{"Channel":[{"title":"KQED","channelVcn":"9.1","identifier":"5cc8","thumb":"/photo/kqed.png"}]}]}}""")
        val flat = JSONObject("""{"MediaContainer":{"Channel":[{"callSign":"KTVU","channelVcn":"2.1"}]}}""")
        val a = PlexLive.parseChannels(nested, "srv", "12", "src", base, "tok").single()
        assertEquals("src:12:9.1", a.id)
        assertEquals("plexlive://srv/12/9.1/index.m3u8", a.url)
        assertEquals("5cc8", a.guideId)
        assertEquals("http://plex.local:32400/photo/kqed.png?X-Plex-Token=tok", a.logo)
        val b = PlexLive.parseChannels(flat, "srv", "12", "src", base, "tok").single()
        assertEquals("KTVU", b.name)
        assertEquals("2.1", b.guideId)
    }

    @Test
    fun `reads the guide grid`() {
        val json = JSONObject(
            """{"MediaContainer":{"Metadata":[{"grandparentTitle":"News at 6","title":"Monday","summary":"Local news",
               "Media":[{"channelIdentifier":"5cc8","beginsAt":1792492200,"endsAt":1792494000}]}]}}""",
        )
        val p = PlexLive.parseGrid(json).single()
        assertEquals("5cc8", p.channel)
        assertEquals("News at 6", p.title)
        assertEquals("Monday", p.subtitle)
        assertEquals(1_792_492_200_000L, p.startMs)
    }

    @Test
    fun `finds the session path anywhere in the tune response`() {
        val json = JSONObject(
            """{"MediaContainer":{"MediaSubscription":[{"MediaGrabOperation":[{"Metadata":{"Media":[{"Part":[{"key":"/livetv/sessions/ab12/c3/index.m3u8"}]}]}}]}]}}""",
        )
        assertEquals("/livetv/sessions/ab12/c3/index.m3u8", PlexLive.findSessionPath(json))
        assertNull(PlexLive.findSessionPath(JSONObject("""{"MediaContainer":{}}""")))
    }

    @Test
    fun `rewrites playlist URIs to absolute ones with the token`() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:2.0,
            00001.ts
            /livetv/sessions/ab12/c3/00002.ts
            https://cdn.example/x.ts?a=1
        """.trimIndent()
        val out = PlexLive.rewritePlaylist(playlist, "http://plex.local:32400/livetv/sessions/ab12/c3/index.m3u8?X-Plex-Token=tok", "tok").lines()
        assertEquals("#EXT-X-KEY:METHOD=AES-128,URI=\"http://plex.local:32400/livetv/sessions/ab12/c3/key.bin?X-Plex-Token=tok\"", out[1])
        assertEquals("http://plex.local:32400/livetv/sessions/ab12/c3/00001.ts?X-Plex-Token=tok", out[3])
        assertEquals("http://plex.local:32400/livetv/sessions/ab12/c3/00002.ts?X-Plex-Token=tok", out[4])
        assertEquals("https://cdn.example/x.ts?a=1&X-Plex-Token=tok", out[5])
    }
}
