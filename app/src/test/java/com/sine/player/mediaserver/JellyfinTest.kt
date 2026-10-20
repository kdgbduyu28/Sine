package com.sine.player.mediaserver

import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinTest {
    private val server = RemoteServer(id = "srv1", kind = ServerKind.JELLYFIN, address = "http://jf:8096", token = "tok", userId = "u1")

    @Test
    fun `parses folders, episodes and stream URLs`() {
        val json = JSONObject(
            """{"Items":[
              {"Id":"f1","Name":"Season 1","Type":"Season","IsFolder":true},
              {"Id":"e1","Name":"Pilot","Type":"Episode","MediaType":"Video","IsFolder":false,"ParentIndexNumber":1,"IndexNumber":2,
               "RunTimeTicks":36000000000,
               "UserData":{"PlaybackPositionTicks":6000000000,"Played":false,"LastPlayedDate":"2026-10-01T12:00:00.1234567Z"},
               "MediaSources":[{"Id":"ms1","Container":"mkv","Size":999,
                 "MediaStreams":[{"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"eng","DisplayTitle":"English (SRT)","IsExternal":true},
                                 {"Type":"Subtitle","Index":4,"Codec":"PGSSUB","IsExternal":false}]}]},
              {"Id":"a1","Name":"Song","Type":"Audio","MediaType":"Audio","IsFolder":false}
            ]}""",
        )
        val entries = JellyfinClient.parseItems(json, server, "http://jf:8096")
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("jellyfin://srv1/f/f1?title=Season%201", entries[0].uri)

        val episode = entries[1]
        assertEquals("S01E02 · Pilot", episode.title)
        assertEquals("jellyfin://srv1/i/e1", episode.uri)
        assertEquals("http://jf:8096/Videos/e1/stream.mkv?static=true&MediaSourceId=ms1&api_key=tok", episode.playUri)
        assertEquals(1, episode.subtitles.size)
        assertEquals("http://jf:8096/Videos/e1/ms1/Subtitles/3/Stream.srt?api_key=tok", episode.subtitles[0].url)
        assertEquals("eng", episode.subtitles[0].language)
        val remote = episode.remote!!
        assertEquals(600_000L, remote.positionMs)
        assertEquals(3_600_000L, remote.durationMs)
        assertTrue(remote.lastPlayedAt > 0)
    }

    @Test
    fun `skips music and photo libraries`() {
        val json = JSONObject("""{"Items":[{"Id":"1","Name":"Movies","CollectionType":"movies"},{"Id":"2","Name":"Music","CollectionType":"music"}]}""")
        assertEquals(listOf("Movies"), JellyfinClient.parseLibraries(json, server).map { it.name })
    }

    @Test
    fun `Emby lives under emby`() {
        assertEquals("http://e:8096/emby", JellyfinClient.apiBase("http://e:8096/", emby = true))
        assertEquals("http://e:8096/emby", JellyfinClient.apiBase("http://e:8096/emby", emby = true))
        assertEquals("http://j:8096", JellyfinClient.apiBase("http://j:8096", emby = false))
    }

    @Test
    fun `parses ISO dates to the second`() {
        assertEquals(1_759_320_000_000L, JellyfinClient.parseDate("2025-10-01T12:00:00.1234567Z"))
        assertEquals(0L, JellyfinClient.parseDate(""))
    }

    @Test
    fun `media server URIs round-trip`() {
        assertEquals(Triple("srv1", "f", "a b/c"), MediaServerUris.parse("plex://srv1/f/a%20b%2Fc?title=X"))
        assertEquals(Triple("srv1", "i", "e1"), MediaServerUris.parse("jellyfin://srv1/i/e1"))
        assertEquals(Triple("srv1", null, null), MediaServerUris.parse("emby://srv1/"))
        assertNull(MediaServerUris.parse("smb://nas/x"))
    }
}
