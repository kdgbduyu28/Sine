package com.sine.player.mediaserver

import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/**
 * Asks Jellyfin, Emby or Plex to transcode to HLS under a bitrate cap, for watching away from home
 * on a slow connection. Embedded text subtitles may not come through; external ones still do.
 */
object Transcode {
    /** Mbps choices; 0 = always the original file. */
    val choices = listOf(0, 40, 20, 12, 8, 4, 2)

    fun label(mbps: Int) = if (mbps == 0) "Original" else "$mbps Mbps"

    /** True when [address] points at this home network (private IP, localhost or a .local/.lan/.home name). */
    fun isHomeNetwork(address: String): Boolean {
        val host = runCatching { URI(if (address.contains("://")) address else "http://$address").host }.getOrNull()?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".internal")) return true
        if (!host.contains('.') && !host.contains(':')) return true // a bare machine name only resolves on the LAN
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return host.startsWith("fd") || host.startsWith("fe80") // IPv6 private / link-local
        val (a, b) = parts[0] to parts[1]
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    /** Whether to transcode on [server] with the user's settings. */
    fun applies(server: RemoteServer, mbps: Int, awayOnly: Boolean): Boolean =
        mbps > 0 && server.kind.isMediaServer && !(awayOnly && isHomeNetwork(server.address))

    /** An HLS URL for item [id] capped at [mbps]. */
    fun url(server: RemoteServer, id: String, mbps: Int, deviceId: String, session: String = UUID.randomUUID().toString()): String {
        val bps = mbps * 1_000_000L
        return when (server.kind) {
            ServerKind.PLEX -> {
                val base = server.address.trimEnd('/')
                "$base/video/:/transcode/universal/start.m3u8?path=${enc("/library/metadata/$id")}&mediaIndex=0&partIndex=0&protocol=hls" +
                    "&fastSeek=1&directPlay=0&directStream=1&directStreamAudio=1&videoQuality=100&maxVideoBitrate=${mbps * 1000}" +
                    "&videoResolution=${resolutionFor(mbps)}&subtitles=auto&session=${enc(session)}&X-Plex-Session-Identifier=${enc(session)}" +
                    "&X-Plex-Client-Identifier=${enc(deviceId)}&X-Plex-Product=Sine&X-Plex-Platform=Android&X-Plex-Token=${enc(server.token)}"
            }
            else -> {
                val base = JellyfinClient.apiBase(server.address, server.kind == ServerKind.EMBY)
                "$base/Videos/${enc(id)}/master.m3u8?MediaSourceId=${enc(id)}&DeviceId=${enc(deviceId)}&PlaySessionId=${enc(session)}" +
                    "&VideoCodec=hevc,h264&AudioCodec=eac3,ac3,aac&TranscodingMaxAudioChannels=6&SegmentContainer=ts" +
                    "&VideoBitrate=${bps - 384_000}&AudioBitrate=384000&MaxStreamingBitrate=$bps&SubtitleMethod=Hls" +
                    "&BreakOnNonKeyFrames=true&api_key=${enc(server.token)}"
            }
        }
    }

    /** Plex wants a resolution ceiling to go with the bitrate. */
    fun resolutionFor(mbps: Int) = when {
        mbps >= 20 -> "3840x2160"
        mbps >= 8 -> "1920x1080"
        mbps >= 4 -> "1280x720"
        else -> "854x480"
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
