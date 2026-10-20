package com.sine.player.mediaserver

import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import com.sine.player.source.MediaEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerHomeTest {
    private val jellyfin = RemoteServer(id = "srv1", kind = ServerKind.JELLYFIN, address = "http://jf:8096", token = "tok", userId = "u1")
    private val plex = RemoteServer(id = "p1", kind = ServerKind.PLEX, address = "http://plex:32400", token = "t")

    @Test
    fun `reads Jellyfin resume items with season playlist and progress`() {
        val json = JSONObject(
            """{"Items":[{"Id":"e1","Name":"Pilot","Type":"Episode","MediaType":"Video","SeriesName":"The Show",
                "ParentIndexNumber":1,"IndexNumber":1,"SeasonId":"s1","SeasonName":"Season 1","RunTimeTicks":36000000000,
                "DateCreated":"2026-10-18T08:00:00.0000000Z","UserData":{"PlaybackPositionTicks":9000000000,"LastPlayedDate":"2026-10-19T10:00:00.0000000Z"},
                "ImageTags":{"Primary":"x"},"MediaSources":[{"Id":"e1","Container":"mkv"}]}]}""",
        )
        val item = ServerHome.parseJellyfin(json, jellyfin, "http://jf:8096").single()
        assertEquals("The Show", item.title)
        assertEquals("S01E01 · Pilot", item.subtitle)
        assertEquals(0.25f, item.progress, 0.001f)
        assertEquals("http://jf:8096/Items/e1/Images/Primary?maxHeight=270", item.image)
        assertEquals("jellyfin://srv1/f/s1?title=Season%201", item.folder)
        assertEquals("jellyfin://srv1/i/e1", item.entry.uri)
        assertEquals(1_792_310_400_000L, item.addedAt)
    }

    @Test
    fun `reads Plex on deck`() {
        val json = JSONObject(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"10","type":"episode","title":"Pilot","grandparentTitle":"The Show",
                "parentIndex":1,"index":2,"parentRatingKey":"9","parentTitle":"Season 1","thumb":"/library/metadata/10/thumb/1",
                "duration":3600000,"viewOffset":0,"addedAt":1792310400,"Media":[{"Part":[{"key":"/library/parts/5/file.mkv","size":1}]}]}]}}""",
        )
        val item = ServerHome.parsePlex(json, plex, "http://plex:32400").single()
        assertEquals("The Show", item.title)
        assertEquals("S01E02 · Pilot", item.subtitle)
        assertEquals(0f, item.progress)
        assertEquals(1_792_310_400_000L, item.addedAt)
        assertEquals("plex://p1/f/%2Flibrary%2Fmetadata%2F9%2Fchildren?title=Season%201", item.folder)
        assertTrue(item.image!!.contains("/photo/:/transcode?") && item.image!!.contains("url=%2Flibrary%2Fmetadata%2F10%2Fthumb%2F1"))
    }

    @Test
    fun `started items come first, most recent first`() {
        fun item(uri: String, progress: Float, at: Long) =
            ServerHomeItem(MediaEntry(uri, uri, false), uri, null, null, null, progress, at, "s")
        val ordered = ServerHome.order(listOf(item("next", 0f, 0), item("old", 0.5f, 1), item("new", 0.2f, 5), item("new", 0.2f, 5)))
        assertEquals(listOf("new", "old", "next"), ordered.map { it.entry.uri })
    }
}
