package com.sine.player.net

import android.content.Context
import android.net.wifi.WifiManager
import com.sine.player.source.MediaEntry
import com.sine.player.source.RemoteSubtitle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/** A UPnP MediaServer found on the network. */
data class DlnaServer(val udn: String, val name: String, val controlUrl: String)

/** One DIDL-Lite object: a container (folder) or a video item. */
data class DidlObject(
    val id: String,
    val title: String,
    val isContainer: Boolean,
    val url: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val durationMs: Long = 0,
    val subtitles: List<RemoteSubtitle> = emptyList(),
)

/**
 * DLNA/UPnP browsing: SSDP discovery, the ContentDirectory `Browse` action and DIDL-Lite parsing.
 *
 * URIs: `dlna://servers/` lists servers, `dlna://servers/<udn>/c/<objectId>` is a container and
 * `dlna://servers/<udn>/i/<objectId>` an item.
 */
class Dlna(private val context: Context) {
    private val known = ConcurrentHashMap<String, DlnaServer>()

    fun server(udn: String): DlnaServer? = known[udn] ?: run {
        discover()
        known[udn]
    }

    /** Sends M-SEARCH and collects answers for [timeoutMs]. */
    fun discover(timeoutMs: Int = 3000): List<DlnaServer> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("sine-ssdp")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                for (target in SEARCH_TARGETS) {
                    val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n".toByteArray()
                    socket.send(DatagramPacket(message, message.size, group, 1900))
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    ssdpHeader(String(packet.data, 0, packet.length), "location")?.let(locations::add)
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
        val found = locations.mapNotNull { location -> runCatching { describe(location) }.getOrNull() }
        found.forEach { known[it.udn] = it }
        return found.distinctBy { it.udn }
    }

    private fun describe(location: String): DlnaServer? {
        val xml = Net.text(Request.Builder().url(location).header("User-Agent", Net.USER_AGENT).build())
        return parseDescription(xml, location)
    }

    /** Lists a container, following the server's paging. */
    fun browse(server: DlnaServer, objectId: String): List<DidlObject> {
        val all = ArrayList<DidlObject>()
        var start = 0
        while (true) {
            val (objects, total) = browsePage(server, objectId, "BrowseDirectChildren", start)
            all += objects
            start += objects.size
            if (objects.isEmpty() || start >= total || start >= 5000) break
        }
        return all
    }

    fun metadata(server: DlnaServer, objectId: String): DidlObject? =
        browsePage(server, objectId, "BrowseMetadata", 0).first.firstOrNull()

