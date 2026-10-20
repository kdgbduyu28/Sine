package com.sine.player.net

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Extra HTTP headers some IPTV providers require (a set User-Agent or Referer), keyed by host so
 * they also reach HLS segments and keys fetched from the same server.
 */
object HostHeaders {
    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    fun register(url: String, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val host = Uri.parse(url).host?.lowercase() ?: return
        byHost[host] = headers
    }

    fun clear() = byHost.clear()

    fun forUrl(url: String): Map<String, String> = Uri.parse(url).host?.lowercase()?.let { byHost[it] }.orEmpty()

    /** mpv's http-header-fields: everything except User-Agent, which has its own option. */
    fun mpvFields(headers: Map<String, String>): String =
        headers.filterKeys { !it.equals("User-Agent", true) }.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" }

    fun userAgent(headers: Map<String, String>): String? = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
}

/** Adds [HostHeaders] to HTTP requests made by Media3. */
@UnstableApi
class HostHeadersDataSource(private val http: DataSource) : DataSource by http {
    override fun open(dataSpec: DataSpec): Long {
        val extra = HostHeaders.forUrl(dataSpec.uri.toString())
        return http.open(if (extra.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(extra))
    }

    class Factory(private val http: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = HostHeadersDataSource(http.createDataSource())
    }
}
