package com.sine.player.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.sine.player.source.MediaFiles
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A parsed `Range: bytes=…` header. [suffix] is set for `bytes=-500` (the last 500 bytes). */
data class ByteRange(val start: Long, val end: Long?, val suffix: Long? = null) {

    /** Resolves against the full length, or null when the range can't be satisfied. */
    fun resolve(total: Long): LongRange? {
        if (suffix != null) {
            if (total <= 0) return null
            return (total - suffix).coerceAtLeast(0) until total
        }
        if (total in 0..start) return null
        val last = when {
            end == null -> total - 1
            total < 0 -> end
            else -> minOf(end, total - 1)
        }
        return start..last
    }

    companion object {
        /** First range of a `bytes=` header; multi-range requests are served as their first range. */
        fun parse(header: String?): ByteRange? {
            val value = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) } ?: return null
            val first = value.substring(6).split(',').first().trim()
            val dash = first.indexOf('-')
            if (dash < 0) return null
            val from = first.substring(0, dash).trim()
            val to = first.substring(dash + 1).trim()
            return when {
                from.isEmpty() -> to.toLongOrNull()?.takeIf { it > 0 }?.let { ByteRange(0, null, it) }
                else -> {
                    val start = from.toLongOrNull() ?: return null
                    val end = if (to.isEmpty()) null else to.toLongOrNull() ?: return null
                    if (end != null && end < start) null else ByteRange(start, end)
                }
            }
        }
    }
}

/**
 * Serves any URI Sine can read (SMB, SFTP, NFS, WebDAV, content://…) as plain HTTP on
 * 127.0.0.1, so mpv can open sources it has no protocol for. Each registered URI gets a random
 * path, so other apps on the device can't use the proxy to read arbitrary shares.
 */
@UnstableApi
class StreamProxy(private val sources: DataSource.Factory) {
    private val routes = ConcurrentHashMap<String, String>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null

    /** An http://127.0.0.1 URL that streams [uri] with range support. */
    fun urlFor(uri: String): String {
        val socket = ensureStarted()
        val token = routes.entries.firstOrNull { it.value == uri }?.key ?: UUID.randomUUID().toString().also { routes[it] = uri }
        val name = Uri.encode(Uri.parse(uri).lastPathSegment ?: "stream")
        return "http://127.0.0.1:${socket.localPort}/$token/$name"
    }

    @Synchronized
    private fun ensureStarted(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { serve(client) }
            }
        }
        return socket
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val token = parts.getOrNull(1)?.trimStart('/')?.substringBefore('/').orEmpty()
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val uri = routes[token]
            if (uri == null || (method != "GET" && method != "HEAD")) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            stream(uri, method == "HEAD", ByteRange.parse(headers["range"]), out)
        }
    }

    private fun stream(uri: String, headOnly: Boolean, range: ByteRange?, out: BufferedOutputStream) {
        val source = sources.createDataSource()
        try {
            // Suffix ranges need the length first; everything else opens right at the start offset.
            val start = if (range?.suffix != null) {
                val total = openLength(source, uri)
                range.resolve(total)?.first ?: 0
            } else {
                range?.start ?: 0
            }
            val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(start).build())
            val total = if (remaining == C.LENGTH_UNSET.toLong()) -1 else start + remaining
            val span = range?.let { ByteRange(start, it.end).resolve(total) }
            if (range != null && span == null) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            out.write(responseHead(uri, total, span).toByteArray())
            if (headOnly) {
                out.flush()
                return
            }
            var left = when {
                span != null -> span.last - span.first + 1
                total >= 0 -> total
                else -> Long.MAX_VALUE
            }
            val buffer = ByteArray(256 * 1024)
            while (left > 0) {
                val n = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
                left -= n
            }
            out.flush()
        } catch (e: Exception) {
            // mpv closes the connection on every seek; that shows up here as a broken pipe.
            Log.d(TAG, "Stream ended: ${e.message}")
        } finally {
            runCatching { source.close() }
        }
    }

    private fun openLength(source: DataSource, uri: String): Long = try {
        source.open(DataSpec(Uri.parse(uri)))
    } finally {
        source.close()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    fun shutdown() {
        runCatching { server?.close() }
        server = null
        routes.clear()
    }

    companion object {
        private const val TAG = "SineProxy"

        fun responseHead(uri: String, total: Long, span: LongRange?): String = buildString {
            if (span != null) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: bytes ${span.first}-${span.last}/${if (total >= 0) total.toString() else "*"}\r\n")
                append("Content-Length: ${span.last - span.first + 1}\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
                if (total >= 0) append("Content-Length: $total\r\n")
            }
            append("Content-Type: ${contentType(uri)}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n\r\n")
        }

        fun contentType(uri: String): String = when (MediaFiles.extension(uri.substringBefore('?'))) {
            "mkv", "mk3d" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "webm" -> "video/webm"
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            else -> "application/octet-stream"
        }
    }
}