    private fun browsePage(server: DlnaServer, objectId: String, flag: String, start: Int): Pair<List<DidlObject>, Int> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>
<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>${escape(objectId)}</ObjectID><BrowseFlag>$flag</BrowseFlag><Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$PAGE</RequestedCount><SortCriteria></SortCriteria></u:Browse>
</s:Body></s:Envelope>"""
        val request = Request.Builder()
            .url(server.controlUrl)
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            .header("User-Agent", Net.USER_AGENT)
            .build()
        return parseBrowseResponse(Net.text(request))
    }

    companion object {
        private const val PAGE = 200
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
        )

        fun ssdpHeader(message: String, name: String): String? = message.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

        /** Device description → server with an absolute ContentDirectory control URL. */
        fun parseDescription(xml: String, location: String): DlnaServer? {
            val doc = parseXml(xml)
            val root = doc.documentElement
            val base = root.text("URLBase")?.takeIf { it.isNotBlank() } ?: location
            val services = doc.getElementsByTagNameNS("*", "service")
            for (i in 0 until services.length) {
                val service = services.item(i) as Element
                if (service.text("serviceType")?.contains("ContentDirectory") != true) continue
                val control = service.text("controlURL") ?: continue
                // The device that owns this service is the closest <device> ancestor.
                var node = service.parentNode
                while (node != null && (node as? Element)?.localName != "device") node = node.parentNode
                val device = node as? Element ?: root
                val udn = device.text("UDN")?.removePrefix("uuid:") ?: continue
                val name = device.text("friendlyName") ?: udn
                return DlnaServer(udn, name, URL(URL(base), control).toString())
            }
            return null
        }

        /** SOAP Browse reply → objects and TotalMatches. The DIDL-Lite document arrives as escaped text. */
        fun parseBrowseResponse(xml: String): Pair<List<DidlObject>, Int> {
            val doc = parseXml(xml)
            val result = doc.getElementsByTagNameNS("*", "Result").item(0)?.textContent.orEmpty()
            val total = doc.getElementsByTagNameNS("*", "TotalMatches").item(0)?.textContent?.trim()?.toIntOrNull() ?: 0
            if (result.isBlank()) return emptyList<DidlObject>() to total
            return parseDidl(result) to total
        }

        fun parseDidl(didl: String): List<DidlObject> {
            val doc = parseXml(didl)
            val out = ArrayList<DidlObject>()
            val children = doc.documentElement.childNodes
            for (i in 0 until children.length) {
                val el = children.item(i) as? Element ?: continue
                val id = el.getAttribute("id")
                val title = el.text("title") ?: id
                when (el.localName) {
                    "container" -> out += DidlObject(id, title, isContainer = true)
                    "item" -> {
                        val upnpClass = el.text("class").orEmpty()
                        val resources = el.getElementsByTagNameNS("*", "res")
                        var video: Element? = null
                        val subs = ArrayList<RemoteSubtitle>()
                        for (r in 0 until resources.length) {
                            val res = resources.item(r) as Element
                            val mime = res.getAttribute("protocolInfo").split(':').getOrNull(2).orEmpty()
                            when {
                                mime.startsWith("video/") && video == null -> video = res
                                mime.startsWith("text/") || mime.contains("subrip") || mime.contains("srt") ->
                                    subs += RemoteSubtitle(res.textContent.trim(), "External", null, mime)
                            }
                        }
                        // Samsung servers put the subtitle in sec:CaptionInfoEx.
                        val captions = el.getElementsByTagNameNS("*", "CaptionInfoEx")
                        for (c in 0 until captions.length) {
                            val caption = captions.item(c) as Element
                            subs += RemoteSubtitle(caption.textContent.trim(), "External", null, caption.getAttribute("sec:type").ifBlank { null })
                        }
                        if (video == null && !upnpClass.startsWith("object.item.videoItem")) continue
                        val res = video ?: (resources.item(0) as? Element) ?: continue
                        out += DidlObject(
                            id = id,
                            title = title,
                            isContainer = false,
                            url = res.textContent.trim(),
                            mimeType = res.getAttribute("protocolInfo").split(':').getOrNull(2),
                            size = res.getAttribute("size").toLongOrNull() ?: 0,
                            durationMs = parseDuration(res.getAttribute("duration")),
                            subtitles = subs.distinctBy { it.url },
                        )
                    }
                }
            }
            return out
        }

        /** `1:23:45.678` → milliseconds. */
        fun parseDuration(value: String?): Long {
            if (value.isNullOrBlank()) return 0
            val parts = value.split(':')
            if (parts.size != 3) return 0
            val h = parts[0].toLongOrNull() ?: return 0
            val m = parts[1].toLongOrNull() ?: return 0
            val s = parts[2].toDoubleOrNull() ?: return 0
            return ((h * 3600 + m * 60) * 1000) + (s * 1000).toLong()
        }

        fun toEntries(udn: String, objects: List<DidlObject>): List<MediaEntry> = objects.map { obj ->
            if (obj.isContainer) {
                MediaEntry(obj.title, containerUri(udn, obj.id, obj.title), isDirectory = true)
            } else {
                MediaEntry(
                    name = obj.title,
                    uri = itemUri(udn, obj.id),
                    isDirectory = false,
                    size = obj.size,
                    playUri = obj.url,
                    playable = true,
                    subtitles = obj.subtitles,
                )
            }
        }

        fun containerUri(udn: String, objectId: String, title: String): String =
            "dlna://servers/${enc(udn)}/c/${enc(objectId)}?title=${enc(title)}"

        fun itemUri(udn: String, objectId: String): String = "dlna://servers/${enc(udn)}/i/${enc(objectId)}"

        private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))

        private fun Element.text(localName: String): String? =
            getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim()
    }
}
