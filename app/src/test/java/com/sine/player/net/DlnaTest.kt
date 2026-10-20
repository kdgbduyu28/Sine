package com.sine.player.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTest {

    @Test
    fun `reads the location header from an SSDP reply`() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.2:8200/rootDesc.xml\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
        assertEquals("http://192.168.1.2:8200/rootDesc.xml", Dlna.ssdpHeader(reply, "location"))
    }

    @Test
    fun `finds the ContentDirectory control URL`() {
        val xml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
  <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
  <friendlyName>NAS: minidlna</friendlyName><UDN>uuid:4d696e69-444c-164e-9d41-001132abcdef</UDN>
  <serviceList>
    <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ctl/ConnectionMgr</controlURL></service>
    <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/ctl/ContentDir</controlURL></service>
  </serviceList>
</device></root>"""
        val server = Dlna.parseDescription(xml, "http://192.168.1.2:8200/rootDesc.xml")!!
        assertEquals("4d696e69-444c-164e-9d41-001132abcdef", server.udn)
        assertEquals("NAS: minidlna", server.name)
        assertEquals("http://192.168.1.2:8200/ctl/ContentDir", server.controlUrl)
    }

    @Test
    fun `parses an escaped DIDL-Lite browse result`() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/">
<container id="64$1" parentID="64" childCount="3"><dc:title>Movies</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>
<item id="64$1$0" parentID="64$1"><dc:title>Some Film</dc:title><upnp:class>object.item.videoItem</upnp:class>
<res protocolInfo="http-get:*:video/x-matroska:*" size="1234" duration="1:42:03.500">http://192.168.1.2:8200/MediaItems/22.mkv</res>
<res protocolInfo="http-get:*:text/srt:*">http://192.168.1.2:8200/Captions/22.srt</res></item>
<item id="64$9"><dc:title>Song</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:audio/mpeg:*">http://x/1.mp3</res></item>
</DIDL-Lite>"""
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>$escaped</Result><NumberReturned>3</NumberReturned><TotalMatches>3</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"""
        val (objects, total) = Dlna.parseBrowseResponse(soap)
        assertEquals(3, total)
        assertEquals(2, objects.size)
        assertTrue(objects[0].isContainer)
        val film = objects[1]
        assertFalse(film.isContainer)
        assertEquals("http://192.168.1.2:8200/MediaItems/22.mkv", film.url)
        assertEquals(1234L, film.size)
        assertEquals(6_123_500L, film.durationMs)
        assertEquals(listOf("http://192.168.1.2:8200/Captions/22.srt"), film.subtitles.map { it.url })

        val entries = Dlna.toEntries("abc", objects)
        assertTrue(entries[0].isDirectory)
        assertEquals("dlna://servers/abc/c/64%241?title=Movies", entries[0].uri)
        assertTrue(entries[1].isVideo)
        assertEquals("Some Film", entries[1].title)
        assertEquals("dlna://servers/abc/i/64%241%240", entries[1].uri)
    }

    @Test
    fun `parses DIDL durations`() {
        assertEquals(3_723_000L, Dlna.parseDuration("1:02:03"))
        assertEquals(0L, Dlna.parseDuration(""))
        assertEquals(0L, Dlna.parseDuration("garbage"))
    }
}
