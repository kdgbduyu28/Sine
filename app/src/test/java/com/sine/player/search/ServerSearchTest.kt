package com.sine.player.search

import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSearchTest {
    private val jellyfin = RemoteServer(id = "jf", kind = ServerKind.JELLYFIN, name = "Home", address = "http://jf:8096", token = "t", userId = "u")
    private val plex = RemoteServer(id = "px", kind = ServerKind.PLEX, name = "Plex", address = "http://plex:32400", token = "t")

    @Test
    fun `reads Jellyfin search results`() {
        val json = JSONObject(
            """{"Items":[
                {"Id":"m1","Name":"Dune","Type":"Movie","MediaType":"Video","ProductionYear":2021,"ImageTags":{"Primary":"x"},"MediaSources":[{"Id":"m1","Container":"mkv"}]},
                {"Id":"s1","Name":"Dune: Prophecy","Type":"Series","IsFolder":true}]}""",
        )
        val (movie, show) = ServerSearch.parseJellyfin(json, jellyfin, "http://jf:8096")
        assertEquals(ResultKind.SERVER_VIDEO, movie.kind)
        assertEquals("Movie · Home", movie.subtitle)
        assertEquals(2021, movie.year)
        assertEquals("http://jf:8096/Items/m1/Images/Primary?maxHeight=300", movie.poster)
        assertEquals(ResultKind.SERVER_SHOW, show.kind)
        assertEquals("Show · Home", show.subtitle)
    }

    @Test
    fun `reads Plex hub search results`() {
        val json = JSONObject(
            """{"MediaContainer":{"Hub":[
                {"type":"movie","Metadata":[{"ratingKey":"7","type":"movie","title":"Dune","year":2021,"thumb":"/t/7",
                  "Media":[{"Part":[{"key":"/library/parts/7/file.mkv"}]}]}]},
                {"type":"actor","Metadata":[{"ratingKey":"9","type":"person","title":"Zendaya"}]},
                {"type":"show","Metadata":[{"ratingKey":"8","type":"show","title":"Dune: Prophecy","key":"/library/metadata/8/children"}]}]}}""",
        )
        val results = ServerSearch.parsePlex(json, plex, "http://plex:32400")
        assertEquals(listOf(ResultKind.SERVER_VIDEO, ResultKind.SERVER_SHOW), results.map { it.kind })
        assertEquals("http://plex:32400/t/7?X-Plex-Token=t", results[0].poster)
        assertEquals("Movie · Plex", results[0].subtitle)
    }
}
