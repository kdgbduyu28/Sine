package com.sine.player.mediaserver

import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeTest {
    @Test
    fun `recognises home network addresses`() {
        listOf("http://192.168.1.20:8096", "http://10.0.0.5", "https://172.20.1.1:443", "http://nas.local:32400", "http://nas:8096", "100.101.1.2", "http://localhost:8096")
            .forEach { assertTrue(it, Transcode.isHomeNetwork(it)) }
        listOf("https://jellyfin.example.com", "http://8.8.8.8:8096", "https://172.32.0.1", "https://abc.plex.direct:32400")
            .forEach { assertFalse(it, Transcode.isHomeNetwork(it)) }
    }

    @Test
    fun `applies only with a cap, to media servers, and away from home when asked`() {
        val home = RemoteServer(id = "a", kind = ServerKind.JELLYFIN, address = "http://192.168.1.20:8096")
        val away = RemoteServer(id = "b", kind = ServerKind.PLEX, address = "https://media.example.com")
        val webdav = RemoteServer(id = "c", kind = ServerKind.WEBDAV, address = "https://dav.example.com")
        assertFalse(Transcode.applies(away, 0, true))
        assertFalse(Transcode.applies(home, 8, true))
        assertTrue(Transcode.applies(home, 8, false))
        assertTrue(Transcode.applies(away, 8, true))
        assertFalse(Transcode.applies(webdav, 8, false))
    }

    @Test
    fun `builds HLS URLs for each server kind`() {
        val jf = RemoteServer(id = "a", kind = ServerKind.JELLYFIN, address = "https://jf.example.com/", token = "tok")
        val emby = RemoteServer(id = "e", kind = ServerKind.EMBY, address = "https://emby.example.com", token = "tok")
        val plex = RemoteServer(id = "p", kind = ServerKind.PLEX, address = "https://plex.example.com", token = "tok")
        val j = Transcode.url(jf, "abc", 8, "dev", "s1")
        assertTrue(j, j.startsWith("https://jf.example.com/Videos/abc/master.m3u8?MediaSourceId=abc&DeviceId=dev&PlaySessionId=s1"))
        assertTrue(j.contains("MaxStreamingBitrate=8000000") && j.endsWith("api_key=tok"))
        assertTrue(Transcode.url(emby, "abc", 8, "dev", "s1").startsWith("https://emby.example.com/emby/Videos/abc/master.m3u8?"))
        val p = Transcode.url(plex, "123", 4, "dev", "s1")
        assertTrue(p, p.startsWith("https://plex.example.com/video/:/transcode/universal/start.m3u8?path=%2Flibrary%2Fmetadata%2F123"))
        assertTrue(p.contains("maxVideoBitrate=4000") && p.contains("videoResolution=1280x720") && p.endsWith("X-Plex-Token=tok"))
    }
}
