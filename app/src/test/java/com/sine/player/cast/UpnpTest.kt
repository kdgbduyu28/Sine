package com.sine.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpTest {
    @Test
    fun `parses a SetAVTransportURI call with escaped metadata`() {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID><CurrentURI>http://192.168.1.5:8200/MediaItems/22.mkv?a=1&amp;b=2</CurrentURI>
            <CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;Big Buck Bunny&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
            </u:SetAVTransportURI></s:Body></s:Envelope>"""
        val call = Upnp.parse("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", body)!!
        assertEquals("SetAVTransportURI", call.action)
        assertEquals("http://192.168.1.5:8200/MediaItems/22.mkv?a=1&b=2", call.args["CurrentURI"])
        assertEquals("Big Buck Bunny", Upnp.didlTitle(call.args["CurrentURIMetaData"]))
    }

    @Test
    fun `rejects a missing action`() {
        assertNull(Upnp.parse(null, ""))
        assertNull(Upnp.parse("\"urn:x\"", ""))
    }

    @Test
    fun `converts times both ways`() {
        assertEquals(3_723_500L, Upnp.parseTime("01:02:03.500"))
        assertEquals(3_723_000L, Upnp.parseTime("1:02:03"))
        assertNull(Upnp.parseTime("123"))
        assertEquals("1:02:03", Upnp.formatTime(3_723_999))
    }

    @Test
    fun `responses carry the action name and escaped values`() {
        val reply = Upnp.response(Upnp.AV_TRANSPORT, "GetPositionInfo", mapOf("TrackURI" to "http://a/b?c=1&d=2"))
        assertTrue(reply.contains("<u:GetPositionInfoResponse"))
        assertTrue(reply.contains("c=1&amp;d=2"))
    }
}
