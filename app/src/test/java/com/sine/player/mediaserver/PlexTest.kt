package com.sine.player.mediaserver

import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexTest {
    private val server = RemoteServer(id = "p1", kind = ServerKind.PLEX, address = "http://plex:32400", token = "t")

    @Test
    fun `parses sections, shows and movies`() {
        val sections = JSONObject("""{"MediaContainer":{"Directory":[{"key":"1","title":"Films","type":"movie"},{"key":"2","title":"Music","type":"artist"}]}}""")
        val libs = PlexClient.parseSections(sections, server)
        assertEquals(1, libs.size)
        assertEquals("plex://p1/f/%2Flibrary%2Fsections%2F1%2Fall?title=Films", libs[0].uri)

        val items = JSONObject(
            """{"MediaContainer":{"Metadata":[
              {"ratingKey":"10","key":"/library/metadata/10/children","type":"show","title":"A Show"},
              {"ratingKey":"20","key":"/library/metadata/20","type":"movie","title":"Film","year":2019,"duration":7200000,"viewOffset":60000,"lastViewedAt":1700000000,
               "Media":[{"Part":[{"id":5,"key":"/library/parts/5/123/file.mkv","size":42,
                 "Stream":[{"streamType":3,"key":"/library/streams/77","codec":"srt","languageCode":"eng","displayTitle":"English (SRT External)"},
                           {"streamType":3,"codec":"pgs","languageCode":"eng"}]}]}]}
            ]}}""",
        )
        val entries = PlexClient.parseMetadata(items, server, "http://plex:32400")
        assertTrue(entries[0].isDirectory)
        assertEquals("plex://p1/f/%2Flibrary%2Fmetadata%2F10%2Fchildren?title=A%20Show", entries[0].uri)
        val film = entries[1]
        assertEquals("Film (2019)", film.title)
        assertEquals("plex://p1/i/20", film.uri)
        assertEquals("http://plex:32400/library/parts/5/123/file.mkv?X-Plex-Token=t", film.playUri)
        assertEquals(listOf("http://plex:32400/library/streams/77?X-Plex-Token=t"), film.subtitles.map { it.url })
        assertEquals(60_000L, film.remote!!.positionMs)
        assertEquals(1_700_000_000_000L, film.remote!!.lastPlayedAt)
    }

    @Test
    fun `prefers the LAN address of a server resource`() {
        val array = JSONArray(
            """[{"name":"Phone","provides":"player","connections":[]},
               {"name":"NAS","provides":"server","accessToken":"srvtok","connections":[
                 {"uri":"https://1-2-3-4.abc.plex.direct:32400","local":false,"address":"1.2.3.4","port":32400},
                 {"uri":"https://192-168-1-9.abc.plex.direct:32400","local":true,"address":"192.168.1.9","port":32400}]}]""",
        )
        val servers = PlexClient.parseResources(array, "acct")
        assertEquals(listOf(PlexResource("NAS", "http://192.168.1.9:32400", "srvtok")), servers)
    }
}
