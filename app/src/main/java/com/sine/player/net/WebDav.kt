package com.sine.player.net

import android.net.Uri
import com.sine.player.data.RemoteServer
import com.sine.player.data.RemoteUris
import com.sine.player.data.ServerKind
import com.sine.player.source.MediaEntry
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** One `<D:response>` of a PROPFIND reply. */
data class DavEntry(val href: String, val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

/** WebDAV browsing over `webdav://` (http) and `webdavs://` (https) URIs. */
object WebDav {

    /** The saved server whose base URL is the longest prefix of [uri]. */
    fun serverFor(servers: List<RemoteServer>, uri: String): RemoteServer? {
        val http = RemoteUris.webDavToHttp(uri).lowercase(Locale.US)
        return servers.filter { it.kind == ServerKind.WEBDAV }
            .filter { http.startsWith(RemoteUris.webDavToHttp(RemoteUris.webDavUri(it.address)).lowercase(Locale.US).trimEnd('/')) }
            .maxByOrNull { it.address.length }
    }

    fun authHeader(server: RemoteServer?): String? =
        server?.takeIf { it.username.isNotBlank() }?.let { Credentials.basic(it.username, it.password, Charsets.UTF_8) }

    fun list(uri: String, server: RemoteServer?, showHidden: Boolean): List<MediaEntry> {
        val folder = RemoteUris.webDavToHttp(uri).let { if (it.endsWith("/")) it else "$it/" }
        val request = Request.Builder()
            .url(folder)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .header("User-Agent", Net.USER_AGENT)
            .apply { authHeader(server)?.let { header("Authorization", it) } }
            .build()
        val xml = Net.text(request)
        val selfPath = rawPath(folder).trimEnd('/')
        val scheme = if (uri.startsWith("webdavs://")) "webdavs" else "webdav"
        val authority = Uri.parse(uri).encodedAuthority
        return parse(xml)
            .filter { rawPath(it.href).trimEnd('/') != selfPath }
            .filter { showHidden || !it.name.startsWith(".") }
            .map { entry ->
                val path = rawPath(entry.href).trimEnd('/')
                MediaEntry(
                    name = entry.name,
                    uri = "$scheme://$authority$path",
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    modified = entry.modified,
                )
            }
    }

    /** The encoded path of an href, which servers send either absolute or as a path, sometimes unencoded. */
    fun rawPath(href: String): String = runCatching { URI(href).rawPath }.getOrNull()
        ?: runCatching { URI(href.replace(" ", "%20")).rawPath }.getOrNull()
        ?: href

    /** Parses a multistatus document. Namespace prefixes vary between servers, so match local names. */
    fun parse(xml: String): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val responses = doc.getElementsByTagNameNS("*", "response")
        return (0 until responses.length).mapNotNull { i ->
            val response = responses.item(i) as Element
            val href = response.firstText("href") ?: return@mapNotNull null
            val isDirectory = response.getElementsByTagNameNS("*", "collection").length > 0
            val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
            val name = response.firstText("displayname")?.takeIf { it.isNotBlank() }
                ?: decoded.trimEnd('/').substringAfterLast('/')
            DavEntry(
                href = href,
                name = name,
                isDirectory = isDirectory,
                size = response.firstText("getcontentlength")?.toLongOrNull() ?: 0,
                modified = response.firstText("getlastmodified")?.let(::parseHttpDate) ?: 0,
            )
        }
    }

    private fun Element.firstText(localName: String): String? =
        getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim()

    fun parseHttpDate(value: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(value)?.time
    }.getOrNull()

    private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
}
