package com.sine.player.livetv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.sine.player.SineApp
import com.sine.player.mediaserver.PlexLive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * `plexlive://<serverId>/<dvrKey>/<channelVcn>/index.m3u8`: tunes the channel on first open and
 * serves Plex's HLS playlist with absolute URLs, so variants and segments come straight from Plex.
 * Live playlists are re-fetched every few seconds through the same URI; those reuse the session.
 */
@UnstableApi
class PlexLiveDataSource : BaseDataSource(true) {
    private var data: ByteArray? = null
    private var position = 0
    private var opened: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val server = SineApp.instance.remoteServers.get(uri.host.orEmpty()) ?: throw IOException("That Plex server is no longer set up in Sine")
        val segments = uri.pathSegments
        if (segments.size < 2) throw IOException("No channel in $uri")
        val (dvrKey, vcn) = segments[0] to segments[1]
        val live = PlexLive(server, SineApp.instance.settings.deviceId())
        val key = uri.toString()
        val text = try {
            val cached = sessions[key]?.takeIf { System.currentTimeMillis() - it.second < REUSE_MS }
            val fromCache = cached?.let { (url, _) -> runCatching { url to live.fetch(url) }.getOrNull() }
            val (url, body) = fromCache ?: live.tune(dvrKey, vcn).let { url -> url to live.fetch(url) }
            sessions[key] = url to System.currentTimeMillis()
            PlexLive.rewritePlaylist(body, url, server.token)
        } catch (e: Exception) {
            sessions.remove(key)
            throw IOException("Couldn't tune the channel: ${e.message}", e)
        }
        val bytes = text.toByteArray()
        data = bytes
        opened = uri
        position = dataSpec.position.toInt().coerceIn(0, bytes.size)
        transferStarted(dataSpec)
        val remaining = (bytes.size - position).toLong()
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining) else remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = data ?: throw IOException("Not open")
        if (length == 0) return 0
        if (position >= bytes.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, bytes.size - position)
        System.arraycopy(bytes, position, buffer, offset, n)
        position += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = opened

    override fun close() {
        if (opened != null) transferEnded()
        data = null
        opened = null
    }

    private companion object {
        /** Playlist URL per channel URI and when it was last fetched. */
        val sessions = ConcurrentHashMap<String, Pair<String, Long>>()
        const val REUSE_MS = 30_000L
    }
}
