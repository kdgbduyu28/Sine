package com.sine.player.livetv

import com.sine.player.net.HostHeaders
import com.sine.player.net.Net
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI

/**
 * Saves a live stream to a file until [untilMs]: MPEG-TS streams are copied as they arrive; HLS
 * is followed segment by segment (best variant, init segment once for fMP4). Encrypted HLS is
 * refused, since the saved file wouldn't play.
 */
class StreamRecorder(private val url: String, private val file: File, private val untilMs: Long, private val cancelled: () -> Boolean) {

    data class MediaPlaylist(val segments: List<Pair<Long, String>>, val initUri: String?, val targetDurationS: Int, val ended: Boolean, val encrypted: Boolean)

    private var fragmentedMp4 = false

    /** Records, reporting bytes written; returns the saved file (renamed .mp4 for fMP4 HLS). Throws on failure. */
    fun record(progress: (Long) -> Unit): File {
        file.parentFile?.mkdirs()
        file.outputStream().buffered(1 shl 20).use { out ->
            val first = fetchHead(url)
            if (first.isPlaylist) recordHls(first.url, first.text!!, out, progress) else copy(url, out, progress)
        }
        if (!fragmentedMp4) return file
        val mp4 = File(file.parentFile, file.nameWithoutExtension + ".mp4")
        return if (file.renameTo(mp4)) mp4 else file
    }

    private class Head(val url: String, val isPlaylist: Boolean, val text: String?)

    /** Peeks at the stream: HLS playlists are small text starting with #EXTM3U. */
    private fun fetchHead(url: String): Head {
        val looksHls = url.substringBefore('?').endsWith(".m3u8") || url.substringBefore('?').endsWith(".m3u")
        if (!looksHls) return Head(url, false, null)
        val text = Net.text(request(url))
        return Head(url, text.trimStart().startsWith("#EXTM3U"), text)
    }

    private fun copy(url: String, out: OutputStream, progress: (Long) -> Unit) {
        var written = 0L
        var failures = 0
        // Reconnect a few times if the stream drops before the end.
        while (System.currentTimeMillis() < untilMs && !cancelled()) {
            try {
                Net.execute(request(url)).use { response ->
                    val input = response.body?.byteStream() ?: throw IOException("Empty stream")
                    val buffer = ByteArray(188 * 1024)
                    while (System.currentTimeMillis() < untilMs && !cancelled()) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        progress(written)
                    }
                }
                failures = 0
            } catch (e: IOException) {
                if (++failures > 5) throw e
                Thread.sleep(2_000)
            }
        }
    }

    private fun recordHls(masterUrl: String, masterText: String, out: OutputStream, progress: (Long) -> Unit) {
        val mediaUrl = bestVariant(masterText, masterUrl) ?: masterUrl
        var text = if (mediaUrl == masterUrl) masterText else Net.text(request(mediaUrl))
        val seen = LinkedHashSet<Long>()
        var initWritten = false
        var written = 0L
        var failures = 0
        while (System.currentTimeMillis() < untilMs && !cancelled()) {
            val playlist = parseMedia(text, mediaUrl)
            if (playlist.encrypted) throw IOException("This channel is encrypted (AES); it can't be recorded")
            if (!initWritten && playlist.initUri != null) {
                Net.execute(request(playlist.initUri)).use { r -> r.body?.bytes()?.let { out.write(it); written += it.size } }
                initWritten = true
                fragmentedMp4 = true
            }
            for ((sequence, segment) in playlist.segments) {
                if (!seen.add(sequence)) continue
                if (System.currentTimeMillis() >= untilMs || cancelled()) break
                Net.execute(request(segment)).use { r -> r.body?.byteStream()?.copyTo(out)?.let { written += it } }
                progress(written)
            }
            while (seen.size > 2000) seen.remove(seen.first())
            if (playlist.ended) break
            Thread.sleep((playlist.targetDurationS.coerceIn(1, 10) * 1000L) / 2)
            text = try {
                Net.text(request(mediaUrl)).also { failures = 0 }
            } catch (e: IOException) {
                if (++failures > 5) throw e
                text
            }
        }
    }

    private fun request(url: String): Request {
        val builder = Request.Builder().url(url).header("User-Agent", "Sine/1.0 (Android TV)")
        HostHeaders.forUrl(url).forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    companion object {
        /** The highest-bandwidth variant of a master playlist, or null for a media playlist. */
        fun bestVariant(text: String, baseUrl: String): String? {
            val lines = text.lines().map { it.trim() }
            var best: Pair<Long, String>? = null
            lines.forEachIndexed { i, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                if (best == null || bandwidth > best!!.first) best = bandwidth to resolve(baseUrl, uri)
            }
            return best?.second
        }

        fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
            var sequence = 0L
            var target = 6
            var init: String? = null
            var encrypted = false
            var ended = false
            val segments = mutableListOf<Pair<Long, String>>()
            var expectSegment = false
            text.lines().map { it.trim() }.forEach { line ->
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toDoubleOrNull()?.toInt() ?: 6
                    line.startsWith("#EXT-X-MAP:") -> init = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let { resolve(baseUrl, it) }
                    line.startsWith("#EXT-X-KEY:") -> encrypted = !line.contains("METHOD=NONE")
                    line.startsWith("#EXT-X-ENDLIST") -> ended = true
                    line.startsWith("#EXTINF") -> expectSegment = true
                    line.isNotEmpty() && !line.startsWith("#") && expectSegment -> {
                        segments += sequence to resolve(baseUrl, line)
                        sequence++
                        expectSegment = false
                    }
                }
            }
            return MediaPlaylist(segments, init, target, ended, encrypted)
        }

        fun resolve(base: String, uri: String): String = if (uri.startsWith("http://") || uri.startsWith("https://")) uri else URI(base).resolve(uri).toString()

        /** "Channel - Title - 2026-10-20 18.30.ts", without characters file systems reject. */
        fun fileName(channel: String, title: String, startMs: Long, extension: String): String {
            val date = CatchUp.format(startMs, "Y-m-d H.M", java.util.TimeZone.getDefault())
            return "$channel - $title - $date".replace(Regex("""[\\/:*?"<>|]"""), "_").take(150) + ".$extension"
        }
    }
}
