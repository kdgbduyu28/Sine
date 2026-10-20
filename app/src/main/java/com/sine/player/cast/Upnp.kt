package com.sine.player.cast

import java.util.Locale

/** Small, pure helpers for the UPnP/DLNA renderer: SOAP parsing and replies, times, DIDL titles. */
object Upnp {
    data class SoapCall(val service: String, val action: String, val args: Map<String, String>)

    /** `SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#Play"` plus the body's arguments. */
    fun parse(soapActionHeader: String?, body: String): SoapCall? {
        val header = soapActionHeader?.trim()?.trim('"') ?: return null
        val service = header.substringBefore('#')
        val action = header.substringAfter('#', "")
        if (action.isEmpty()) return null
        val element = Regex("""<(?:\w+:)?$action\b[^>]*>(.*)</(?:\w+:)?$action>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty()
        val args = Regex("""<(\w+)(?:\s[^>]*)?>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(element)
            .associate { it.groupValues[1] to unescape(it.groupValues[2].trim()) }
        return SoapCall(service, action, args)
    }

    fun response(service: String, action: String, values: Map<String, String> = emptyMap()): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
        append("""<u:${action}Response xmlns:u="$service">""")
        values.forEach { (k, v) -> append("<$k>").append(escape(v)).append("</$k>") }
        append("</u:${action}Response></s:Body></s:Envelope>")
    }

    fun fault(code: Int, description: String): String =
        """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${escape(description)}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

    /** 1:02:03 / 01:02:03.500 → ms. */
    fun parseTime(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
    }

    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    }

    /** dc:title from DIDL-Lite metadata (which arrives XML-escaped inside the SOAP body). */
    fun didlTitle(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        val xml = if (metadata.contains("&lt;")) unescape(metadata) else metadata
        return Regex("""<dc:title>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()
    }

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    fun deviceDescription(uuid: String, name: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>$MEDIA_RENDERER</deviceType>
    <friendlyName>${escape(name)}</friendlyName>
    <manufacturer>Sine</manufacturer>
    <modelName>Sine for Android TV</modelName>
    <modelNumber>1</modelNumber>
    <UDN>uuid:$uuid</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
      <service><serviceType>$AV_TRANSPORT</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/avt.xml</SCPDURL><controlURL>/control/avt</controlURL><eventSubURL>/event/avt</eventSubURL></service>
      <service><serviceType>$RENDERING_CONTROL</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/rc.xml</SCPDURL><controlURL>/control/rc</controlURL><eventSubURL>/event/rc</eventSubURL></service>
      <service><serviceType>$CONNECTION_MANAGER</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/cm.xml</SCPDURL><controlURL>/control/cm</controlURL><eventSubURL>/event/cm</eventSubURL></service>
    </serviceList>
  </device>
</root>"""

    /** Minimal service descriptions: the actions controllers look for. */
    fun scpd(actions: List<Pair<String, List<Pair<String, String>>>>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>""")
        actions.forEach { (name, args) ->
            append("<action><name>$name</name><argumentList>")
            args.forEach { (arg, direction) ->
                append("<argument><name>$arg</name><direction>$direction</direction><relatedStateVariable>A_ARG_TYPE_$arg</relatedStateVariable></argument>")
            }
            append("</argumentList></action>")
        }
        append("</actionList><serviceStateTable>")
        actions.flatMap { it.second }.map { it.first }.distinct().forEach {
            append("""<stateVariable sendEvents="no"><name>A_ARG_TYPE_$it</name><dataType>string</dataType></stateVariable>""")
        }
        append("""<stateVariable sendEvents="yes"><name>LastChange</name><dataType>string</dataType></stateVariable></serviceStateTable></scpd>""")
    }

    private fun args(vararg pairs: Pair<String, String>) = pairs.toList()
    private const val IN = "in"
    private const val OUT = "out"

    val avTransportScpd by lazy {
        scpd(
            listOf(
                "SetAVTransportURI" to args("InstanceID" to IN, "CurrentURI" to IN, "CurrentURIMetaData" to IN),
                "Play" to args("InstanceID" to IN, "Speed" to IN),
                "Pause" to args("InstanceID" to IN),
                "Stop" to args("InstanceID" to IN),
                "Seek" to args("InstanceID" to IN, "Unit" to IN, "Target" to IN),
                "GetPositionInfo" to args("InstanceID" to IN, "Track" to OUT, "TrackDuration" to OUT, "TrackMetaData" to OUT, "TrackURI" to OUT, "RelTime" to OUT, "AbsTime" to OUT, "RelCount" to OUT, "AbsCount" to OUT),
                "GetTransportInfo" to args("InstanceID" to IN, "CurrentTransportState" to OUT, "CurrentTransportStatus" to OUT, "CurrentSpeed" to OUT),
                "GetMediaInfo" to args("InstanceID" to IN, "NrTracks" to OUT, "MediaDuration" to OUT, "CurrentURI" to OUT, "CurrentURIMetaData" to OUT),
            ),
        )
    }
    val renderingControlScpd by lazy {
        scpd(
            listOf(
                "GetVolume" to args("InstanceID" to IN, "Channel" to IN, "CurrentVolume" to OUT),
                "SetVolume" to args("InstanceID" to IN, "Channel" to IN, "DesiredVolume" to IN),
                "GetMute" to args("InstanceID" to IN, "Channel" to IN, "CurrentMute" to OUT),
                "SetMute" to args("InstanceID" to IN, "Channel" to IN, "DesiredMute" to IN),
            ),
        )
    }
    val connectionManagerScpd by lazy {
        scpd(
            listOf(
                "GetProtocolInfo" to args("Source" to OUT, "Sink" to OUT),
                "GetCurrentConnectionIDs" to args("ConnectionIDs" to OUT),
                "GetCurrentConnectionInfo" to args("ConnectionID" to IN, "RcsID" to OUT, "AVTransportID" to OUT, "ProtocolInfo" to OUT, "PeerConnectionManager" to OUT, "PeerConnectionID" to OUT, "Direction" to OUT, "Status" to OUT),
            ),
        )
    }

    const val SINK_PROTOCOLS = "http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*,http-get:*:application/x-mpegURL:*,http-get:*:application/vnd.apple.mpegurl:*"
}
