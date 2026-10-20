package com.sine.player.livetv

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.sine.player.SineApp
import com.sine.player.mediaserver.JellyfinClient
import java.io.IOException

/**
 * `jflive://<serverId>/<channelId>.ts`: tunes a Jellyfin/Emby Live TV channel when opened and
 * streams it over HTTP, closing the server's live stream when playback stops.
 */
@UnstableApi
class ServerLiveDataSource(private val http: DataSource) : DataSource {
    private var liveStream: Pair<JellyfinClient, String>? = null
    private var opened: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val server = SineApp.instance.remoteServers.get(uri.host.orEmpty()) ?: throw IOException("That server is no longer set up in Sine")
        val channelId = uri.lastPathSegment?.removeSuffix(".ts") ?: throw IOException("No channel in $uri")
        val client = JellyfinClient(server, SineApp.instance.settings.deviceId())
        val (url, liveStreamId) = try {
            client.openLiveStream(channelId)
        } catch (e: Exception) {
            throw IOException("Couldn't tune the channel: ${e.message}", e)
        }
        liveStream = liveStreamId?.let { client to it }
        opened = uri
        // Live: no seeking within the stream, unknown length.
        http.open(dataSpec.buildUpon().setUri(url).setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = http.read(buffer, offset, length)

    override fun getUri(): Uri? = opened

    override fun getResponseHeaders(): Map<String, List<String>> = http.responseHeaders

    override fun close() {
        runCatching { http.close() }
        liveStream?.let { (client, id) ->
            Thread { runCatching { client.closeLiveStream(id) }.onFailure { Log.w("SineLiveTv", "Closing live stream failed: ${it.message}") } }.start()
        }
        liveStream = null
        opened = null
    }
}
