package com.sine.player.net

import com.sine.player.data.RemoteServer
import com.sine.player.data.RemoteUris
import com.sine.player.data.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavTest {

    private val multistatus = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>/dav/Movies/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
  <d:response><d:href>/dav/Movies/Some%20Film%20(2020)/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Mon, 12 Jan 2026 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
  <D:response xmlns:D="DAV:"><D:href>http://nas:8080/dav/Movies/a%20b.mkv</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>123456</D:getcontentlength></D:prop></D:propstat></D:response>
</d:multistatus>"""

    @Test
    fun `parses folders and files whatever the namespace prefix`() {
        val entries = WebDav.parse(multistatus)
        assertEquals(3, entries.size)
        assertEquals("Some Film (2020)", entries[1].name)
        assertTrue(entries[1].isDirectory)
        assertTrue(entries[1].modified > 0)
        assertEquals("a b.mkv", entries[2].name)
        assertFalse(entries[2].isDirectory)
        assertEquals(123456L, entries[2].size)
    }

    @Test
    fun `href paths stay encoded`() {
        assertEquals("/dav/Movies/a%20b.mkv", WebDav.rawPath("http://nas:8080/dav/Movies/a%20b.mkv"))
        assertEquals("/dav/x%20y/", WebDav.rawPath("/dav/x y/"))
    }

    @Test
    fun `maps between webdav and http URIs`() {
        assertEquals("webdavs://nas:5006/video/", RemoteUris.webDavUri("https://nas:5006/video/"))
        assertEquals("webdav://nas/dav/", RemoteUris.webDavUri("http://nas/dav"))
        assertEquals("https://nas:5006/video/a.mkv", RemoteUris.webDavToHttp("webdavs://nas:5006/video/a.mkv"))
    }

    @Test
    fun `credentials come from the longest matching server`() {
        val root = RemoteServer(id = "1", kind = ServerKind.WEBDAV, address = "https://nas:5006", username = "a")
        val video = RemoteServer(id = "2", kind = ServerKind.WEBDAV, address = "https://nas:5006/video", username = "b")
        assertEquals("2", WebDav.serverFor(listOf(root, video), "webdavs://nas:5006/video/x.mkv")?.id)
        assertEquals("1", WebDav.serverFor(listOf(root, video), "webdavs://nas:5006/music/x.mp3")?.id)
        assertNull(WebDav.serverFor(listOf(root), "webdav://other/x.mkv"))
    }

    @Test
    fun `normalises typed server addresses`() {
        assertEquals("http://192.168.1.5:8096", RemoteUris.normaliseBaseUrl("192.168.1.5", 8096))
        assertEquals("http://nas:9000/jf", RemoteUris.normaliseBaseUrl("http://nas:9000/jf/", 8096))
        assertEquals("https://jf.example.com", RemoteUris.normaliseBaseUrl("https://jf.example.com", 8096))
        assertEquals("http://nas.local", RemoteUris.normaliseBaseUrl("nas.local", 0))
    }
}
